package com.quire.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.tracing.trace
import androidx.tracing.traceAsync
import com.quire.reader.data.db.BookEditEntity
import com.quire.reader.data.db.BookTagEntity
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.db.ReadingSessionEntity
import com.quire.reader.data.db.BookStateEntity
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.db.MissingBookRow
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.MAX_SQL_ARGS
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
import com.quire.reader.data.scan.BookIdentity
import com.quire.reader.data.scan.StoragePaths
import com.quire.reader.data.scan.CoverStore
import com.quire.reader.data.scan.LibraryScanner
import com.quire.reader.data.scan.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/** What an action on several books' files did: how many files it [done] and how many [failed]. */
data class FileOpResult(val done: Int, val failed: Int)

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
  suspend fun forgetMissing(ids: List<Long>) = withContext(Dispatchers.IO) {
    val chosen = userCoversOf(ids)
    covers.deleteAll(db.books().forgetMissing(ids))
    covers.deleteAll(chosen)
  }

  /** The covers the user chose for [ids]; their rows go with the books, so the files must be deleted by hand. */
  private suspend fun userCoversOf(ids: List<Long>): List<String> {
    val set = ids.toHashSet()
    return db.edits().all().filter { it.bookId in set }.mapNotNull { it.coverPath }
  }

  /** Copies picked books (EPUB, AZW3, MOBI) into app storage (a folder that is always readable) and scans it. */
  suspend fun importFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
    val dir = File(app.filesDir, "imported").apply { mkdirs() }
    var copied = 0
    for (uri in uris) {
      runCatchingCancellable {
        val incoming = File(dir, ".import-${System.currentTimeMillis()}.tmp")
        try {
          app.contentResolver.openInputStream(uri)?.use { input -> incoming.outputStream().use { input.copyTo(it) } } ?: error("cannot open $uri")
          storeImported(incoming, displayName(uri))
          copied++
        } finally {
          incoming.delete()
        }
      }.onFailure { Log.w(TAG, "cannot import $uri", it) }
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

  /** Adds the user tag [tag] to every book in [bookIds]; books that already carry it keep theirs. */
  suspend fun addTag(bookIds: List<Long>, tag: String) {
    val t = tag.trim()
    if (t.isEmpty()) return
    bookIds.chunked(MAX_SQL_ARGS).forEach { ids -> db.books().insertTags(ids.map { BookTagEntity(it, t, BookTagEntity.ORIGIN_USER) }) }
  }

  // ── several books at once ────────────────────────────────────────────────

  /**
   * Takes books out of the library and keeps their files: their paths are remembered so scans skip them (see
   * [removedBooks]), and they become missing, so those with reading history get it back if they are brought back.
   * Returns how many books were removed.
   */
  suspend fun removeFromLibrary(bookIds: List<Long>): Int = withContext(Dispatchers.IO) {
    val files = db.books().files(bookIds)
    if (files.isEmpty()) return@withContext 0
    settings.addRemovedBooks(files.map { it.path })
    covers.deleteAll(db.books().removeBooks(files.map { it.id }, System.currentTimeMillis()).staleCovers)
    indexer.sweep()
    files.size
  }

  /** Paths of the book files removed from the library, which scans skip. */
  val removedBooks: Flow<Set<String>> = settings.removedBooks

  /** Lets scans find the removed files [paths] again, and scans, so they come back with any history they kept. */
  suspend fun restoreRemoved(paths: Collection<String>): ScanResult {
    settings.restoreRemovedBooks(paths)
    return rescan()
  }

  /**
   * Deletes the books' files from the device, then forgets the books, reading history included. Only the book file goes:
   * a Calibre folder's metadata.opf and cover.jpg stay. A book whose file is already gone is forgotten as well.
   */
  suspend fun deleteFiles(bookIds: List<Long>): FileOpResult = withContext(Dispatchers.IO) {
    val files = db.books().files(bookIds)
    val deleted = files.filter { f ->
      val file = File(f.path)
      runCatching { !file.exists() || file.delete() }.getOrDefault(false).also { if (!it) Log.w(TAG, "cannot delete ${f.path}") }
    }
    if (deleted.isNotEmpty()) {
      covers.deleteAll(userCoversOf(deleted.map { it.id }))
      covers.deleteAll(db.books().forgetBooks(deleted.map { it.id }))
      settings.restoreRemovedBooks(deleted.map { it.path })
      indexer.sweep()
    }
    FileOpResult(deleted.size, files.size - deleted.size)
  }

  /**
   * Copies the books' files into the folder [dir], renaming a copy whose name is taken. A copy into a watched folder is
   * then scanned in.
   */
  suspend fun copyFiles(bookIds: List<Long>, dir: String): FileOpResult = withContext(Dispatchers.IO) {
    val target = File(dir)
    val files = db.books().files(bookIds)
    if (!target.isDirectory || !target.canWrite()) return@withContext FileOpResult(0, files.size)
    var copied = 0
    for (f in files) {
      var copy: File? = null
      runCatchingCancellable {
        val source = File(f.path)
        copy = uniqueFile(target, source.name)
        source.copyTo(copy!!)
        copied++
      }.onFailure { e -> copy?.delete(); Log.w(TAG, "cannot copy ${f.path} to $dir", e) }
    }
    val path = target.absolutePath
    if (copied > 0 && db.folders().watched().any { path == it.path || path.startsWith(it.path.trimEnd('/') + "/") }) rescan()
    FileOpResult(copied, files.size - copied)
  }

  /**
   * Moves the copied book [incoming] into the imported-books folder under [name] (sanitised; a generated name when it
   * has none or no book extension) and returns where it went.
   */
  private fun storeImported(incoming: File, name: String?): File {
    val dir = File(app.filesDir, "imported").apply { mkdirs() }
    // A picker may hand over a file without its name; a MOBI then still needs its extension to be found.
    val ext = if (MobiBook.isMobi(incoming)) "mobi" else "epub"
    val chosen = name?.takeIf { BookFormats.isBook(it) } ?: "import-${System.currentTimeMillis()}.$ext"
    val target = uniqueFile(dir, chosen.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
    if (!incoming.renameTo(target)) error("cannot store $chosen")
    return target
  }

  // ── the user's own title, authors, series and cover ──────────────────────

  /** The user's edits to one book, or null when there are none. */
  fun bookEdit(bookId: Long): Flow<BookEditEntity?> = db.edits().observe(bookId)
  suspend fun bookEditNow(bookId: Long): BookEditEntity? = db.edits().get(bookId)

  /** Sets (or with null, resets) one field of a book; throws [EditRejected] for a value that cannot be saved. */
  suspend fun setBookEdit(bookId: Long, field: EditField, value: String?) = withContext(Dispatchers.IO) {
    val current = db.edits().get(bookId) ?: BookEditEntity(bookId)
    db.edits().save(BookEdits.apply(current, field, value, System.currentTimeMillis()))
  }

  /**
   * Gives a book a cover from an image the user picked (cropped to 2:3), from [bytes]; null [bytes] goes back to the
   * book's own cover. The previous chosen cover's file is deleted. Returns false when the image could not be read.
   */
  suspend fun setBookCover(bookId: Long, bytes: ByteArray?): Boolean = withContext(Dispatchers.IO) {
    val current = db.edits().get(bookId) ?: BookEditEntity(bookId)
    val path = if (bytes == null) null else covers.saveUserCover(bytes, bookId, System.currentTimeMillis()) ?: return@withContext false
    db.edits().save(current.copy(coverPath = path, updatedAt = System.currentTimeMillis()))
    current.coverPath?.takeIf { it != path }?.let(covers::delete)
    true
  }

  /** Reads an image the user picked (photo picker) for [setBookCover]; null when it cannot be read or is too large. */
  suspend fun readPickedImage(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
      app.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > MAX_PICKED_IMAGE) return@use null }
        out.toByteArray()
      }
    }.getOrNull()
  }

  /** Resets every edit of a book (*Revert all*), its chosen cover included. */
  suspend fun revertBookEdits(bookId: Long) = withContext(Dispatchers.IO) {
    db.edits().get(bookId)?.coverPath?.let(covers::delete)
    db.edits().clear(bookId)
  }

  // ── books handed over by other apps ──────────────────────────────────────

  /**
   * Opens a book file another app handed over ("Open with", tapping a file): the library book it already is, or the
   * book it becomes once scanned in, restored or copied in (see [ExternalOpenRules]). Quire never opens a book that is
   * not in its library.
   */
  suspend fun openExternal(uri: Uri): ExternalOpenResult = withContext(Dispatchers.IO) {
    when (val r = addExternal(listOf(uri)).single()) {
      null -> ExternalOpenResult.Unreadable
      else -> ExternalOpenResult.Opened(r.first, r.second)
    }
  }

  /**
   * Brings the handed-over files [uris] into the library (the share sheet's "Add to Quire"), with the same matching as
   * [openExternal] so a book already there is never copied twice. Returns, per file, its book id and whether it was
   * copied in just now, or null when it is not a book Quire can read. Scans once, after every file is in place.
   */
  suspend fun addExternal(uris: List<Uri>): List<Pair<Long, Boolean>?> = withContext(Dispatchers.IO) {
    val roots = db.folders().watched().map { WatchedRoot(it.path, runCatching { File(it.path).canonicalPath }.getOrDefault(it.path)) }
    val removed = settings.removedBooks.first()
    val tmpDir = File(app.cacheDir, "external").apply { mkdirs() }
    // Per file: a book id now, or the path a scan will give a book (and whether it was copied in), or null.
    val pending = uris.map { uri ->
      runCatchingCancellable {
        val path = StoragePaths.pathOf(app, uri)?.let(::File)?.takeIf { it.isFile && it.canRead() }
        var temp: File? = null
        try {
          val source = path ?: File(tmpDir, ".open-${System.nanoTime()}.tmp").also { t ->
            temp = t
            app.contentResolver.openInputStream(uri)?.use { input -> t.outputStream().use { input.copyTo(it) } } ?: error("cannot open $uri")
          }
          if (!BookFormats.looksLikeBook(source)) return@runCatchingCancellable null
          val fingerprint = if (MobiBook.isMobi(source)) BookIdentity.fullFingerprint(source) else BookIdentity.fingerprint(source)
          val libraryPath = path?.let { ExternalOpenRules.libraryPath(it.canonicalPath, roots) }
          val plan = ExternalOpenRules.plan(
            libraryPath = libraryPath,
            byPath = libraryPath?.let { db.books().byPath(it) }?.takeIf { it.missingSince == null }?.id,
            byFingerprint = fingerprint?.let { db.books().presentIdByFingerprint(it) },
            removed = libraryPath != null && libraryPath in removed,
          )
          when (plan) {
            is OpenPlan.Open -> Pending(id = plan.bookId)
            is OpenPlan.Restore -> { settings.restoreRemovedBooks(listOf(plan.path)); Pending(path = plan.path) }
            is OpenPlan.ScanThenOpen -> Pending(path = plan.path)
            OpenPlan.Import -> {
              val incoming = temp ?: File(tmpDir, ".open-${System.nanoTime()}.tmp").also { t -> source.copyTo(t); temp = t }
              Pending(path = storeImported(incoming, path?.name ?: displayName(uri)).absolutePath, added = true)
            }
          }
        } finally {
          temp?.delete()
        }
      }.onFailure { Log.w(TAG, "cannot open handed-over $uri", it) }.getOrNull()
    }
    if (pending.any { it?.path != null }) {
      if (pending.any { it?.added == true }) ensureFolder(File(app.filesDir, "imported").absolutePath)
      rescan()
    }
    pending.map { p ->
      when {
        p == null -> null
        p.id != null -> p.id to false
        else -> db.books().byPath(p.path!!)?.takeIf { it.missingSince == null }?.let { it.id to p.added }
      }
    }
  }

  private class Pending(val id: Long? = null, val path: String? = null, val added: Boolean = false)

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
  suspend fun addReadingSession(session: ReadingSessionEntity) { db.stats().addSession(session) }
  val readingSessions: Flow<List<ReadingSessionEntity>> = db.stats().observeSessions()
  val finishedAt: Flow<List<Long>> = db.stats().observeFinished()

  suspend fun setHighlightColor(id: Long, color: Int) = db.annotations().setHighlightColor(id, color)
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

  /** Every tag in the library, each once, in name order; the hidden-tags picker chooses from these. */
  val tagNames: Flow<List<String>> = db.books().observeTagNames()
  val hiddenTags: Flow<Set<String>> = settings.hiddenTags
  val showWordCount: Flow<Boolean> = settings.showWordCount
  suspend fun setShowWordCount(v: Boolean) = settings.setShowWordCount(v)
  suspend fun setTagHidden(tag: String, hidden: Boolean) = settings.setTagHidden(tag, hidden)

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

  val lastHighlightColor: Flow<Int> = settings.lastHighlightColor
  suspend fun setLastHighlightColor(v: Int) = settings.setLastHighlightColor(v)

  val readAloudPrefs: Flow<ReadAloudPrefs> = settings.readAloudPrefs
  suspend fun setReadAloudPrefs(prefs: ReadAloudPrefs) = settings.setReadAloudPrefs(prefs)

  val readerControls: Flow<ReaderControls> = settings.readerControls
  suspend fun setReaderControls(controls: ReaderControls) = settings.setReaderControls(controls)

  val brightness: Flow<Int> = settings.brightness
  suspend fun setBrightness(v: Int) = settings.setBrightness(v)

  companion object {
    const val FINISHED_AT = 0.985f
    private const val TAG = "LibraryRepository"
  }
}

/** Images over this size are not taken as covers (a photo of a cover is a few MB at most). */
private const val MAX_PICKED_IMAGE = 30 * 1024 * 1024
