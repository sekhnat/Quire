package com.quire.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.tracing.trace
import androidx.tracing.traceAsync
import com.quire.reader.data.db.BookTagEntity
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.db.BookStateEntity
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.db.MissingBookRow
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.BookTextPage
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.data.index.LibraryIndexer
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextSearchResult
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.index.TextSearcher
import com.quire.reader.data.mobi.MobiBook
import com.quire.reader.data.scan.BookFormats
import com.quire.reader.data.scan.CoverStore
import com.quire.reader.data.scan.LibraryScanner
import com.quire.reader.data.scan.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
  private val indexDb: IndexDatabase,
  val scanner: LibraryScanner,
  private val covers: CoverStore,
  private val settings: SettingsStore,
  private val indexer: LibraryIndexer,
) {
  private val app = context.applicationContext

  /**
   * The library's books. The catalogue is mapped only when books change; a saved position re-reads just the reading
   * states (a small query), which are joined onto the mapped books here.
   */
  val books: Flow<List<Book>> = combine(
    db.books().observeCatalog().map { rows -> trace("Library.mapCatalog") { val now = System.currentTimeMillis(); rows.map { it.toBook(now) } } },
    db.states().observeReading().map { rows -> rows.associateBy { it.bookId } },
  ) { catalog, states ->
    trace("Library.joinStates") { val now = System.currentTimeMillis(); catalog.map { it.withReadingState(states[it.id], now) } }
  }
    .flowOn(Dispatchers.Default)
  val folders: Flow<List<FolderEntity>> = db.folders().observeAll()

  // ── library text search ──────────────────────────────────────────────────

  private val textSearcher = TextSearcher(db, indexDb)

  /** How much of the library is searchable right now. */
  val indexCoverage: Flow<IndexCoverage> = indexer.catalog.observeCoverage()

  /** Persisted chunk text across all books (not the search index or position data built on top of it). */
  val indexedTextBytes: Flow<Long> = indexer.catalog.observeTextBytes()

  /** Separate disk usage, including each database's WAL and shared-memory sidecars. */
  suspend fun storageBytes(): com.quire.reader.data.index.IndexStorageBytes = withContext(Dispatchers.IO) {
    fun bytes(name: String): Long {
      val main = app.getDatabasePath(name)
      return listOf("", "-wal", "-shm").sumOf { File(main.path + it).length() }
    }
    com.quire.reader.data.index.IndexStorageBytes(bytes(QuireDatabase.FILE_NAME), bytes(IndexDatabase.FILE_NAME))
  }

  /** Books whose text matches [query], grouped and ranked, narrowed by the library [filters]. Follows the database. */
  fun searchText(query: FtsQuery.Result.Query, filters: TextSearchFilters = TextSearchFilters.None, order: SearchOrder = SearchOrder.Relevance): Flow<TextSearchResult> =
    textSearcher.observe(query, filters, order)

  /** One page of a single book's matches, for "Show all in this book"; pass the previous page's `nextAfterSeq` as [afterSeq]. */
  suspend fun searchBookPage(bookId: Long, query: FtsQuery.Result.Query, afterSeq: Int = -1): BookTextPage =
    textSearcher.page(query, bookId, afterSeq)

  /** False once the book was changed or removed since [target] was indexed, so the target must not be opened at its text. */
  suspend fun isCurrent(target: IndexTarget): Boolean =
    db.books().byId(target.bookId)?.let { target.isCurrentFor(it.mtime, it.sizeBytes) } == true

  suspend fun rescan(): ScanResult = scanner.scan().also { indexer.sweep(); indexer.request() }

  /** Whether any watched folder holds books, which only all-files access can open. */
  suspend fun hasWatchedFolders(): Boolean = withContext(Dispatchers.IO) { db.folders().watched().isNotEmpty() }

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
    indexer.sweep()
    removal.keptWithHistory
  }

  /** Books whose file is gone but whose reading history is kept, most recently missed first. */
  val missingBooks: Flow<List<MissingBookRow>> = db.books().observeMissing()

  /** Deletes missing books and their reading history for good. */
  suspend fun forgetMissing(ids: List<Long>) = withContext(Dispatchers.IO) { covers.deleteAll(db.books().forgetMissing(ids)) }

  suspend fun forgetAllMissing() = forgetMissing(withContext(Dispatchers.IO) { db.books().missingIds() })

  /** Copies picked books (EPUB, AZW3, MOBI) into app storage (a folder that is always readable) and scans it. */
  suspend fun importFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
    val dir = File(app.filesDir, "imported").apply { mkdirs() }
    var copied = 0
    for (uri in uris) {
      runCatching {
        val incoming = File(dir, ".import-${System.currentTimeMillis()}.tmp")
        try {
          app.contentResolver.openInputStream(uri)?.use { input -> incoming.outputStream().use { input.copyTo(it) } } ?: error("cannot open $uri")
          // A picker may hand over a file without its name; a MOBI then still needs its extension to be found.
          val ext = if (MobiBook.isMobi(incoming)) "mobi" else "epub"
          val name = displayName(uri)?.takeIf { BookFormats.isBook(it) } ?: "import-${System.currentTimeMillis()}.$ext"
          val target = uniqueFile(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
          if (!incoming.renameTo(target)) error("cannot store $name")
          copied++
        } finally {
          incoming.delete()
        }
      }
    }
    if (copied > 0) scanImported()
    copied
  }

  /** Puts the imported-books folder in the library and scans it, after books were copied into it. */
  suspend fun scanImported() = withContext(Dispatchers.IO) {
    ensureFolder(File(app.filesDir, "imported").apply { mkdirs() }.absolutePath)
    scanner.scan()
    indexer.request()
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
    val ext = name.substringAfterLast('.', "epub")
    while (f.exists()) f = File(dir, "$base ($n).$ext").also { n++ }
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
  suspend fun savePosition(bookId: Long, locatorJson: String, progress: Float) = traceAsync("Library.savePosition", 0) {
    db.states().edit(bookId) {
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
  }

  fun bookmarks(bookId: Long): Flow<List<BookmarkEntity>> = db.annotations().observeBookmarks(bookId)
  suspend fun addBookmark(b: BookmarkEntity) = db.annotations().addBookmark(b)
  suspend fun deleteBookmark(id: Long) = db.annotations().deleteBookmark(id)

  fun highlights(bookId: Long): Flow<List<HighlightEntity>> = db.annotations().observeHighlights(bookId)
  suspend fun addHighlight(h: HighlightEntity) = db.annotations().addHighlight(h)
  suspend fun deleteHighlight(id: Long) = db.annotations().deleteHighlight(id)
  suspend fun setHighlightNote(id: Long, note: String?) = db.annotations().setHighlightNote(id, note?.trim()?.takeIf { it.isNotEmpty() })

  /** Serializes preference writes: a read-reduce-write never interleaves with another. */
  private val prefsMutex = Mutex()

  /**
   * This book's effective reading settings: each group from the book's own row when it
   * carries one, otherwise from the global defaults. An advanced-only row therefore keeps
   * inheriting the global basics.
   */
  fun readerPrefs(bookId: Long): Flow<ReaderPrefs> =
    combine(settings.readerDefaults, db.states().observe(bookId)) { defaults, state ->
      BookReaderPrefs.fromJson(state?.prefsJson)?.appliedTo(defaults) ?: defaults
    }

  /** True when the book overrides the basic settings with a whole set of its own. */
  fun hasBookOverride(bookId: Long): Flow<Boolean> =
    db.states().observe(bookId).map { BookReaderPrefs.fromJson(it?.prefsJson)?.hasBasic == true }

  /** True when the book carries an advanced object of its own (the book restore action). */
  fun hasBookAdvancedOverride(bookId: Long): Flow<Boolean> =
    db.states().observe(bookId).map { BookReaderPrefs.fromJson(it?.prefsJson)?.hasAdvanced == true }

  /** Whether the stored global defaults carry non-factory advanced values; reads stored JSON only. */
  val advancedDefaultsCustomized: Flow<Boolean> = settings.readerDefaults.map { !it.advanced.isFactory }

  /** The settings every book without its own settings uses. */
  val readerDefaults: Flow<ReaderPrefs> = settings.readerDefaults
  suspend fun setReaderDefaults(prefs: ReaderPrefs) = settings.setReaderDefaults(prefs)

  /** Whether the advanced reading controls are shown at all; independent of their values. */
  val advancedReadingEnabled: Flow<Boolean> = settings.advancedReadingEnabled
  suspend fun setAdvancedReadingEnabled(v: Boolean) = settings.setAdvancedReadingEnabled(v)

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

  /** A basic edit: writes the basic group and leaves the book's advanced object as it is. */
  suspend fun setBookPrefs(bookId: Long, prefs: ReaderPrefs) = prefsMutex.withLock {
    db.states().edit(bookId) { state ->
      val stored = BookReaderPrefs.fromJson(state.prefsJson) ?: BookReaderPrefs()
      state.copy(prefsJson = stored.withBasicFrom(prefs).toJson())
    }
  }

  /** An advanced edit: writes the effective advanced object and leaves the basic group as it is. */
  suspend fun setBookAdvancedPrefs(bookId: Long, advanced: AdvancedReaderPrefs) = prefsMutex.withLock {
    db.states().edit(bookId) { state ->
      val stored = BookReaderPrefs.fromJson(state.prefsJson) ?: BookReaderPrefs()
      state.copy(prefsJson = stored.copy(advanced = advanced).toJson())
    }
  }

  /** Restores this book's advanced controls to the globals; its basic override stays. */
  suspend fun clearBookAdvancedPrefs(bookId: Long) = prefsMutex.withLock {
    db.states().edit(bookId) { state ->
      val stored = BookReaderPrefs.fromJson(state.prefsJson) ?: return@edit state
      val next = stored.copy(advanced = null)
      state.copy(prefsJson = if (next.isEmpty) null else next.toJson())
    }
  }

  /** "Use for all books": these become the defaults and this book stops overriding them. */
  suspend fun useForAllBooks(bookId: Long, prefs: ReaderPrefs) = prefsMutex.withLock {
    settings.setReaderDefaults(prefs)
    db.states().edit(bookId) { it.copy(prefsJson = null) }
  }

  val brightness: Flow<Int> = settings.brightness
  suspend fun setBrightness(v: Int) = settings.setBrightness(v)

  companion object { const val FINISHED_AT = 0.985f }
}
