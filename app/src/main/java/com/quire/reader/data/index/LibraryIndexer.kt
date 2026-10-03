package com.quire.reader.data.index

import android.content.Context
import android.os.Process
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.EligibleBook
import com.quire.reader.data.db.IndexStateEntity
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.db.TextChunkEntity
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
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse
import java.io.File
import java.util.concurrent.Executors
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
  private val loader: PublicationLoader,
  private val settings: SettingsStore,
  private val scope: CoroutineScope,
) {
  private val app = context.applicationContext
  private val workManager by lazy { WorkManager.getInstance(app) }

  private val mutex = Mutex()
  private val requestLock = Mutex()
  private val epoch = AtomicLong()

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
    combine(flags, queued, db.index().observeCoverage()) { f, queuedWork, c ->
      deriveActivity(f.copy(workQueued = queuedWork, eligible = c.eligible, pending = c.eligible - c.searchable - c.failed - c.skipped))
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), IndexActivity.Idle)
  }

  // ── scheduling ───────────────────────────────────────────────────────────

  /** Asks for indexing to run: queues one worker behind any running one, unless one is already waiting. Safe to call often. */
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
    mutex.withLock { withContext(NonCancellable) { db.index().clearAll() } }
  }

  private suspend fun enqueueIfAllowed() {
    _permissionMissing.value = !StoragePaths.hasAllFilesAccess()
    if (!settings.indexingEnabled.first() || _permissionMissing.value) return
    // A request that has not started yet will see everything, so another would only lengthen the chain.
    val waiting = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    if (waiting) return
    val constraints = Constraints.Builder().setRequiresCharging(settings.indexChargingOnly.first()).build()
    val work = OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).build()
    workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
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
    val setAside = HashSet<Long>()
    var processed = 0
    while (true) {
      currentCoroutineContext().ensureActive()
      if (epoch.get() != startEpoch) return BatchResult(processed, BatchStop.Superseded)
      if (_readerBusy.value) return BatchResult(processed, BatchStop.ReaderBusy)
      val book = db.index().eligibleBooks().firstOrNull { it.id !in setAside } ?: return BatchResult(processed, BatchStop.Drained)
      if (System.currentTimeMillis() >= deadlineMillis) return BatchResult(processed, BatchStop.Deadline)
      when (indexBook(book, startEpoch)) {
        Step.Settled -> processed++
        // The file changed or vanished after the last scan; the next scan updates the book and makes it eligible again.
        Step.SetAside -> setAside += book.id
        Step.ReaderBusy -> return BatchResult(processed, BatchStop.ReaderBusy)
        Step.Superseded -> return BatchResult(processed, BatchStop.Superseded)
      }
      yield()
    }
  }

  private enum class Step { Settled, SetAside, ReaderBusy, Superseded }

  private class Extraction(val result: Extracted, val chunks: List<IndexChunk> = emptyList(), val truncated: Boolean = false)

  private suspend fun indexBook(book: EligibleBook, startEpoch: Long): Step = mutex.withLock {
    if (epoch.get() != startEpoch) return Step.Superseded
    val file = File(book.path)
    if (!isUnchanged(file, book)) return Step.SetAside

    val extraction = extract(file, startEpoch)
    // Look again at the file and the epoch only now, under the lock: nothing can clear the index before the write below.
    val settlement = settle(extraction.result, fileUnchanged = isUnchanged(file, book), epochCurrent = epoch.get() == startEpoch)
    val written = withContext(NonCancellable) {
      when (settlement) {
        Settlement.Publish -> publish(book, extraction)
        Settlement.MarkFailed -> db.index().markTerminal(book.id, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_FAILED)
        Settlement.MarkSkipped -> db.index().markTerminal(book.id, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_SKIPPED)
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

  private fun isUnchanged(file: File, book: EligibleBook): Boolean =
    file.isFile && file.lastModified() == book.mtime && file.length() == book.sizeBytes

  private suspend fun publish(book: EligibleBook, extraction: Extraction): Boolean {
    val chunks = extraction.chunks
    val rows = chunks.map {
      TextChunkEntity(
        bookId = book.id, seq = it.seq, chapter = it.chapter, href = it.href, tokenStart = it.tokenStart, tokenEnd = it.tokenEnd,
        primaryEndByte = it.primaryEndByte, text = it.text, mapping = it.mappingJson, progression = it.progression,
      )
    }
    val state = IndexStateEntity(
      bookId = book.id, mtime = book.mtime, sizeBytes = book.sizeBytes, status = IndexStateEntity.STATUS_DONE,
      completedAt = System.currentTimeMillis(), chunkCount = rows.size, textBytes = chunks.sumOf { it.text.utf8Length().toLong() },
      truncated = extraction.truncated,
    )
    return db.index().replaceBook(book.id, book.mtime, book.sizeBytes, rows, state)
  }

  /** Reads the whole book into chunks. Always closes the publication; cancellation propagates. */
  private suspend fun extract(file: File, startEpoch: Long): Extraction {
    val publication = loader.open(file).getOrElse { return Extraction(Extracted.Unreadable) }
    try {
      val chunks = ArrayList<IndexChunk>()
      val chunker = TextChunker()
      val content = publication.content() ?: return Extraction(Extracted.Text(0))
      val iterator = content.iterator()
      val chapters = chapterLabeler(publication)
      var currentHref = ""
      var currentOrder: ResourceOrder? = null
      while (true) {
        currentCoroutineContext().ensureActive()
        if (_readerBusy.value || epoch.get() != startEpoch) return Extraction(Extracted.Interrupted)
        val element = iterator.nextOrNull() ?: break
        val text = element as? Content.TextElement ?: continue
        val href = text.locator.href.removeFragment()
        if (href.toString() != currentHref) {
          currentHref = href.toString()
          currentOrder = if (chapters.hasAnchors(currentHref)) resourceOrder(publication, href) else null
        }
        val source = text.toSource(chapters, currentOrder) ?: continue
        chunks += chunker.add(source)
        if (chunker.truncated) break
      }
      chunks += chunker.finish()
      return Extraction(Extracted.Text(chunks.size), chunks, chunker.truncated)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return Extraction(Extracted.Unreadable)
    } finally {
      publication.close()
    }
  }

  private fun chapterLabeler(publication: Publication): ChapterLabeler {
    fun Link.entries(): List<ChapterEntry> =
      listOf(ChapterEntry(url().removeFragment().toString(), url().fragment?.takeIf { it.isNotEmpty() }, title.orEmpty())) + children.flatMap { it.entries() }
    return ChapterLabeler(
      readingOrder = publication.readingOrder.map { it.url().removeFragment().toString() },
      entries = publication.tableOfContents.flatMap { it.entries() },
    )
  }

  /**
   * The document order of a resource whose chapters begin at anchors, read and parsed the way Readium's content iterator does,
   * or null when it cannot be read (the chapters then fall back to matching selector text).
   */
  private suspend fun resourceOrder(publication: Publication, href: Url): ResourceOrder? {
    val resource = publication.get(href) ?: return null
    try {
      val bytes = resource.read().getOrElse { return null }
      return ResourceOrder.parse(String(bytes, Charsets.UTF_8))
    } finally {
      resource.close()
    }
  }

  private fun Content.TextElement.toSource(chapters: ChapterLabeler, resourceOrder: ResourceOrder?): SourceElement? {
    val text = segments.joinToString("") { it.text }
    if (text.isBlank()) return null
    val css = locator.locations.otherLocations["cssSelector"] as? String
    val href = locator.href.removeFragment().toString()
    val chapter = chapters.markFor(href, css, resourceOrder)
    return SourceElement(
      href = href,
      text = text,
      headingStart = isHeadingSelector(css),
      locatorJson = slimLocator(locator).toJSON().toString(),
      progression = locator.locations.totalProgression ?: 0.0,
      chapter = chapter.label,
      chapterStart = chapter.startsChapter,
    )
  }

  /** Only what finds the element again: the stored text is the source of the highlight, so the large `text` part is dropped. The cssSelector is left out too — navigation finds the passage by its text — and only the progression position is kept. */
  private fun slimLocator(locator: Locator): Locator =
    Locator(
      href = locator.href,
      mediaType = locator.mediaType,
      locations = Locator.Locations(
        progression = locator.locations.progression,
      ),
    )

  companion object {
    /** The unique WorkManager chain every indexing request joins. */
    const val WORK_NAME = "indexing-chain"
  }
}
