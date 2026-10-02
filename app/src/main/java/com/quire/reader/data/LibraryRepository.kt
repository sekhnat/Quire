package com.quire.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.quire.reader.data.db.BookTagEntity
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.db.BookStateEntity
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.QuireDatabase
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
) {
  private val app = context.applicationContext

  val books: Flow<List<Book>> = db.books().observeAll()
    .map { rows -> val now = System.currentTimeMillis(); rows.map { it.toBook(now) } }
    .flowOn(Dispatchers.Default)
  val folders: Flow<List<FolderEntity>> = db.folders().observeAll()

  suspend fun rescan(): ScanResult = scanner.scan()

  /** Adds a folder to watch. Returns false if it can't be read or is already in the library. */
  suspend fun addFolder(path: String): Boolean = withContext(Dispatchers.IO) {
    val dir = File(path)
    if (!dir.isDirectory || !dir.canRead()) return@withContext false
    db.folders().insert(FolderEntity(path = dir.absolutePath)) != -1L
  }

  /** Forgets a folder and its books. The files on disk are not touched. */
  suspend fun removeFolder(id: Long) = withContext(Dispatchers.IO) {
    db.books().coverPathsIn(id).forEach { covers.delete(it) }
    db.folders().delete(id)
  }

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
      db.folders().insert(FolderEntity(path = dir.absolutePath))
      scanner.scan()
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
