package com.quire.reader.ui

import android.net.Uri
import com.quire.reader.data.Book
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexActivity
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextSearchResult
import com.quire.reader.data.scan.ScanProgress
import com.quire.reader.data.scan.ScanResult
import com.quire.reader.ui.library.LibraryPrefs
import com.quire.reader.ui.library.LibraryStore
import com.quire.reader.ui.reader.ReaderStore
import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.index.BookTextPage
import com.quire.reader.data.index.IndexGap
import com.quire.reader.data.index.Snippet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Where a feature asked to go: a [ReaderRequest], a [Scope], or one of these. */
sealed interface Visit {
  data object Library : Visit
  data class Detail(val bookId: Long) : Visit
  data object Settings : Visit
}

/** Records where the feature asked to go. */
class RecordingNavigator : AppNavigator {
  val visits = mutableListOf<Any>()
  override fun openLibrary() { visits += Visit.Library }
  override fun openLibraryScope(scope: Scope) { visits += scope }
  override fun openDetail(bookId: Long) { visits += Visit.Detail(bookId) }
  override fun openReader(request: ReaderRequest) { visits += request }
  override fun openSettings() { visits += Visit.Settings }
}

/** Records every toast. */
class RecordingToasts : Toasts {
  val shown = mutableListOf<String>()
  override fun show(text: String) { shown += text }
}

class FakeIndexer : IndexerControl {
  override val activity = MutableStateFlow<IndexActivity>(IndexActivity.Idle)
  var requests = 0
  /** Every reader-busy change, in order. */
  val busy = mutableListOf<Boolean>()
  var rebuilt = 0
  var deleted = 0
  override fun request() { requests++ }
  override fun setReaderBusy(busy: Boolean) { this.busy += busy }
  override suspend fun rebuild() { rebuilt++ }
  override suspend fun deleteIndex() { deleted++ }
}

class FakeLibraryStore(books: List<Book> = emptyList()) : LibraryStore {
  override val books = MutableStateFlow(books)
  override val folders = MutableStateFlow<List<FolderEntity>>(emptyList())
  override val scan: StateFlow<ScanProgress> = MutableStateFlow(ScanProgress())
  override val indexCoverage: Flow<IndexCoverage> = emptyFlow()
  var current = true
  var scanResult = ScanResult(0, 0, 0, 0)
  var rescans = 0
  /** The filters of every text search run. */
  val searches = mutableListOf<TextSearchFilters>()
  override fun searchText(query: FtsQuery.Result.Query, filters: TextSearchFilters, order: SearchOrder): Flow<TextSearchResult> {
    searches += filters
    return flowOf(TextSearchResult(emptyList(), 0, capped = false))
  }
  override suspend fun isCurrent(target: IndexTarget) = current
  override suspend fun rescan(): ScanResult { rescans++; return scanResult }
  override suspend fun addFolder(path: String) = true
  override suspend fun removeFolder(id: Long) = 0
  override suspend fun importFiles(uris: List<Uri>) = uris.size
}

/** Saved library choices; [librarySort] can be held back to model a slow first read. */
class FakeLibraryPrefs(
  layout: String? = null,
  sort: String? = null,
  ascending: Boolean? = null,
  override val librarySort: Flow<String?> = MutableStateFlow(sort),
) : LibraryPrefs {
  override val libraryLayout = MutableStateFlow(layout)
  override val librarySortAscending = MutableStateFlow(ascending)
  override val textSearchOrder = MutableStateFlow(SearchOrder.Relevance)
  val savedSorts = mutableListOf<Pair<String, Boolean>>()
  override suspend fun setLibraryLayout(name: String) { libraryLayout.value = name }
  override suspend fun setLibrarySort(name: String, ascending: Boolean) { savedSorts += name to ascending }
  override suspend fun setTextSearchOrder(order: SearchOrder) { textSearchOrder.value = order }
}

fun testTarget(bookId: Long) = IndexTarget(bookId, indexedMtime = 1, indexedSizeBytes = 1, locatorJson = "{}", highlight = "word", progression = 0.5)

/** One book's reader data in memory; [paths] says which books are still in the library. */
class FakeReaderStore(var paths: Map<Long, String> = emptyMap()) : ReaderStore {
  /** Every write, in order, as a short description. */
  val writes = mutableListOf<String>()
  val prefs = MutableStateFlow(ReaderPrefs())
  val defaults = MutableStateFlow(ReaderPrefs())
  /** Book-search pages by the cursor they answer; a missing cursor fails the search. */
  val pages = mutableMapOf<Int, BookTextPage>()
  var current = true

  override suspend fun bookPath(bookId: Long) = paths[bookId]
  override suspend fun savedLocator(bookId: Long): String? = null
  override suspend fun markUnreadable(bookId: Long) { writes += "unreadable $bookId" }
  override suspend fun markOpened(bookId: Long) { writes += "opened $bookId" }
  override suspend fun updatePageCount(bookId: Long, pages: Int) { writes += "pages $bookId $pages" }
  override suspend fun savePosition(bookId: Long, locatorJson: String, progress: Float) { writes += "position $bookId" }
  override fun readerPrefs(bookId: Long): Flow<ReaderPrefs> = prefs
  override fun hasBookOverride(bookId: Long) = flowOf(false)
  override fun hasBookAdvancedOverride(bookId: Long) = flowOf(false)
  override val readerDefaults: Flow<ReaderPrefs> = defaults
  override val advancedReadingEnabled = flowOf(true)
  override suspend fun setBookPrefs(bookId: Long, prefs: ReaderPrefs) { writes += "prefs $bookId ${prefs.fontSize}" }
  override suspend fun setBookAdvancedPrefs(bookId: Long, advanced: AdvancedReaderPrefs) { writes += "advanced $bookId" }
  override suspend fun clearBookPrefs(bookId: Long) { writes += "clear prefs $bookId" }
  override suspend fun clearBookAdvancedPrefs(bookId: Long) { writes += "clear advanced $bookId" }
  override suspend fun useForAllBooks(bookId: Long, prefs: ReaderPrefs) { writes += "for all $bookId" }
  override fun bookmarks(bookId: Long) = flowOf(emptyList<BookmarkEntity>())
  override suspend fun addBookmark(bookmark: BookmarkEntity) { writes += "bookmark" }
  override suspend fun deleteBookmark(id: Long) { writes += "delete bookmark $id" }
  override fun highlights(bookId: Long) = flowOf(emptyList<HighlightEntity>())
  override suspend fun addHighlight(highlight: HighlightEntity): Long { writes += "highlight"; return 1 }
  override suspend fun deleteHighlight(id: Long) { writes += "delete highlight $id" }
  override suspend fun setHighlightNote(id: Long, note: String?) { writes += "note $id $note" }
  override val brightness = MutableStateFlow(100)
  override suspend fun setBrightness(value: Int) { writes += "brightness $value" }
  override suspend fun searchBookPage(bookId: Long, query: FtsQuery.Result.Query, afterSeq: Int) = pages[afterSeq] ?: error("no page after $afterSeq")
  override suspend fun isCurrent(target: IndexTarget) = current
}

fun testSnippet(bookId: Long, seq: Int) = Snippet(seq, "Chapter", emptyList(), testTarget(bookId))
fun testPage(bookId: Long, seqs: IntRange, next: Int?) = BookTextPage(seqs.map { testSnippet(bookId, it) }, next, IndexGap.None)
