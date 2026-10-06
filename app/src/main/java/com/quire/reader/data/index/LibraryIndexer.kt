package com.quire.reader.data.index

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.EligibleBook
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.IndexStateEntity
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.scan.StoragePaths
import com.quire.reader.reader.PublicationLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.getOrElse
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Builds and maintains the library text index. Extraction runs only here, one book at a time under [mutex], and is
 * started only by [IndexWorker] (through [request]); callers never index inline.
 *
 * Nothing but a finished book is written: a book is replaced in one transaction, or marked failed/skipped, and an
 * interruption of any kind (reader opening, cancellation, the process dying) writes nothing, so the book stays eligible.
 * [epoch] is bumped by rebuild, delete and disable before they wait for the lock; extraction compares it before publishing,
 * so a book that finishes after the index was cleared is discarded instead of reappearing.
 */
@OptIn(ExperimentalReadiumApi::class)
class LibraryIndexer(
  context: Context,
  private val db: QuireDatabase,
  private val indexDb: IndexDatabase,
  private val loader: PublicationLoader,
  private val settings: SettingsStore,
  private val scope: CoroutineScope,
) {
  private val store = IndexStore(RoomIndexSql(indexDb))
  val catalog = IndexCatalog(db, indexDb)

  private val app = context.applicationContext
  private val workManager by lazy { WorkManager.getInstance(app) }

  private val mutex = Mutex()
  private val requestLock = Mutex()
  private val epoch = AtomicLong()
  private val sourcesBackfilled = AtomicBoolean(false)

  /** Extraction runs on one low-priority thread, so it never competes with the UI. */
  private val dispatcher = Executors.newSingleThreadExecutor { task ->
    Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "library-indexer").apply { isDaemon = true }
  }.asCoroutineDispatcher()

  private val _readerBusy = MutableStateFlow(false)
  /** True while a reader is opening or open. Memory only: a process death can never leave it set. */
  val readerBusy: StateFlow<Boolean> = _readerBusy.asStateFlow()
  fun setReaderBusy(busy: Boolean) { _readerBusy.value = busy }

  private val _running = MutableStateFlow(false)
  private val _permissionMissing = MutableStateFlow(false)

  /** What indexing is doing, or why not. Collect it to keep it live. */
  val activity: StateFlow<IndexActivity> by lazy {
    val flags = combine(settings.indexingEnabled, settings.indexChargingOnly, _permissionMissing, _readerBusy, _running) { enabled, charging, permission, reader, running ->
      ActivityInputs(enabled, permission, reader, running, charging, workQueued = false, eligible = 0, pending = 0)
    }
    val queued = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }
    combine(flags, queued, catalog.observeCoverage()) { f, queuedWork, c ->
      deriveActivity(f.copy(workQueued = queuedWork, eligible = c.eligible, pending = c.eligible - c.searchable - c.failed - c.skipped))
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), IndexActivity.Idle)
  }

  // ── scheduling ───────────────────────────────────────────────────────────

  /** Workers running [IndexWorker.doWork] in this process, the only place they run. Unlike WorkManager's records, never stale. */
  private val activeWorkers = AtomicInteger()

  /** Runs [block] as the worker, so requests made meanwhile queue behind it instead of replacing it. */
  internal suspend fun <T> asWorker(block: suspend () -> T): T {
    activeWorkers.incrementAndGet()
    try {
      return block()
    } finally {
      activeWorkers.decrementAndGet()
    }
  }

  /**
   * Asks for indexing to run. With no worker running, starts the chain afresh (see [enqueueChoice]); otherwise queues one
   * worker behind the running one, unless one is already waiting. Safe to call often.
   */
  fun request() {
    scope.launch { requestLock.withLock { enqueueIfAllowed() } }
  }

  /** Applies a changed indexing setting: drops obsolete requests (and abandons a running one when disabled), then requests under the new policy. */
  suspend fun applyPolicy() = requestLock.withLock {
    if (!settings.indexingEnabled.first()) epoch.incrementAndGet()
    workManager.cancelUniqueWork(WORK_NAME).await()
    enqueueIfAllowed()
  }

  /** Forgets every indexing decision and indexed text, then indexes the library again under the current policy. */
  suspend fun rebuild() {
    clearIndex { workManager.cancelUniqueWork(WORK_NAME).await() }
    request()
  }

  /** Turns indexing off and deletes the index. Books, metadata and reading state are untouched. */
  suspend fun deleteIndex() {
    settings.setIndexingEnabled(false)
    clearIndex { workManager.cancelUniqueWork(WORK_NAME).await() }
  }

  /**
   * Invalidates running extraction ([epoch] first, so nothing in flight can publish any more), lets [cancelWork] stop the
   * workers, then clears the tables once no book holds the lock.
   */
  internal suspend fun clearIndex(cancelWork: suspend () -> Unit = {}) {
    epoch.incrementAndGet()
    cancelWork()
    mutex.withLock {
      withContext(NonCancellable) {
        store.clearAll()
        store.incrementalVacuum()
        settings.setIndexOptimized(false)
      }
    }
  }

  /**
   * Merges the full-text tables into one segment each, once, after the first complete build: queries are fastest then, and
   * automerge keeps them close afterwards. A merge rewrites the whole index, so it only runs while the device is charging,
   * with free space for a second copy, and never while a reader is open.
   */
  suspend fun optimizeIfDue() {
    if (settings.indexOptimized.first() || _readerBusy.value || !isCharging()) return
    // An empty library drains at once; the merge is for after the first build that put text in the index.
    if (indexDb.states().searchable().isEmpty()) return
    val file = app.getDatabasePath(IndexDatabase.FILE_NAME)
    val size = listOf("", "-wal").sumOf { File(file.path + it).length() }
    if (file.parentFile!!.usableSpace < size) return
    val started = System.currentTimeMillis()
    // Step by step, so a reader opening (or the charger leaving) stops it at once; the next idle batch carries on.
    while (true) {
      currentCoroutineContext().ensureActive()
      if (_readerBusy.value || !isCharging()) return
      val done = mutex.withLock { withContext(NonCancellable) { store.mergeStep() } }
      if (done) break
    }
    settings.setIndexOptimized(true)
    Log.i(TAG, "optimized the index (${size / (1 shl 20)} MB) in ${System.currentTimeMillis() - started} ms")
  }

  private fun isCharging(): Boolean = app.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true

  /**
   * Forgets the indexed text of books that left the library (deleted, or missing). The index is a separate database, so
   * nothing cascades to it: this sweep is how it follows. Cheap enough to run before every batch.
   */
  suspend fun sweep() {
    val removed = store.retainOnly(db.books().presentIds())
    if (removed > 0) store.incrementalVacuum()
  }

  private suspend fun enqueueIfAllowed() {
    _permissionMissing.value = !StoragePaths.hasAllFilesAccess()
    if (!settings.indexingEnabled.first() || _permissionMissing.value) return
    val waiting = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    val policy = when (enqueueChoice(workerActive = activeWorkers.get() > 0, successorQueued = waiting)) {
      EnqueueChoice.Skip -> return
      EnqueueChoice.Append -> ExistingWorkPolicy.APPEND_OR_REPLACE
      EnqueueChoice.Replace -> ExistingWorkPolicy.REPLACE.also { if (waiting) Log.i(TAG, "request: replacing queued work that has not started") }
    }
    val constraints = Constraints.Builder().setRequiresCharging(settings.indexChargingOnly.first()).build()
    val work = OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).build()
    workManager.enqueueUniqueWork(WORK_NAME, policy, work)
  }

  /** Called by the worker, which knows best whether it could run. */
  internal fun setPermissionMissing(missing: Boolean) { _permissionMissing.value = missing }

  // ── extraction ───────────────────────────────────────────────────────────

  /**
   * Indexes eligible books, newest first, until none is left, [deadlineMillis] (epoch millis) passes, a reader opens or
   * the index is cleared. The deadline is checked between books; a book in progress is finished first.
   */
  suspend fun runBatch(deadlineMillis: Long): BatchResult = withContext(dispatcher) {
    _running.value = true
    try {
      batch(deadlineMillis)
    } finally {
      _running.value = false
    }
  }

  private suspend fun batch(deadlineMillis: Long): BatchResult {
    val startEpoch = epoch.get()
    // In slices that let other writers in, and stopping at once if a reader opens or the index is cleared; the next batch resumes.
    dropLegacyIndex(db.openHelper.writableDatabase, keepGoing = { !_readerBusy.value && epoch.get() == startEpoch })
      ?.let { Log.i(TAG, "dropped the old index from ${QuireDatabase.FILE_NAME} in $it ms") }
    sweep()
    backfillSources()
    val setAside = HashSet<Long>()
    var processed = 0
    var carried = 0
    fun stop(reason: BatchStop): BatchResult {
      if (carried > 0) Log.i(TAG, "kept the index of $carried books whose file signature changed but whose content did not")
      return BatchResult(processed, reason)
    }
    while (true) {
      currentCoroutineContext().ensureActive()
      if (epoch.get() != startEpoch) return stop(BatchStop.Superseded)
      if (_readerBusy.value) return stop(BatchStop.ReaderBusy)
      val book = catalog.eligibleBooks().firstOrNull { it.id !in setAside } ?: return stop(BatchStop.Drained)
      if (System.currentTimeMillis() >= deadlineMillis) return stop(BatchStop.Deadline)
      when (indexBook(book, startEpoch)) {
        Step.Settled -> processed++
        Step.Carried -> { processed++; carried++ }
        // The file changed or vanished after the last scan; the next scan updates the book and makes it eligible again.
        Step.SetAside -> setAside += book.id
        Step.ReaderBusy -> return stop(BatchStop.ReaderBusy)
        Step.Superseded -> return stop(BatchStop.Superseded)
      }
      yield()
    }
  }

  private enum class Step { Settled, Carried, SetAside, ReaderBusy, Superseded }

  private class Extraction(val result: Extracted, val chunks: List<IndexChunk> = emptyList(), val truncated: Boolean = false)

  private suspend fun indexBook(book: EligibleBook, startEpoch: Long): Step = mutex.withLock {
    if (epoch.get() != startEpoch) return Step.Superseded
    val file = File(book.path)
    if (!isUnchanged(file, book)) return Step.SetAside
    if (carryForward(book)) return Step.Carried

    val extraction = extract(file, startEpoch)
    // Look again at the file and the epoch only now, under the lock: nothing can clear the index before the write below.
    val settlement = settle(extraction.result, fileUnchanged = isUnchanged(file, book), epochCurrent = epoch.get() == startEpoch)
    val written = withContext(NonCancellable) {
      when (settlement) {
        Settlement.Publish -> publish(book, extraction)
        Settlement.MarkFailed -> markTerminal(book, IndexStateEntity.STATUS_FAILED)
        Settlement.MarkSkipped -> markTerminal(book, IndexStateEntity.STATUS_SKIPPED)
        Settlement.Discard -> false
      }
    }
    when {
      written -> Step.Settled
      extraction.result == Extracted.Interrupted -> if (epoch.get() != startEpoch) Step.Superseded else Step.ReaderBusy
      epoch.get() != startEpoch -> Step.Superseded
      else -> Step.SetAside
    }
  }

  /** Keeps the index of a book whose file signature moved but whose content did not (see [canCarryIndex]); true if it did. */
  private suspend fun carryForward(book: EligibleBook): Boolean {
    val state = indexDb.states().of(book.id) ?: return false
    if (!canCarryIndex(book.sizeBytes, book.fingerprint, IndexSignature(state.mtime, state.sizeBytes), store.sourceFingerprint(book.id))) return false
    if (!stillCurrent(book)) return false
    return withContext(NonCancellable) { store.resign(book.id, book.mtime, book.sizeBytes) }
  }

  /**
   * Once per process: books indexed before source fingerprints were kept get the fingerprint of the file their index still
   * matches, so they can carry their index forward too. Books whose file changed since are left out; they are re-indexed.
   */
  private suspend fun backfillSources() {
    if (!sourcesBackfilled.compareAndSet(false, true)) return
    mutex.withLock {
      val states = indexDb.states().all().associateBy { it.bookId }
      val known = store.sourcedBooks()
      val found = db.books().indexable().mapNotNull { b ->
        val s = states[b.id] ?: return@mapNotNull null
        val fingerprint = b.fingerprint ?: return@mapNotNull null
        if (b.id in known || s.mtime != b.mtime || s.sizeBytes != b.sizeBytes) null else b.id to fingerprint
      }.toMap()
      withContext(NonCancellable) { store.addSources(found) }
    }
  }

  private fun isUnchanged(file: File, book: EligibleBook): Boolean =
    file.isFile && file.lastModified() == book.mtime && file.length() == book.sizeBytes

  /** Whether [book] is still in the library with the file signature it was read from; the index is only written if so. */
  private suspend fun stillCurrent(book: EligibleBook): Boolean =
    db.books().byId(book.id)?.let { it.mtime == book.mtime && it.sizeBytes == book.sizeBytes && it.missingSince == null } == true

  private suspend fun publish(book: EligibleBook, extraction: Extraction): Boolean {
    if (!stillCurrent(book)) return false
    val unreadable = (extraction.result as? Extracted.Text)?.unreadableResources ?: 0
    store.replaceBook(book.id, book.mtime, book.sizeBytes, extraction.chunks, extraction.truncated, unreadable, fingerprint = book.fingerprint)
    return true
  }

  private suspend fun markTerminal(book: EligibleBook, status: String): Boolean {
    if (!stillCurrent(book)) return false
    store.markTerminal(book.id, book.mtime, book.sizeBytes, status, fingerprint = book.fingerprint)
    return true
  }

  /** Reads the whole book into chunks. Always closes the publication; cancellation propagates. */
  private suspend fun extract(file: File, startEpoch: Long): Extraction {
    val publication = loader.open(file).getOrElse { return Extraction(Extracted.Unreadable) }
    try {
      val chunks = ArrayList<IndexChunk>()
      val chunker = TextChunker()
      val extractor = BookExtractor(publication)
      while (true) {
        currentCoroutineContext().ensureActive()
        if (_readerBusy.value || epoch.get() != startEpoch) return Extraction(Extracted.Interrupted)
        val source = extractor.next() ?: break
        chunks += chunker.add(source)
        if (chunker.truncated) break
      }
      chunks += chunker.finish()
      // The chunker flushes lazily, so the size cap trips on the first element of the NEXT resource. That resource is the last
      // tally: it was opened last and only partly yielded, so it says nothing about how much text it holds and is left out here.
      logSparse(file, if (chunker.truncated) extractor.tallies.dropLast(1) else extractor.tallies)
      val unreadable = extractor.tallies.count { it.readFailed }
      return Extraction(extracted(chunks.size, extractor.tallies.size, unreadable), chunks, chunker.truncated)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return Extraction(Extracted.Unreadable)
    } finally {
      publication.close()
    }
  }

  /** Evidence of misreads other than the ones [normalizeHtml] repairs, for a later look; nothing is decided from it. */
  private fun logSparse(file: File, tallies: List<ResourceTally>) {
    tallies.filter { isSparse(it.bytes, it.yieldedChars) }.forEach {
      Log.i(TAG, "sparse resource: ${file.path} ${it.href}: ${it.yieldedChars} chars from ${it.bytes} bytes")
    }
  }

  companion object {
    /** The unique WorkManager chain every indexing request joins. */
    const val WORK_NAME = "indexing-chain"

    internal const val TAG = "LibraryIndexer"
  }
}
