package com.quire.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quire.reader.QuireApplication
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.reader.ReaderSession
import com.quire.reader.reader.SearchHit
import com.quire.reader.reader.SelectionAction
import com.quire.reader.reader.TocEntry
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.getOrElse
import com.quire.reader.data.scan.FolderCandidate
import com.quire.reader.data.scan.FolderDiscovery
import com.quire.reader.data.scan.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private const val FOREGROUND_SCAN_GAP_MS = 2 * 60 * 1000L

/** Holds UI state and runs the library, onboarding and detail actions. */
class QuireViewModel(private val app: QuireApplication) : ViewModel() {
  private val repo = app.library
  private val settings = app.settings

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state

  val library: StateFlow<LibraryData> = combine(repo.books, repo.folders) { books, folders -> LibraryData(books, folders) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryData.Empty)

  val scan = repo.scanner.progress

  private var toastJob: Job? = null
  private var scanJob: Job? = null

  private fun edit(block: UiState.() -> UiState) = _state.update(block)

  init {
    viewModelScope.launch {
      val done = settings.onboardingDone.first()
      edit { copy(screen = if (done) Screen.Library else Screen.Onboard, hasAccess = StoragePaths.hasAllFilesAccess(), useCalibre = true) }
    }
  }

  fun toast(text: String) {
    toastJob?.cancel()
    edit { copy(toast = text) }
    toastJob = viewModelScope.launch { delay(2200); edit { copy(toast = null) } }
  }

  // ── onboarding ───────────────────────────────────────────────────────────

  fun refreshAccess() {
    val granted = StoragePaths.hasAllFilesAccess()
    edit { copy(hasAccess = granted) }
    // Coming back from the Settings screen with access granted moves on by itself.
    if (granted && _state.value.onboardStep == OnboardStep.Access) goToFolders()
  }

  fun setStep(step: OnboardStep) = edit { copy(onboardStep = step) }

  fun chooseFolders() {
    if (StoragePaths.hasAllFilesAccess()) goToFolders() else edit { copy(onboardStep = OnboardStep.Access, hasAccess = false) }
  }

  private fun goToFolders() {
    edit { copy(onboardStep = OnboardStep.Folders, hasAccess = true, discovering = true) }
    viewModelScope.launch(Dispatchers.IO) {
      val found = FolderDiscovery.discover()
      edit { copy(candidates = (found + candidates).distinctBy { it.path }, discovering = false, pickedFolders = pickedFolders + found.map { it.path }) }
    }
  }

  fun toggleFolder(path: String) = edit { copy(pickedFolders = if (path in pickedFolders) pickedFolders - path else pickedFolders + path) }
  fun toggleCalibre() = edit { copy(useCalibre = !useCalibre) }
  fun toggleWatch() = edit { copy(watchFolders = !watchFolders) }

  /** A folder chosen with the system picker, added to the candidate list and selected. */
  fun addPickedFolder(path: String?) {
    if (path == null || !StoragePaths.isUsableDirectory(path)) { toast("Quire can't read that folder"); return }
    viewModelScope.launch(Dispatchers.IO) {
      val count = FolderDiscovery.countEpubs(File(path))
      edit { copy(candidates = candidates.filter { it.path != path } + FolderCandidate(File(path).name, path, count), pickedFolders = pickedFolders + path) }
    }
  }

  fun startScan() {
    val s = _state.value
    edit { copy(onboardStep = OnboardStep.Scan) }
    scanJob?.cancel()
    scanJob = viewModelScope.launch {
      settings.setUseCalibre(s.useCalibre)
      settings.setWatchNewBooks(s.watchFolders)
      settings.setOnboardingDone(true)
      s.pickedFolders.forEach { repo.addFolder(it) }
      repo.rescan()
    }
  }

  fun openLibrary() = edit { copy(screen = Screen.Library) }

  // ── library ──────────────────────────────────────────────────────────────

  fun setView(v: LibView) = edit { copy(view = v) }
  fun setFilter(f: LibFilter) = edit { copy(filter = f) }
  fun setScope(scope: Scope?, view: LibView = LibView.Books, screen: Screen? = null) =
    edit { copy(scope = scope, view = view, query = if (scope != null) "" else query, screen = screen ?: this.screen) }
  fun showFilter(f: LibFilter) = edit { copy(view = LibView.Books, filter = f, scope = null) }
  fun showShelf(filter: LibFilter? = null, scope: Scope? = null) = edit { copy(filter = filter ?: LibFilter.All, scope = scope, layout = LibLayout.Grid) }
  fun toggleSearch() = edit { copy(searchOpen = !searchOpen, query = "") }
  fun setQuery(q: String) = edit { copy(query = q, view = LibView.Books) }
  fun openImport(open: Boolean) = edit { copy(importOpen = open) }
  fun openSort(open: Boolean) = edit { copy(sortOpen = open) }
  fun setSortAscending(asc: Boolean) = edit { copy(sortAscending = asc) }
  fun flipSort() = edit { copy(sortAscending = !sortAscending) }
  fun pickSort(k: SortKey) = edit { copy(sort = k, sortOpen = false, view = LibView.Books, layout = if (layout == LibLayout.Shelves) LibLayout.Grid else layout) }

  fun cycleLayout() {
    val next = when (_state.value.layout) { LibLayout.Grid -> LibLayout.List; LibLayout.List -> LibLayout.Shelves; LibLayout.Shelves -> LibLayout.Grid }
    edit { copy(layout = next, view = LibView.Books) }
    toast(when (next) { LibLayout.Grid -> "Grid"; LibLayout.List -> "Dense list"; LibLayout.Shelves -> "Shelves" } + " layout")
  }

  /** Rescans every watched folder and reports what changed. */
  fun rescan() {
    edit { copy(importOpen = false) }
    viewModelScope.launch {
      if (!StoragePaths.hasAllFilesAccess()) { toast("Allow access to your files first"); return@launch }
      toast("Scanning…")
      val r = repo.rescan()
      toast(describe(r.added, r.removed, r.updated))
    }
  }

  private fun describe(added: Int, removed: Int, updated: Int): String = when {
    added == 0 && removed == 0 && updated == 0 -> "Library is up to date"
    else -> listOfNotNull(
      if (added > 0) "$added new ${if (added == 1) "book" else "books"}" else null,
      if (updated > 0) "$updated updated" else null,
      if (removed > 0) "$removed removed" else null,
    ).joinToString(" · ")
  }

  private var lastForegroundScan = 0L

  /**
   * Called whenever the app comes to the foreground. Rescans (quietly, and at most every couple of minutes)
   * so books copied in while Quire was in the background show up; only speaks up if something changed.
   */
  fun onForeground() {
    val now = System.currentTimeMillis()
    if (now - lastForegroundScan < FOREGROUND_SCAN_GAP_MS) return
    lastForegroundScan = now
    viewModelScope.launch {
      if (!settings.onboardingDone.first() || !StoragePaths.hasAllFilesAccess() || !settings.watchNewBooks.first()) return@launch
      val r = repo.rescan()
      if (r.added > 0 || r.removed > 0) toast(describe(r.added, r.removed, r.updated))
    }
  }

  fun addFolder(path: String?) {
    viewModelScope.launch {
      if (path == null || !repo.addFolder(path)) { toast("Quire can't add that folder"); return@launch }
      edit { copy(importOpen = false) }
      toast("Scanning…")
      toast(repo.rescan().let { describe(it.added, it.removed, it.updated) })
    }
  }

  fun removeFolder(id: Long) = viewModelScope.launch { repo.removeFolder(id); toast("Folder removed from the library") }

  fun importFiles(uris: List<android.net.Uri>, fromOnboarding: Boolean = false) {
    if (uris.isEmpty()) return
    edit { copy(importOpen = false) }
    viewModelScope.launch {
      val n = repo.importFiles(uris)
      if (n > 0 && fromOnboarding) { settings.setOnboardingDone(true); edit { copy(screen = Screen.Library) } }
      toast(if (n > 0) "Imported $n ${if (n == 1) "book" else "books"}" else "Nothing could be imported")
    }
  }

  // ── detail ───────────────────────────────────────────────────────────────

  fun openBook(id: Long) = edit { copy(screen = Screen.Detail, bookId = id, editOpen = false) }
  fun goLibrary() = edit { copy(screen = Screen.Library, chrome = false) }
  fun openEdit(open: Boolean) = edit { copy(editOpen = open) }
  fun setFinished(id: Long, finished: Boolean) = viewModelScope.launch { repo.setFinished(id, finished); toast(if (finished) "Marked as finished" else "Marked as unread") }
  fun setRating(id: Long, rating: Int?) = viewModelScope.launch { repo.setUserRating(id, rating) }
  fun addTag(id: Long, tag: String) = viewModelScope.launch { repo.addTag(id, tag) }
  fun removeTag(id: Long, tag: String) = viewModelScope.launch { repo.removeTag(id, tag) }

  // ── reader ───────────────────────────────────────────────────────────────

  private val _reader = MutableStateFlow<ReaderLoad>(ReaderLoad.Idle)
  val reader: StateFlow<ReaderLoad> = _reader

  private val _prefs = MutableStateFlow(ReaderPrefs())
  /** The open book's reading settings (its own, or the defaults). */
  val prefs: StateFlow<ReaderPrefs> = _prefs

  private val _bookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())
  val bookmarks: StateFlow<List<BookmarkEntity>> = _bookmarks
  private val _highlights = MutableStateFlow<List<HighlightEntity>>(emptyList())
  val highlights: StateFlow<List<HighlightEntity>> = _highlights

  private val _search = MutableStateFlow(SearchUi())
  val search: StateFlow<SearchUi> = _search

  private var readerJobs: Job? = null
  private var searchJob: Job? = null

  /** Opens a book in the reader. [restart] ignores the saved position (the "Read again" button). */
  fun read(id: Long, restart: Boolean = false) {
    closeReaderSession()
    edit { copy(screen = Screen.Reader, bookId = id, chrome = false, sheet = null, textSearchOpen = false, textQuery = "", activeHighlight = null, noteFor = null, showZones = false) }
    _reader.value = ReaderLoad.Loading
    viewModelScope.launch {
      val book = repo.book(id)
      if (book == null) { _reader.value = ReaderLoad.Failed("This book is no longer in the library."); return@launch }
      val opened = app.publicationLoader.open(File(book.path))
      val publication = opened.getOrElse {
        repo.markUnreadable(id)
        _reader.value = ReaderLoad.Failed("This book can’t be opened. The file may be damaged or protected.")
        return@launch
      }
      val positions = withContext(Dispatchers.IO) { runCatching { publication.positions() }.getOrDefault(emptyList()) }
      val saved = repo.readingState(id)
      val initial = if (restart) null else ReaderSession.parseLocator(saved?.locatorJson)
      repo.markOpened(id)
      repo.updatePageCount(id, positions.size)
      val libraryBook = library.value.byId[id]
      if (libraryBook == null) { publication.close(); _reader.value = ReaderLoad.Failed("This book is no longer in the library."); return@launch }
      val session = ReaderSession(libraryBook, publication, positions, initial)
      _reader.value = ReaderLoad.Ready(session)
      edit { copy(brightness = 100) }
      startReaderJobs(session)
    }
  }

  @OptIn(FlowPreview::class)
  private fun startReaderJobs(session: ReaderSession) {
    val id = session.book.id
    readerJobs = viewModelScope.launch {
      launch { repo.readerPrefs(id).collect { _prefs.value = it } }
      launch { repo.bookmarks(id).collect { _bookmarks.value = it } }
      launch { repo.highlights(id).collect { _highlights.value = it } }
      launch { repo.brightness.collect { b -> edit { copy(brightness = b) } } }
      // Save the position a moment after the reader stops moving.
      launch {
        session.current.filterNotNull().drop(1).debounce(800).collect { savePosition(session, it) }
      }
      launch { runCatching { session.resolveChapterAnchors() } }
      // Keep highlight decorations in step with the database and with the navigator coming and going.
      launch {
        _highlights.collect { list -> runCatching { session.applyHighlights(list) } }
      }
    }
  }

  private suspend fun savePosition(session: ReaderSession, locator: Locator) {
    repo.savePosition(session.book.id, locator.toJSON().toString(), (locator.locations.totalProgression ?: 0.0).toFloat())
  }

  /** Leaves the reader, saving where it was. */
  fun closeReader() {
    closeReaderSession()
    edit { copy(screen = Screen.Library, chrome = false, sheet = null, textSearchOpen = false, activeHighlight = null, noteFor = null) }
  }

  private fun closeReaderSession() {
    readerJobs?.cancel(); searchJob?.cancel()
    (_reader.value as? ReaderLoad.Ready)?.session?.let { session ->
      val locator = session.current.value
      // Written outside viewModelScope's cancellation so the last page turn is never lost.
      if (locator != null) app.appScope.launch { savePosition(session, locator) }
      session.close()
    }
    _reader.value = ReaderLoad.Idle
    _search.value = SearchUi()
    _highlights.value = emptyList(); _bookmarks.value = emptyList()
  }

  override fun onCleared() { closeReaderSession(); super.onCleared() }

  private fun session(): ReaderSession? = (_reader.value as? ReaderLoad.Ready)?.session

  fun setChrome(show: Boolean) = edit { copy(chrome = show) }
  fun openSheet(sheet: Sheet?, tab: TocTab? = null) = edit { copy(sheet = sheet, tocTab = tab ?: tocTab) }
  fun setTocTab(tab: TocTab) = edit { copy(tocTab = tab) }
  fun showZones(show: Boolean) = edit { copy(showZones = show, sheet = if (show) null else sheet, chrome = if (show) false else chrome) }
  fun closeReaderOverlays() = edit { copy(sheet = null, chrome = false, textSearchOpen = false, showZones = false, activeHighlight = null, noteFor = null) }

  // reading settings

  fun updatePrefs(change: (ReaderPrefs) -> ReaderPrefs) {
    val id = session()?.book?.id ?: return
    val next = change(_prefs.value)
    _prefs.value = next
    viewModelScope.launch { repo.setBookPrefs(id, next) }
  }

  fun useForAllBooks() {
    val id = session()?.book?.id ?: return
    viewModelScope.launch { repo.useForAllBooks(id, _prefs.value); toast("These settings are now the default for every book") }
  }

  fun setBrightness(v: Int) { edit { copy(brightness = v) }; viewModelScope.launch { repo.setBrightness(v) } }

  // bookmarks

  /** Whether the page showing now is bookmarked: a bookmark within half a position of the reader's place. */
  fun isBookmarked(session: ReaderSession, marks: List<BookmarkEntity>): Boolean {
    val here = session.totalProgress
    val tolerance = 0.5f / session.positions.size.coerceAtLeast(1)
    return marks.any { kotlin.math.abs(it.progress - here) <= tolerance }
  }

  fun toggleBookmark() {
    val session = session() ?: return
    val locator = session.current.value ?: return
    val existing = _bookmarks.value.filter { kotlin.math.abs(it.progress - session.totalProgress) <= 0.5f / session.positions.size.coerceAtLeast(1) }
    viewModelScope.launch {
      if (existing.isNotEmpty()) existing.forEach { repo.deleteBookmark(it.id) }
      else repo.addBookmark(BookmarkEntity(bookId = session.book.id, locatorJson = locator.toJSON().toString(), label = session.chapterTitle().ifEmpty { "Bookmark" }, progress = session.totalProgress, createdAt = System.currentTimeMillis()))
    }
  }

  fun deleteBookmark(id: Long) = viewModelScope.launch { repo.deleteBookmark(id) }

  // highlights and notes

  fun onSelectionAction(action: SelectionAction) {
    val session = session() ?: return
    viewModelScope.launch {
      val locator = session.currentSelection() ?: return@launch
      when (action) {
        SelectionAction.Copy -> {
          val text = locator.text.highlight.orEmpty()
          val cm = app.getSystemService(android.content.ClipboardManager::class.java)
          cm.setPrimaryClip(android.content.ClipData.newPlainText("Quire", text))
          session.clearSelection(); toast("Copied")
        }
        SelectionAction.Highlight, SelectionAction.Note -> {
          val id = repo.addHighlight(
            HighlightEntity(
              bookId = session.book.id, locatorJson = locator.toJSON().toString(), text = locator.text.highlight.orEmpty(),
              progress = (locator.locations.totalProgression ?: 0.0).toFloat(), createdAt = System.currentTimeMillis(),
            ),
          )
          session.clearSelection()
          if (action == SelectionAction.Note) edit { copy(noteFor = id) } else toast("Highlighted. Find it under Contents → Highlights")
        }
      }
    }
  }

  fun setActiveHighlight(id: Long?) = edit { copy(activeHighlight = id, chrome = if (id != null) false else chrome) }
  fun editNote(id: Long?) = edit { copy(noteFor = id, activeHighlight = null) }
  fun saveNote(id: Long, note: String) { viewModelScope.launch { repo.setHighlightNote(id, note) }; edit { copy(noteFor = null) } }
  fun deleteHighlight(id: Long) { viewModelScope.launch { repo.deleteHighlight(id) }; edit { copy(activeHighlight = null, noteFor = null) } }

  fun copyHighlight(id: Long) {
    val text = _highlights.value.firstOrNull { it.id == id }?.text ?: return
    app.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Quire", text))
    edit { copy(activeHighlight = null) }; toast("Copied")
  }

  // search inside the book

  fun setTextSearch(open: Boolean) {
    edit { copy(textSearchOpen = open, chrome = false) }
    if (!open) { searchJob?.cancel(); _search.value = SearchUi(); viewModelScope.launch { session()?.applySearchHits(emptyList()) } }
  }

  fun setTextQuery(q: String) {
    edit { copy(textQuery = q) }
    searchJob?.cancel()
    val session = session() ?: return
    if (q.trim().length < 2) { _search.value = SearchUi(); return }
    searchJob = viewModelScope.launch {
      delay(250) // wait for the user to pause typing
      _search.value = SearchUi(query = q.trim(), running = true)
      runCatching { session.search(q.trim()) { hits -> _search.value = SearchUi(q.trim(), hits, running = true) } }
      _search.value = _search.value.copy(running = false)
    }
  }

  /** Jumps to a search result and underlines the matches. */
  fun openSearchHit(hit: SearchHit) {
    val session = session() ?: return
    edit { copy(textSearchOpen = false, chrome = false) }
    session.go(hit.locator)
    viewModelScope.launch { session.applySearchHits(_search.value.hits) }
  }

  fun goTo(entry: TocEntry) { session()?.go(entry.link); closeReaderOverlays() }
  fun goTo(locator: Locator) { session()?.go(locator); closeReaderOverlays() }
}

sealed interface ReaderLoad {
  data object Idle : ReaderLoad
  data object Loading : ReaderLoad
  data class Failed(val message: String) : ReaderLoad
  data class Ready(val session: ReaderSession) : ReaderLoad
}

data class SearchUi(val query: String = "", val hits: List<SearchHit> = emptyList(), val running: Boolean = false)
