package com.quire.reader.data.scan

import android.util.Log
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.BookEntity
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.KnownFile
import com.quire.reader.data.db.MAX_SQL_ARGS
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.mobi.MobiBook
import com.quire.reader.reader.PublicationLoader
import org.readium.r2.shared.publication.services.cover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.math.max

enum class ScanPhase { Idle, Finding, Reading, Done }

data class ScanProgress(
  val phase: ScanPhase = ScanPhase.Idle,
  /** Book files seen so far (grows during [ScanPhase.Finding]). */
  val found: Int = 0,
  /** Files read so far out of [total] that needed reading. */
  val processed: Int = 0,
  val total: Int = 0,
  val currentFile: String = "",
) {
  val fraction: Float get() = when (phase) { ScanPhase.Done -> 1f; ScanPhase.Reading -> if (total == 0) 1f else processed.toFloat() / total; else -> 0f }
}

/** What a scan changed. [removed] counts books whose file vanished; [moved] those found again under a new path or name. */
data class ScanResult(val added: Int, val updated: Int, val removed: Int, val unreadable: Int, val moved: Int = 0)

/** Work the scan hands to its readers: a full read of a new or changed file, or only reading the identity of a known one. */
private data class ScanJob(val file: FoundFile, val existing: KnownFile?, val identityOnly: Boolean = false)

/**
 * Keeps the database in step with the watched folders. Only new or changed files are read, in parallel,
 * and a failure on one file never stops the scan. A file that vanished never takes reading history with it:
 * its book becomes missing, and takes over a file found elsewhere with the same identity (a Calibre rename, a
 * move) or comes back when the file reappears.
 */
class LibraryScanner(
  private val db: QuireDatabase,
  private val loader: PublicationLoader,
  private val covers: CoverStore,
  private val settings: SettingsStore,
) {
  private val _progress = MutableStateFlow(ScanProgress())
  val progress: StateFlow<ScanProgress> = _progress.asStateFlow()
  private val mutex = Mutex()

  val isRunning: Boolean get() = _progress.value.phase == ScanPhase.Finding || _progress.value.phase == ScanPhase.Reading

  suspend fun scan(): ScanResult = mutex.withLock { withContext(Dispatchers.IO) { doScan() } }

  private suspend fun doScan(): ScanResult {
    val startedAt = System.currentTimeMillis()
    val useCalibre = settings.useCalibre.first()
    // Books a cover-broken build stored without a cover are never re-read (their file is unchanged), so once, reset
    // their mtime: this scan re-reads them and tries the cover again, then they settle (see queueCoverBackfill).
    if (!settings.coversBackfilled.first()) {
      db.books().queueCoverBackfill()
      settings.setCoversBackfilled(true)
      Log.i(TAG, "queued cover backfill for cover-less books")
    }
    val folders = db.folders().watched()
    val folderById = folders.associateBy { it.id }
    val known = db.books().knownFiles().associateBy { it.path }
    _progress.value = ScanProgress(ScanPhase.Finding)

    val foundPaths = HashSet<String>()
    val reachable = mutableSetOf<Long>()
    val found = AtomicInteger(0)
    val queued = AtomicInteger(0)
    val done = AtomicInteger(0)
    val unreadable = AtomicInteger(0)
    val updated = AtomicInteger(0)
    val identified = AtomicInteger(0)
    val revived = AtomicInteger(0)
    val newIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val walking = AtomicBoolean(true)
    fun publish(currentFile: String) = synchronized(_progress) {
      _progress.value = ScanProgress(if (walking.get()) ScanPhase.Finding else ScanPhase.Reading, found.get(), done.get(), queued.get(), currentFile)
    }

    // Walk the folders, reading each new or changed file as soon as it is found.
    readWhileFinding<ScanJob>(
      PARALLELISM,
      walk = { emit ->
        for (folder in folders) {
          val root = File(folder.path)
          if (!root.isDirectory || !root.canRead()) { Log.w(TAG, "folder unreachable: ${folder.path}"); continue }
          reachable += folder.id
          walk(root) { f ->
            val file = FoundFile(f.absolutePath, folder.id, f.length(), f.lastModified())
            foundPaths += file.path
            val n = found.incrementAndGet()
            val existing = known[file.path]
            when {
              needsRead(file, existing) -> { queued.incrementAndGet(); emit(ScanJob(file, existing)) }
              else -> {
                // An unchanged file of a missing book: it is back (a folder added again, a file put back).
                if (existing!!.missing) { db.books().revive(existing.id, file.folderId); revived.incrementAndGet() }
                if (!existing.hasIdentity) { queued.incrementAndGet(); emit(ScanJob(file, existing, identityOnly = true)) }
              }
            }
            if (n % 25 == 0) publish(relative(f, root))
          }
        }
        walking.set(false)
        publish("")
      },
      read = { job ->
        val file = job.file
        if (job.identityOnly) {
          val id = BookIdentity.read(File(file.path))
          db.books().setIdentity(job.existing!!.id, id.calibreUuid, id.epubUid, id.fingerprint)
          identified.incrementAndGet()
        } else {
          val existing = job.existing
          if (existing != null) updated.incrementAndGet()
          val stored = runCatching { readAndStore(file, folderById.getValue(file.folderId), existing?.id ?: 0L, existing?.addedAt, useCalibre) }
            .onFailure { Log.w(TAG, "failed to read ${file.path}", it) }
            .getOrNull()
          if (stored != null && existing == null) newIds += stored.id
          if (stored?.readable != true) unreadable.incrementAndGet()
        }
        done.incrementAndGet()
        publish(File(file.path).name)
      },
    )

    // Only a finished walk shows which known books are gone. They become missing rather than deleted, take over a file
    // that turned up elsewhere if one matches, and only those without reading history are then deleted.
    val now = System.currentTimeMillis()
    val vanished = removedIds(foundPaths, known, reachable)
    vanished.chunked(MAX_SQL_ARGS).forEach { db.books().markMissing(it, now) }
    val moves = reconcile()
    covers.deleteAll(db.books().purgeMissing())

    reachable.forEach { db.folders().markScanned(it, now) }
    val read = queued.get()
    _progress.value = ScanProgress(ScanPhase.Done, found.get(), read, read)
    // A move shows as a book added under the new path and one removed under the old; count it as a move instead.
    val vanishedSet = vanished.toHashSet()
    val movedNow = moves.count { it.liveId in newIds && it.missingId in vanishedSet }
    val added = (newIds.size - movedNow).coerceAtLeast(0)
    val removed = (vanished.size - movedNow).coerceAtLeast(0)
    Log.i(
      TAG,
      "scan done: ${found.get()} files, ${read - identified.get()} read, ${identified.get()} identified, ${moves.size} moved, " +
        "${revived.get()} back, ${vanished.size} vanished, ${unreadable.get()} unreadable in ${System.currentTimeMillis() - startedAt} ms",
    )
    return ScanResult(added = added, updated = updated.get(), removed = removed, unreadable = unreadable.get(), moved = moves.size)
  }

  /** Lets missing books take over the files of matching live books (see [matchMoves]). Returns the moves made. */
  private suspend fun reconcile(): List<Move> {
    val missing = db.books().missingIdentities()
    if (missing.isEmpty()) return emptyList()
    return matchMoves(missing, db.books().liveIdentities()).filter { move ->
      val adoption = db.books().adopt(move.missingId, move.liveId) ?: return@filter false
      covers.delete(adoption.staleCover)
      Log.i(TAG, "book ${move.missingId} moved to the file of book ${move.liveId}")
      true
    }
  }

  private class Stored(val id: Long, val readable: Boolean)

  /** Stores the book; [Stored.readable] is false when it was stored but could not be opened as a book. */
  private suspend fun readAndStore(file: FoundFile, folder: FolderEntity, existingId: Long, existingAddedAt: Long?, useCalibre: Boolean): Stored {
    val epub = File(file.path)
    val mobi = BookFormats.isMobi(epub)
    val opfFile = File(epub.parentFile, "metadata.opf")
    var meta: OpfMetadata? = null
    var source = BookEntity.SOURCE_FILE
    var coverPath: String? = null
    var readable = true

    if (useCalibre && opfFile.isFile) {
      meta = runCatching { opfFile.inputStream().use(OpfParser::parse) }.getOrNull()
      if (meta != null) {
        source = BookEntity.SOURCE_CALIBRE
        val cover = File(epub.parentFile, "cover.jpg")
        if (cover.isFile) coverPath = covers.saveFromFile(cover, file.path)
      }
    }
    if (mobi && (meta == null || (coverPath == null && source == BookEntity.SOURCE_CALIBRE))) {
      // A MOBI's header has its metadata and names its cover; converting the whole book is left until it is opened.
      runCatching {
        MobiBook.open(epub).use { book ->
          if (meta == null) meta = book.metadata.toOpfMetadata()
          if (coverPath == null) book.coverImage()?.let { coverPath = covers.saveFromBytes(it, file.path) }
        }
      }.onFailure { readable = false; Log.w(TAG, "cannot read MOBI ${file.path}: ${it.message}") }
    } else if (meta == null || (coverPath == null && source == BookEntity.SOURCE_CALIBRE)) {
      // No Calibre metadata (or no cover.jpg): ask the EPUB itself.
      val opened = loader.open(epub)
      opened.onSuccess { pub ->
        try {
          if (meta == null) meta = pub.toOpfMetadata()
          if (coverPath == null) pub.cover()?.let { bmp -> coverPath = covers.saveFromBitmap(bmp, file.path) }
        } finally { pub.close() }
      }.onFailure { readable = false }
    }

    // Both cover paths ran (Calibre cover.jpg, then the book itself) and still nothing: say so, so a silent
    // extraction regression is visible in logcat instead of just an empty card in the library.
    if (coverPath == null) Log.i(TAG, "no cover extracted for ${file.path}")

    val m = meta ?: OpfMetadata(
      title = epub.nameWithoutExtension.replace('_', ' ').trim().ifEmpty { "Untitled" },
      titleSort = null, authors = emptyList(), authorSort = null, series = null, seriesIndex = null, tags = emptyList(),
      rating = 0, description = null, year = null, language = null, addedAtMillis = null,
    )
    val identity = BookIdentity.read(epub)
    val author = m.authors.joinToString(" & ").ifEmpty { UNKNOWN_AUTHOR }
    val primary = m.authors.firstOrNull() ?: UNKNOWN_AUTHOR
    val entity = BookEntity(
      id = existingId,
      path = file.path,
      folderId = folder.id,
      sizeBytes = file.sizeBytes,
      mtime = file.mtime,
      title = m.title,
      sortTitle = m.titleSort?.lowercase() ?: sortKey(m.title),
      author = author,
      primaryAuthor = primary,
      authorSort = (m.authorSort ?: authorSortFrom(primary)).lowercase(),
      series = m.series,
      seriesIndex = m.seriesIndex,
      pubYear = m.year,
      language = m.language,
      description = m.description,
      calibreRating = m.rating,
      addedAt = existingAddedAt ?: (if (source == BookEntity.SOURCE_CALIBRE) m.addedAtMillis else null) ?: file.mtime,
      pageEstimate = estimatePages(epub),
      coverPath = coverPath,
      source = source,
      readable = readable,
      calibreUuid = identity.calibreUuid,
      epubUid = identity.epubUid,
      fingerprint = identity.fingerprint,
    )
    return Stored(db.books().save(entity, m.tags), readable)
  }

  /** Every book file under [root]; of a book kept in several formats under one name, only the best (see [BookFormats]). */
  private suspend fun walk(root: File, onFile: suspend (File) -> Unit) {
    val stack = ArrayDeque<File>().apply { add(root) }
    while (stack.isNotEmpty()) {
      val dir = stack.removeLast()
      val files = ArrayList<File>()
      for (f in dir.listFiles().orEmpty()) {
        when {
          f.name.startsWith(".") -> continue
          f.isDirectory -> if (!(dir == root && f.name == "Android")) stack.add(f)
          BookFormats.isBook(f.name) -> files += f
        }
      }
      for (f in BookFormats.preferred(files)) onFile(f)
    }
  }

  private fun relative(f: File, root: File) = f.path.removePrefix(root.parent.orEmpty()).trimStart('/')

  companion object {
    private const val TAG = "LibraryScanner"
    private const val PARALLELISM = 4
    const val UNKNOWN_AUTHOR = "Unknown author"

    /** Pages from the amount of text in the EPUB's HTML files (no decompression needed), or a MOBI's text length. */
    fun estimatePages(file: File): Int {
      if (BookFormats.isMobi(file)) {
        val chars = runCatching { MobiBook.open(file).use { it.textLength } }.getOrDefault(0L)
        if (chars > 0) return max(1, (chars / CHARS_PER_PAGE).toInt())
      }
      val chars = runCatching {
        ZipFile(file).use { zip ->
          var total = 0L
          for (e in zip.entries()) {
            val n = e.name.lowercase()
            if (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) total += max(0L, e.size)
          }
          total
        }
      }.getOrDefault(0L)
      return if (chars > 0) max(1, (chars / CHARS_PER_PAGE).toInt()) else max(1, (file.length() / 2000).toInt())
    }
    /** HTML bytes per Readium position; calibrated so a typical EPUB estimate lands near the real count. */
    private const val CHARS_PER_PAGE = 3000

    /** "The Hound of the Baskervilles" sorts under H. */
    fun sortKey(title: String): String {
      val t = title.trim().lowercase()
      for (article in listOf("the ", "a ", "an ")) if (t.startsWith(article) && t.length > article.length) return t.removePrefix(article)
      return t
    }

    /** "Arthur Conan Doyle" → "doyle, arthur conan". */
    fun authorSortFrom(name: String): String {
      if (name == UNKNOWN_AUTHOR) return "~"
      val parts = name.trim().split(Regex("\\s+"))
      return if (parts.size < 2) name.lowercase() else "${parts.last()}, ${parts.dropLast(1).joinToString(" ")}".lowercase()
    }
  }
}
