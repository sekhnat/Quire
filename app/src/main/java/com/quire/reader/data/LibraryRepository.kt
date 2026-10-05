package com.quire.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.quire.reader.data.db.BookTagEntity
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.db.BookStateEntity
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.db.MissingBookRow
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.BookTextPage
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.data.index.LibraryIndexer
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextSearchResult
import com.quire.reader.data.index.TextSearcher
import com.quire.reader.data.scan.CoverStore
import com.quire.reader.data.scan.LibraryScanner
import com.quire.reader.data.scan.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/** The one place the UI reads and writes library data. */
class LibraryRepository(
  context: Context,
  private val db: QuireDatabase,
  val scanner: LibraryScanner,
  private val covers: CoverStore,
  private val settings: SettingsStore,
  private val indexer: LibraryIndexer,
) {
  private val app = context.applicationContext

  val books: Flow<List<Book>> = db.books().observeAll()
    .map { rows -> val now = System.currentTimeMillis(); rows.map { it.toBook(now) } }
    .flowOn(Dispatchers.Default)
  val folders: Flow<List<FolderEntity>> = db.folders().observeAll()

  // ── library text search ──────────────────────────────────────────────────

  private val textSearcher = TextSearcher(db)

  /** How much of the library is searchable right now. */
  val indexCoverage: Flow<IndexCoverage> = db.index().observeCoverage()

  /** Persisted chunk text across all books (not the search index or position data built on top of it). */
  val indexedTextBytes: Flow<Long> = db.index().observeTextBytes()

  /** Bytes the library database takes on disk: the database file and its write-ahead log (and shared-memory) files. Not the index alone. */
  suspend fun databaseBytes(): Long = withContext(Dispatchers.IO) {
    val main = app.getDatabasePath(QuireDatabase.FILE_NAME)
    listOf("", "-wal", "-shm").sumOf { File(main.path + it).length() }
  }

  /** Books whose text matches [query], grouped and ranked, narrowed by the library [filters]. Follows the database. */
  fun searchText(query: FtsQuery.Result.Query, filters: TextSearchFilters = TextSearchFilters.None): Flow<TextSearchResult> =
    textSearcher.observe(query, filters)

  /** One page of a single book's matches, for "Show all in this book"; pass the previous page's `nextAfterSeq` as [afterSeq]. */
  suspend fun searchBookPage(bookId: Long, query: FtsQuery.Result.Query, afterSeq: Int = -1): BookTextPage =
    textSearcher.page(query, bookId, afterSeq)

  /** False once the book was changed or removed since [target] was indexed, so the target must not be opened at its text. */
  suspend fun isCurrent(target: IndexTarget): Boolean =
    db.books().byId(target.bookId)?.let { target.isCurrentFor(it.mtime, it.sizeBytes) } == true

  suspend fun rescan(): ScanResult = scanner.scan().also { indexer.request() }

  /** Adds a folder to watch. Returns false if it can't be read or is already in the library. */
  suspend fun addFolder(path: String): Boolean = withContext(Dispatchers.IO) {
    val dir = File(path)
    if (!dir.isDirectory || !dir.canRead()) return@withContext false
    ensureFolder(dir.absolutePath)
  }

  /**
   * Puts [path] in the library; false if it already is. A folder removed earlier is put back with its id, so the next
   * scan finds its missing books at their paths and their reading history returns.
   */
  private suspend fun ensureFolder(path: String): Boolean {
    val existing = db.folders().byPath(path) ?: return db.folders().insert(FolderEntity(path = path)) != -1L
    if (existing.watched) return false
    db.folders().rewatch(existing.id)
    return true
  }

  /**
   * Takes a folder out of the library. Books with reading history stay as missing books (see [missingBooks]) and come
   * back if the folder is added again; the rest are forgotten. The files on disk are not touched. Returns how many books
   * kept their history.
   */
  suspend fun removeFolder(id: Long): Int = withContext(Dispatchers.IO) {
    val removal = db.books().removeFolder(id, System.currentTimeMillis())
    covers.deleteAll(removal.staleCovers)
    db.index().forgetMissing()
    removal.keptWithHistory
  }

  /** Books whose file is gone but whose reading history is kept, most recently missed first. */
  val missingBooks: Flow<List<MissingBookRow>> = db.books().observeMissing()

  /** Deletes missing books and their reading history for good. */
  suspend fun forgetMissing(ids: List<Long>) = withContext(Dispatchers.IO) { covers.deleteAll(db.books().forgetMissing(ids)) }

  suspend fun forgetAllMissing() = forgetMissing(withContext(Dispatchers.IO) { db.books().missingIds() })

  /** Copies picked EPUBs into app storage (a folder that is always readable) and scans it. */
  suspend fun importFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
    val dir = File(app.filesDir, "imported").apply { mkdirs() }
    var copied = 0
    for (uri in uris) {
      runCatching {
        val name = displayName(uri)?.takeIf { it.endsWith(".epub", true) } ?: "import-${System.currentTimeMillis()}.epub"
        val target = uniqueFile(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        app.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("cannot open $uri")
        copied++
      }
    }
    if (copied > 0) {
      ensureFolder(dir.absolutePath)
      scanner.scan()
      indexer.request()
    }
    copied
  }

  suspend fun setFinished(bookId: Long, finished: Boolean) = db.states().edit(bookId) {
    if (finished) it.copy(status = BookStateEntity.STATUS_FINISHED, progress = 1f, finishedAt = System.currentTimeMillis())
    else it.copy(status = if (it.lastOpenedAt > 0) BookStateEntity.STATUS_READING else BookStateEntity.STATUS_UNREAD, progress = if (it.progress >= 1f) 0f else it.progress, finishedAt = 0)
  }

  suspend fun setUserRating(bookId: Long, rating: Int?) = db.states().edit(bookId) { it.copy(userRating = rating) }

  suspend fun addTag(bookId: Long, tag: String) {
    val t = tag.trim()
    if (t.isNotEmpty()) db.books().insertTags(listOf(BookTagEntity(bookId, t, BookTagEntity.ORIGIN_USER)))
  }

  suspend fun removeTag(bookId: Long, tag: String) = db.books().deleteUserTag(bookId, tag)

  private fun displayName(uri: Uri): String? {
    val cursor = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) ?: return null
    return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
  }

  private fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    var n = 1
    val base = name.substringBeforeLast('.')
    while (f.exists()) f = File(dir, "$base ($n).epub").also { n++ }
    return f
  }

  // ── reading ──────────────────────────────────────────────────────────────

  suspend fun readingState(bookId: Long): BookStateEntity? = db.states().get(bookId)
  suspend fun book(bookId: Long) = db.books().byId(bookId)
  suspend fun markUnreadable(bookId: Long) = db.books().setReadable(bookId, false)

  /** Called when a book is opened: it now counts as "in progress" and moves to the top of Recently opened. */
  suspend fun markOpened(bookId: Long) = db.states().edit(bookId) {
    it.copy(lastOpenedAt = System.currentTimeMillis(), status = if (it.status == BookStateEntity.STATUS_UNREAD) BookStateEntity.STATUS_READING else it.status)
  }

  /** Real page count from Readium's position list, replacing the size-based estimate. */
  suspend fun updatePageCount(bookId: Long, pages: Int) { if (pages > 0) db.books().setPages(bookId, pages) }

  /** Saves where the reader is. Reaching the end marks the book finished. */
  suspend fun savePosition(bookId: Long, locatorJson: String, progress: Float) = db.states().edit(bookId) {
    val status = when {
      progress >= FINISHED_AT -> BookStateEntity.STATUS_FINISHED
      progress > 0f -> BookStateEntity.STATUS_READING
      else -> it.status
    }
    it.copy(
      locatorJson = locatorJson, progress = progress, status = status,
      finishedAt = if (status == BookStateEntity.STATUS_FINISHED && it.status != status) System.currentTimeMillis() else it.finishedAt,
    )
  }

  fun bookmarks(bookId: Long): Flow<List<BookmarkEntity>> = db.annotations().observeBookmarks(bookId)
  suspend fun addBookmark(b: BookmarkEntity) = db.annotations().addBookmark(b)
  suspend fun deleteBookmark(id: Long) = db.annotations().deleteBookmark(id)

  fun highlights(bookId: Long): Flow<List<HighlightEntity>> = db.annotations().observeHighlights(bookId)
  suspend fun addHighlight(h: HighlightEntity) = db.annotations().addHighlight(h)
  suspend fun deleteHighlight(id: Long) = db.annotations().deleteHighlight(id)
  suspend fun setHighlightNote(id: Long, note: String?) = db.annotations().setHighlightNote(id, note?.trim()?.takeIf { it.isNotEmpty() })

  /** This book's reading settings: its own if it has any, otherwise the defaults. */
  fun readerPrefs(bookId: Long): Flow<ReaderPrefs> =
    combine(settings.readerDefaults, db.states().observe(bookId)) { defaults, state -> ReaderPrefs.fromJson(state?.prefsJson) ?: defaults }

  fun hasBookOverride(bookId: Long): Flow<Boolean> = db.states().observe(bookId).map { ReaderPrefs.fromJson(it?.prefsJson) != null }

  /** The settings every book without its own settings uses. */
  val readerDefaults: Flow<ReaderPrefs> = settings.readerDefaults
  suspend fun setReaderDefaults(prefs: ReaderPrefs) = settings.setReaderDefaults(prefs)

  suspend fun clearBookPrefs(bookId: Long) = db.states().edit(bookId) { it.copy(prefsJson = null) }
  suspend fun clearAllBookPrefs() = db.states().clearAllPrefs()

  val useCalibre: Flow<Boolean> = settings.useCalibre
  val watchNewBooks: Flow<Boolean> = settings.watchNewBooks
  suspend fun setUseCalibre(v: Boolean) = settings.setUseCalibre(v)
  suspend fun setWatchNewBooks(v: Boolean) = settings.setWatchNewBooks(v)

  val indexingEnabled: Flow<Boolean> = settings.indexingEnabled
  val indexChargingOnly: Flow<Boolean> = settings.indexChargingOnly
  suspend fun setIndexingEnabled(v: Boolean) = settings.setIndexingEnabled(v)
  suspend fun setIndexChargingOnly(v: Boolean) = settings.setIndexChargingOnly(v)

  suspend fun setBookPrefs(bookId: Long, prefs: ReaderPrefs) = db.states().edit(bookId) { it.copy(prefsJson = prefs.toJson()) }

  /** "Use for all books": these become the defaults and this book stops overriding them. */
  suspend fun useForAllBooks(bookId: Long, prefs: ReaderPrefs) {
    settings.setReaderDefaults(prefs)
    db.states().edit(bookId) { it.copy(prefsJson = null) }
  }

  val brightness: Flow<Int> = settings.brightness
  suspend fun setBrightness(v: Int) = settings.setBrightness(v)

  companion object { const val FINISHED_AT = 0.985f }
}
