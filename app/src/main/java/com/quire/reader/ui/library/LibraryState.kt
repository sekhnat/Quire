package com.quire.reader.ui.library

import android.net.Uri
import androidx.tracing.trace
import com.quire.reader.data.Book
import com.quire.reader.data.FileOpResult
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextSearchResult
import com.quire.reader.data.scan.ScanProgress
import com.quire.reader.data.scan.ScanResult
import com.quire.reader.data.scan.StoragePaths
import com.quire.reader.reader.STALE_TARGET_MESSAGE
import com.quire.reader.ui.AppNavigator
import com.quire.reader.ui.AuthorEntry
import com.quire.reader.ui.BulkSheet
import com.quire.reader.ui.BookQuery
import com.quire.reader.ui.IndexerControl
import com.quire.reader.ui.LibFilter
import com.quire.reader.ui.LibLayout
import com.quire.reader.ui.LibView
import com.quire.reader.ui.LibraryData
import com.quire.reader.ui.LibraryTextSearch
import com.quire.reader.ui.LibraryUiState
import com.quire.reader.ui.ReaderRequest
import com.quire.reader.ui.Scope
import com.quire.reader.ui.SearchScope
import com.quire.reader.ui.SeriesEntry
import com.quire.reader.ui.ShelfDef
import com.quire.reader.ui.SortKey
import com.quire.reader.ui.Toasts
import com.quire.reader.ui.bookQuery
import com.quire.reader.ui.books
import com.quire.reader.ui.describeImport
import com.quire.reader.ui.describe
import com.quire.reader.ui.describeFileOp
import com.quire.reader.ui.libLayoutOf
import com.quire.reader.ui.sortKeyOf
import com.quire.reader.ui.textSearchInput
import com.quire.reader.ui.textSearchStatus
import com.quire.reader.ui.visibleBooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** The Books view's list for [query], from the library snapshot [lib]. Compared by identity, like [Derived]. */
class VisibleBooks(val query: BookQuery, val lib: LibraryData, val books: List<Book>) {
  companion object { val None = VisibleBooks(BookQuery(), LibraryData.Empty, emptyList()) }
}

/**
 * A value worked out from one library snapshot. Compared by identity, so a state flow taking a new one never compares
 * two whole lists, on the main thread where it collects.
 */
class Derived<out T>(val value: T)

/** What the library needs from the data layer (`LibraryRepository`). */
interface LibraryStore {
  val books: Flow<List<Book>>
  val folders: Flow<List<FolderEntity>>
  val scan: StateFlow<ScanProgress>
  val indexCoverage: Flow<IndexCoverage>
  fun searchText(query: FtsQuery.Result.Query, filters: TextSearchFilters, order: SearchOrder): Flow<TextSearchResult>
  suspend fun isCurrent(target: IndexTarget): Boolean
  suspend fun rescan(): ScanResult
  suspend fun addFolder(path: String): Boolean
  /** Returns how many books kept their reading history. */
  suspend fun removeFolder(id: Long): Int
  /** Returns how many files were imported. */
  suspend fun importFiles(uris: List<Uri>): Int
  /** Adds the user tag [tag] to each book. */
  suspend fun addTag(bookIds: List<Long>, tag: String)
  /** Takes the books out of the library, keeping their files; returns how many were removed. */
  suspend fun removeFromLibrary(bookIds: List<Long>): Int
  /** Deletes the books' files from the device and forgets the books. */
  suspend fun deleteFiles(bookIds: List<Long>): FileOpResult
  /** Copies the books' files into the folder [dir]. */
  suspend fun copyFiles(bookIds: List<Long>, dir: String): FileOpResult
}

/** The library's saved view choices (`SettingsStore`). */
interface LibraryPrefs {
  val libraryLayout: Flow<String?>
  val librarySort: Flow<String?>
  val librarySortAscending: Flow<Boolean?>
  val textSearchOrder: Flow<SearchOrder>
  /** Tags whose books the library leaves out. */
  val hiddenTags: Flow<Set<String>>
  /** Whether books show their word count (and the library can sort by it). */
  val showWordCount: Flow<Boolean>
  suspend fun setLibraryLayout(name: String)
  suspend fun setLibrarySort(name: String, ascending: Boolean)
  suspend fun setTextSearchOrder(order: SearchOrder)
}

/**
 * The library screen's state and actions: browsing, sorting and filtering the books, the metadata and "Inside books"
 * searches, and the watched folders. Lives as long as the app's UI, so a filter or scope is still there on coming back.
 */
class LibraryState(
  private val store: LibraryStore,
  private val prefs: LibraryPrefs,
  private val indexer: IndexerControl,
  private val nav: AppNavigator,
  private val toasts: Toasts,
  private val hasFileAccess: () -> Boolean,
  private val scope: CoroutineScope,
  /** Where the book list and everything derived from it are worked out; never the main thread. */
  private val compute: CoroutineContext = Dispatchers.Default,
  /** Hands the books' files to another app (the system share sheet); false when none can take them. */
  private val shareFiles: (List<Book>) -> Boolean = { false },
) {
  private val _state = MutableStateFlow(LibraryUiState())
  val state: StateFlow<LibraryUiState> = _state

  val data: StateFlow<LibraryData> = combine(store.books, store.folders, prefs.hiddenTags, prefs.showWordCount) { books, folders, hidden, showWords ->
    trace("LibraryData") { LibraryData(books, folders, hidden, showWords = showWords) }
  }
    .flowOn(compute)
    .stateIn(scope, SharingStarted.Eagerly, LibraryData.Empty)

  /**
   * The Books view's list. It is worked out again only when the books or the [BookQuery] change, not for a new layout,
   * view or open sheet. Only runs while something observes it, and keeps the last list meanwhile, so coming back to
   * the library shows it at once.
   */
  val visible: StateFlow<VisibleBooks> = combine(data, _state.map { it.bookQuery }.distinctUntilChanged()) { lib, query ->
    VisibleBooks(query, lib, visibleBooks(query, lib))
  }
    .flowOn(compute)
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), VisibleBooks.None)

  /** The Authors view's groups; null until the library has loaded. */
  val authors: StateFlow<Derived<List<Pair<Char, List<AuthorEntry>>>>?> = derived { it.authorGroups }
  /** The Series view's series; null until the library has loaded. */
  val series: StateFlow<Derived<List<SeriesEntry>>?> = derived { it.series }
  /** The shelves layout's rows; null until the library has loaded. */
  val shelves: StateFlow<Derived<List<ShelfDef>>?> = derived { it.shelves }

  /** [pick] of each loaded library, worked out off the main thread and only while something observes it. */
  private fun <T> derived(pick: (LibraryData) -> T): StateFlow<Derived<T>?> = data.filter { it.loaded }
    .map { Derived(pick(it)) }
    .flowOn(compute)
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

  val scan: StateFlow<ScanProgress> = store.scan

  /**
   * The "Inside books" search: the status for the typed text and the active filters, with the index coverage and
   * indexer activity that explain partial or missing results. Only runs while something observes it.
   */
  val textSearch: StateFlow<LibraryTextSearch> = combine(
    textSearchStatus(combine(_state, prefs.hiddenTags, ::textSearchInput), search = store::searchText),
    store.indexCoverage,
    indexer.activity,
  ) { status, coverage, activity -> LibraryTextSearch(status, coverage, activity) }
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), LibraryTextSearch())

  /** Set once a sort is picked here; the saved sort, loaded at start, never replaces it. Main thread only. */
  private var sortChosen = false

  private fun edit(block: LibraryUiState.() -> LibraryUiState) = _state.update(block)

  init {
    scope.launch { prefs.textSearchOrder.collect { edit { copy(textSearchOrder = it) } } }
    // A selected book that leaves the library (deleted, removed, gone in a rescan) leaves the selection too.
    scope.launch {
      data.collect { lib ->
        if (lib.loaded) edit {
          val kept = selected.filterTo(HashSet()) { it in lib.byId }
          if (kept.size == selected.size) this else copy(selected = kept, bulkSheet = bulkSheet.takeIf { kept.isNotEmpty() })
        }
      }
    }
    // Only the first saved value is applied: later changes come from this screen, and a shelf's forced grid must not be undone by them.
    scope.launch { prefs.libraryLayout.first().let { saved -> edit { copy(layout = libLayoutOf(saved)) } } }
    // Sorting by words needs the word count shown; turning it off goes back to the default sort (the saved one stays).
    scope.launch { prefs.showWordCount.collect { show -> if (!show) edit { if (sort == SortKey.Words) copy(sort = SortKey.Opened) else this } } }
    scope.launch {
      val sort = sortKeyOf(prefs.librarySort.first()).let { if (it == SortKey.Words && !prefs.showWordCount.first()) SortKey.Opened else it }
      val ascending = prefs.librarySortAscending.first() ?: false
      // A sort picked while the saved one was loading is newer, and stays.
      if (!sortChosen) edit { copy(sort = sort, sortAscending = ascending) }
    }
  }

  // ── browsing ─────────────────────────────────────────────────────────────

  /** Selection belongs to the Books view, so another view ends it. */
  fun setView(v: LibView) = edit { copy(view = v, selected = if (v == LibView.Books) selected else emptySet(), bulkSheet = if (v == LibView.Books) bulkSheet else null) }
  fun setFilter(f: LibFilter) = edit { copy(filter = f) }
  fun setScope(scope: Scope?, view: LibView = LibView.Books) =
    edit { copy(scope = scope, view = view, query = if (scope != null) "" else query) }
  fun showFilter(f: LibFilter) = edit { copy(view = LibView.Books, filter = f, scope = null) }
  fun showShelf(filter: LibFilter? = null, scope: Scope? = null) = edit { copy(filter = filter ?: LibFilter.All, scope = scope, layout = LibLayout.Grid) }
  fun toggleSearch() = edit { copy(searchOpen = !searchOpen, query = "", textLibraryQuery = "") }
  fun setQuery(q: String) = edit { copy(query = q, view = LibView.Books) }
  fun setSearchScope(scope: SearchScope) = edit { copy(searchScope = scope) }
  fun setTextLibraryQuery(q: String) = edit { copy(textLibraryQuery = q, view = LibView.Books) }
  fun setTextSearchOrder(order: SearchOrder) {
    edit { copy(textSearchOrder = order) }
    scope.launch { prefs.setTextSearchOrder(order) }
  }
  fun openImport(open: Boolean) = edit { copy(importOpen = open) }
  fun openSort(open: Boolean) = edit { copy(sortOpen = open) }
  fun setSortAscending(asc: Boolean) { edit { copy(sortAscending = asc) }; saveSort() }
  fun flipSort() { edit { copy(sortAscending = !sortAscending) }; saveSort() }
  fun pickSort(k: SortKey) {
    edit { copy(sort = k, sortOpen = false, view = LibView.Books, layout = if (layout == LibLayout.Shelves) LibLayout.Grid else layout) }
    saveSort()
  }

  /** Remembers the sort and its direction, so the library opens the same way next time. */
  private fun saveSort() {
    val s = _state.value
    sortChosen = true
    scope.launch { prefs.setLibrarySort(s.sort.name, s.sortAscending) }
  }

  fun cycleLayout() {
    val next = when (_state.value.layout) {
      LibLayout.Grid -> LibLayout.List
      LibLayout.List -> LibLayout.Comfortable
      LibLayout.Comfortable -> LibLayout.Shelves
      LibLayout.Shelves -> LibLayout.Grid
    }
    edit { copy(layout = next, view = LibView.Books) }
    scope.launch { prefs.setLibraryLayout(next.name) }
    toasts.show(when (next) { LibLayout.Grid -> "Grid"; LibLayout.List -> "Dense list"; LibLayout.Comfortable -> "Comfortable list"; LibLayout.Shelves -> "Shelves" } + " layout")
  }

  // ── selecting books ──────────────────────────────────────────────────────

  /** A long press: picks the book, starting selection mode if it was not on. */
  fun startSelection(id: Long) = edit { copy(selected = selected + id) }

  /** A tap while selecting; unpicking the last book ends selection mode. */
  fun toggleSelected(id: Long) = edit {
    val next = if (id in selected) selected - id else selected + id
    copy(selected = next, bulkSheet = bulkSheet.takeIf { next.isNotEmpty() })
  }

  /** Picks every book the Books view shows now, or, when they all are already, none of them. */
  fun toggleSelectAll() = edit {
    val shown = visible.value.books.mapTo(HashSet()) { it.id }
    copy(selected = if (selected.containsAll(shown)) emptySet() else selected + shown, bulkSheet = null)
  }

  fun clearSelection() = edit { copy(selected = emptySet(), bulkSheet = null) }

  fun openBulk(sheet: BulkSheet?) = edit { copy(bulkSheet = sheet.takeIf { selecting }) }

  /** The selected books, in library order; those no longer in the library are left out. */
  fun selectedBooks(): List<Book> {
    val picked = _state.value.selected
    return data.value.all.filter { it.id in picked }
  }

  /** Ends selection mode and hands back what was selected, for an action to work on. */
  private fun takeSelection(): List<Long> {
    val ids = _state.value.selected.toList()
    edit { copy(selected = emptySet(), bulkSheet = null) }
    return ids
  }

  /** Sends the selected books' files to another app through the system share sheet. */
  fun sendSelected() {
    val chosen = selectedBooks()
    if (chosen.isEmpty()) return
    if (shareFiles(chosen)) clearSelection() else toasts.show("No app can receive these files")
  }

  fun tagSelected(tag: String) {
    val t = tag.trim()
    if (t.isEmpty() || !_state.value.selecting) return
    val ids = takeSelection()
    scope.launch {
      store.addTag(ids, t)
      toasts.show("Tagged ${books(ids.size)} “$t”")
    }
  }

  /** Copies the selected books' files into [dir], the folder picked for them; null when the picker was closed. */
  fun copySelected(dir: String?) {
    if (dir == null) { openBulk(null); return }
    if (!_state.value.selecting) return
    val ids = takeSelection()
    scope.launch {
      toasts.show("Copying ${books(ids.size)}…")
      report(store.copyFiles(ids, dir), "Copied", "copy", " to ${StoragePaths.displayName(dir)}")
    }
  }

  fun removeSelected() {
    if (!_state.value.selecting) return
    val ids = takeSelection()
    scope.launch {
      val n = store.removeFromLibrary(ids)
      toasts.show(if (n == 0) "Nothing to remove" else "Removed ${books(n)} from the library · bring ${if (n == 1) "it" else "them"} back in Settings")
    }
  }

  fun deleteSelected() {
    if (!_state.value.selecting) return
    val ids = takeSelection()
    scope.launch { report(store.deleteFiles(ids), "Deleted", "delete") }
  }

  /** A file action's toast; when files failed without all-files access, asks for it instead. */
  private fun report(result: FileOpResult, past: String, verb: String, tail: String = "") {
    toasts.show(if (result.failed > 0 && !hasFileAccess()) "Allow access to your files first" else describeFileOp(result, past, verb, tail))
  }

  // ── going elsewhere ──────────────────────────────────────────────────────

  fun openBook(id: Long) = nav.openDetail(id)
  fun read(id: Long) = nav.openReader(ReaderRequest(id))
  fun openSettings() = nav.openSettings()
  fun openStats() = nav.openStats()

  /**
   * Opens a library text-search result: the book at the matched passage, underlined, in place of its saved position
   * for this opening only. A result for a file that has changed since it was indexed is not opened; the book's index
   * is refreshed instead.
   */
  fun openTextHit(target: IndexTarget) {
    scope.launch {
      if (!store.isCurrent(target)) { toasts.show(STALE_TARGET_MESSAGE); indexer.request(); return@launch }
      nav.openReader(ReaderRequest(target.bookId, target = target))
    }
  }

  /** "Show all in this book": opens the book at its saved position with the search overlay in library-search mode for [query]. */
  fun openBookSearch(bookId: Long, query: String) = nav.openReader(ReaderRequest(bookId, libraryQuery = query))

  // ── folders and imports ──────────────────────────────────────────────────

  /** Rescans every watched folder and reports what changed. */
  fun rescan() {
    edit { copy(importOpen = false) }
    scope.launch {
      if (!hasFileAccess()) { toasts.show("Allow access to your files first"); return@launch }
      toasts.show("Scanning…")
      toasts.show(describe(store.rescan()))
    }
  }

  fun addFolder(path: String?) {
    scope.launch {
      if (path == null || !store.addFolder(path)) { toasts.show("Quire can't add that folder"); return@launch }
      edit { copy(importOpen = false) }
      toasts.show("Scanning…")
      toasts.show(describe(store.rescan()))
    }
  }

  fun removeFolder(id: Long) = scope.launch {
    val kept = store.removeFolder(id)
    toasts.show(if (kept == 0) "Folder removed from the library" else "Folder removed · ${books(kept)} with reading history kept under Missing books")
  }

  fun importFiles(uris: List<Uri>) {
    if (uris.isEmpty()) return
    edit { copy(importOpen = false) }
    scope.launch {
      val n = store.importFiles(uris)
      toasts.show(describeImport(n, uris.size))
    }
  }
}
