package com.quire.reader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import com.quire.reader.QuireApplication
import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexActivity
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.scan.StoragePaths
import com.quire.reader.ui.detail.BookEditor
import com.quire.reader.ui.detail.DetailState
import com.quire.reader.ui.library.LibraryPrefs
import com.quire.reader.ui.library.LibraryState
import com.quire.reader.ui.library.LibraryStore
import com.quire.reader.ui.reader.ReaderState
import com.quire.reader.ui.reader.ReaderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Builds the feature state holders from the app's singletons. The holders only see the narrow ports they declare;
 * the adapters here delegate to the repositories, which stay as they are.
 */
class Features(private val app: QuireApplication) {
  private val repo get() = app.library
  private val settings get() = app.settings

  val indexer: IndexerControl = object : IndexerControl {
    override val activity: StateFlow<IndexActivity> get() = app.indexer.activity
    override fun request() = app.indexer.request()
    override fun setReaderBusy(busy: Boolean) = app.indexer.setReaderBusy(busy)
    override suspend fun rebuild() = app.indexer.rebuild()
    override suspend fun deleteIndex() = app.indexer.deleteIndex()
  }

  private val libraryStore = object : LibraryStore {
    override val books get() = repo.books
    override val folders get() = repo.folders
    override val scan get() = repo.scanner.progress
    override val indexCoverage get() = repo.indexCoverage
    override fun searchText(query: FtsQuery.Result.Query, filters: TextSearchFilters, order: SearchOrder) = repo.searchText(query, filters, order)
    override suspend fun isCurrent(target: IndexTarget) = repo.isCurrent(target)
    override suspend fun rescan() = repo.rescan()
    override suspend fun addFolder(path: String) = repo.addFolder(path)
    override suspend fun removeFolder(id: Long) = repo.removeFolder(id)
    override suspend fun importFiles(uris: List<Uri>) = repo.importFiles(uris)
  }

  private val libraryPrefs = object : LibraryPrefs {
    override val libraryLayout get() = settings.libraryLayout
    override val librarySort get() = settings.librarySort
    override val librarySortAscending get() = settings.librarySortAscending
    override val textSearchOrder get() = settings.textSearchOrder
    override suspend fun setLibraryLayout(name: String) { settings.setLibraryLayout(name) }
    override suspend fun setLibrarySort(name: String, ascending: Boolean) { settings.setLibrarySort(name, ascending) }
    override suspend fun setTextSearchOrder(order: SearchOrder) { settings.setTextSearchOrder(order) }
  }

  private val bookEditor = object : BookEditor {
    override suspend fun setFinished(bookId: Long, finished: Boolean) = repo.setFinished(bookId, finished)
    override suspend fun setUserRating(bookId: Long, rating: Int?) = repo.setUserRating(bookId, rating)
    override suspend fun addTag(bookId: Long, tag: String) = repo.addTag(bookId, tag)
    override suspend fun removeTag(bookId: Long, tag: String) = repo.removeTag(bookId, tag)
  }

  fun detail(bookId: Long, notes: NotesExport, nav: AppNavigator, toasts: Toasts, persist: CoroutineScope) =
    DetailState(bookId, bookEditor, notes, nav, toasts, persist)

  private val readerStore = object : ReaderStore {
    override suspend fun bookPath(bookId: Long) = repo.book(bookId)?.path
    override suspend fun savedLocator(bookId: Long) = repo.readingState(bookId)?.locatorJson
    override suspend fun markUnreadable(bookId: Long) = repo.markUnreadable(bookId)
    override suspend fun markOpened(bookId: Long) = repo.markOpened(bookId)
    override suspend fun updatePageCount(bookId: Long, pages: Int) = repo.updatePageCount(bookId, pages)
    override suspend fun savePosition(bookId: Long, locatorJson: String, progress: Float) = repo.savePosition(bookId, locatorJson, progress)
    override fun readerPrefs(bookId: Long) = repo.readerPrefs(bookId)
    override fun hasBookOverride(bookId: Long) = repo.hasBookOverride(bookId)
    override fun hasBookAdvancedOverride(bookId: Long) = repo.hasBookAdvancedOverride(bookId)
    override val readerDefaults get() = repo.readerDefaults
    override val advancedReadingEnabled get() = repo.advancedReadingEnabled
    override suspend fun setBookPrefs(bookId: Long, prefs: ReaderPrefs) = repo.setBookPrefs(bookId, prefs)
    override suspend fun setBookAdvancedPrefs(bookId: Long, advanced: AdvancedReaderPrefs) = repo.setBookAdvancedPrefs(bookId, advanced)
    override suspend fun clearBookPrefs(bookId: Long) = repo.clearBookPrefs(bookId)
    override suspend fun clearBookAdvancedPrefs(bookId: Long) = repo.clearBookAdvancedPrefs(bookId)
    override suspend fun useForAllBooks(bookId: Long, prefs: ReaderPrefs) = repo.useForAllBooks(bookId, prefs)
    override fun bookmarks(bookId: Long) = repo.bookmarks(bookId)
    override suspend fun addBookmark(bookmark: BookmarkEntity) { repo.addBookmark(bookmark) }
    override suspend fun deleteBookmark(id: Long) = repo.deleteBookmark(id)
    override fun highlights(bookId: Long) = repo.highlights(bookId)
    override suspend fun addHighlight(highlight: HighlightEntity) = repo.addHighlight(highlight)
    override suspend fun deleteHighlight(id: Long) = repo.deleteHighlight(id)
    override suspend fun setHighlightNote(id: Long, note: String?) = repo.setHighlightNote(id, note)
    override val brightness get() = repo.brightness
    override suspend fun setBrightness(value: Int) { repo.setBrightness(value) }
    override suspend fun searchBookPage(bookId: Long, query: FtsQuery.Result.Query, afterSeq: Int) = repo.searchBookPage(bookId, query, afterSeq)
    override suspend fun isCurrent(target: IndexTarget) = repo.isCurrent(target)
  }

  private fun copyToClipboard(text: String) =
    app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Quire", text))

  /** A reader for [request]; it starts opening the book at once. */
  fun reader(request: ReaderRequest, library: StateFlow<LibraryData>, nav: AppNavigator, toasts: Toasts, scope: CoroutineScope, persist: CoroutineScope) =
    ReaderState(request, readerStore, library, app.publicationLoader::open, indexer, nav, toasts, ::copyToClipboard, scope, persist, app.appScope)

  fun library(nav: AppNavigator, toasts: Toasts, scope: CoroutineScope) =
    LibraryState(libraryStore, libraryPrefs, indexer, nav, toasts, StoragePaths::hasAllFilesAccess, scope)
}
