package com.quire.reader.ui.reader

import android.util.Log
import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.ParagraphPreset
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.index.BookTextPage
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.reader.OpeningPosition
import com.quire.reader.reader.ReaderSession
import com.quire.reader.reader.STALE_TARGET_MESSAGE
import com.quire.reader.reader.SearchHit
import com.quire.reader.reader.SelectionAction
import com.quire.reader.reader.TargetOutcome
import com.quire.reader.reader.TocEntry
import com.quire.reader.reader.message
import com.quire.reader.reader.openingPosition
import com.quire.reader.ui.AppNavigator
import com.quire.reader.ui.BookSearchMode
import com.quire.reader.ui.BookSearchStatus
import com.quire.reader.ui.BookSearchUi
import com.quire.reader.ui.IndexerControl
import com.quire.reader.ui.LibraryData
import com.quire.reader.ui.ReaderRequest
import com.quire.reader.ui.Sheet
import com.quire.reader.ui.Toasts
import com.quire.reader.ui.TocTab
import com.quire.reader.ui.bookSearchFirstPage
import com.quire.reader.ui.planBookSearch
import com.quire.reader.ui.withPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import java.io.File

/** What the reader needs from the data layer (`LibraryRepository`), all for one book at a time. */
interface ReaderStore {
  /** The book's file, or null once it is no longer in the library. */
  suspend fun bookPath(bookId: Long): String?
  /** The saved reading position, as locator JSON. */
  suspend fun savedLocator(bookId: Long): String?
  suspend fun markUnreadable(bookId: Long)
  suspend fun markOpened(bookId: Long)
  suspend fun updatePageCount(bookId: Long, pages: Int)
  suspend fun savePosition(bookId: Long, locatorJson: String, progress: Float)

  fun readerPrefs(bookId: Long): Flow<ReaderPrefs>
  fun hasBookOverride(bookId: Long): Flow<Boolean>
  fun hasBookAdvancedOverride(bookId: Long): Flow<Boolean>
  val readerDefaults: Flow<ReaderPrefs>
  val advancedReadingEnabled: Flow<Boolean>
  suspend fun setBookPrefs(bookId: Long, prefs: ReaderPrefs)
  suspend fun setBookAdvancedPrefs(bookId: Long, advanced: AdvancedReaderPrefs)
  suspend fun clearBookPrefs(bookId: Long)
  suspend fun clearBookAdvancedPrefs(bookId: Long)
  suspend fun useForAllBooks(bookId: Long, prefs: ReaderPrefs)

  fun bookmarks(bookId: Long): Flow<List<BookmarkEntity>>
  suspend fun addBookmark(bookmark: BookmarkEntity)
  suspend fun deleteBookmark(id: Long)
  fun highlights(bookId: Long): Flow<List<HighlightEntity>>
  /** Returns the new highlight's id. */
  suspend fun addHighlight(highlight: HighlightEntity): Long
  suspend fun deleteHighlight(id: Long)
  suspend fun setHighlightNote(id: Long, note: String?)

  val brightness: Flow<Int>
  suspend fun setBrightness(value: Int)

  suspend fun searchBookPage(bookId: Long, query: FtsQuery.Result.Query, afterSeq: Int = -1): BookTextPage
  suspend fun isCurrent(target: IndexTarget): Boolean
}

private const val TAG = "ReaderOpen"
/** How long the opening log waits for the book to show before reporting that it did not. */
private const val SHOWN_LOG_TIMEOUT_MS = 30_000L
/** How long chapter anchors wait for the first page before being worked out anyway. */
private const val ANCHORS_WAIT_MS = 5_000L

sealed interface ReaderLoad {
  data object Idle : ReaderLoad
  data object Loading : ReaderLoad
  data class Failed(val message: String) : ReaderLoad
  data class Ready(val session: ReaderSession) : ReaderLoad
}

data class SearchUi(val query: String = "", val hits: List<SearchHit> = emptyList(), val running: Boolean = false)

/** The reader's overlays and transient controls; the reading settings themselves live in [ReaderPrefs]. */
data class ReaderUiState(
  val chrome: Boolean = false,
  val sheet: Sheet? = null,
  val tocTab: TocTab = TocTab.Contents,
  /** Whether the Display sheet shows the advanced controls expanded. Session memory; resets per book. */
  val advancedOpen: Boolean = false,
  val showZones: Boolean = false,
  val textSearchOpen: Boolean = false,
  val textQuery: String = "",
  /** Set while the search overlay is in library-search mode ("Show all in this book"); [textQuery] is then not used. */
  val bookSearch: BookSearchMode? = null,
  val brightness: Int = 100,
  /** Highlight whose actions (note, remove, copy) are showing after tapping it. */
  val activeHighlight: Long? = null,
  /** Highlight being given a note. */
  val noteFor: Long? = null,
)

/**
 * One opening of one book: loads it as [request] asks, then owns the [ReaderSession], everything collected for it
 * (settings, bookmarks, highlights, brightness, position saving), both searches, and the overlays. Made when the reader
 * is entered and [close]d when it is left; while it exists, background indexing steps aside.
 *
 * Work for the open book runs on [scope], which [close] cancels. Writes the user made run on [persist], which outlives
 * the reader so leaving right after one never loses it; the last position is saved on [appScope], which outlives even
 * the app's UI.
 */
class ReaderState(
  val request: ReaderRequest,
  private val store: ReaderStore,
  private val library: Flow<LibraryData>,
  private val openPublication: suspend (File) -> Result<Publication>,
  private val indexer: IndexerControl,
  private val nav: AppNavigator,
  private val toasts: Toasts,
  private val copyText: (String) -> Unit,
  private val scope: CoroutineScope,
  private val persist: CoroutineScope,
  private val appScope: CoroutineScope,
) {
  val bookId: Long get() = request.bookId

  private val _load = MutableStateFlow<ReaderLoad>(ReaderLoad.Loading)
  val load: StateFlow<ReaderLoad> = _load

  private val _ui = MutableStateFlow(ReaderUiState())
  val ui: StateFlow<ReaderUiState> = _ui

  private val _prefs = MutableStateFlow(ReaderPrefs())
  /** The book's reading settings (its own, or the defaults). */
  val prefs: StateFlow<ReaderPrefs> = _prefs

  private val _bookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())
  val bookmarks: StateFlow<List<BookmarkEntity>> = _bookmarks
  private val _highlights = MutableStateFlow<List<HighlightEntity>>(emptyList())
  val highlights: StateFlow<List<HighlightEntity>> = _highlights

  private val _search = MutableStateFlow(SearchUi())
  val search: StateFlow<SearchUi> = _search

  /** The overlay's library-search results while [ReaderUiState.bookSearch] is set. */
  private val _bookSearch = MutableStateFlow(BookSearchUi())
  val bookSearchUi: StateFlow<BookSearchUi> = _bookSearch

  /** Whether the book has its own reading settings instead of the defaults. */
  val hasBookOverride: StateFlow<Boolean> = store.hasBookOverride(bookId).stateIn(scope, SharingStarted.Eagerly, false)
  /** Whether the book carries an advanced object of its own (the book restore action). */
  val hasBookAdvancedOverride: StateFlow<Boolean> = store.hasBookAdvancedOverride(bookId).stateIn(scope, SharingStarted.Eagerly, false)
  /** Whether the advanced reading controls are shown at all; independent of their values. */
  val advancedReadingEnabled: StateFlow<Boolean> = store.advancedReadingEnabled.stateIn(scope, SharingStarted.Eagerly, false)
  private val defaults: StateFlow<ReaderPrefs> = store.readerDefaults.stateIn(scope, SharingStarted.Eagerly, ReaderPrefs())

  private var searchJob: Job? = null
  private var bookSearchJob: Job? = null
  private var targetJob: Job? = null
  private var holdingIndexer = false
  private var closed = false

  private fun edit(block: ReaderUiState.() -> ReaderUiState) = _ui.update(block)

  init {
    // Before anything is loaded, so background indexing steps aside while the book opens.
    holdingIndexer = true
    indexer.setReaderBusy(true)
    scope.launch { open() }
  }

  /**
   * Opens the book. Everything the first page needs besides the book itself (its reading settings, the brightness and
   * the saved position) is read while the publication is parsed, so the navigator is built once with the book's own
   * settings instead of being laid out with the defaults and then again when they arrive. Bookkeeping writes happen
   * after the book is shown.
   */
  private suspend fun open(): Unit = coroutineScope {
    val started = System.nanoTime()
    var unclaimed: Publication? = null
    try {
      val path = store.bookPath(bookId)
      if (path == null) { fail("This book is no longer in the library."); return@coroutineScope }
      val opening = openingPosition(request.restart, hasTarget = request.target != null)
      val prefs = async { store.readerPrefs(bookId).first() }
      val brightness = async { store.brightness.first() }
      val saved = async { if (opening == OpeningPosition.Saved) store.savedLocator(bookId) else null }
      val publication = openPublication(File(path)).getOrElse {
        coroutineContext.cancelChildren()
        store.markUnreadable(bookId)
        fail("This book can’t be opened. The file may be damaged or protected.")
        return@coroutineScope
      }
      unclaimed = publication
      val parsed = System.nanoTime()
      val positions = withContext(Dispatchers.IO) { runCatching { publication.positions() }.getOrDefault(emptyList()) }
      val positioned = System.nanoTime()
      val initial = when (opening) {
        OpeningPosition.Target -> request.target?.let { ReaderSession.targetLocator(it, positions) }
        OpeningPosition.Saved -> ReaderSession.parseLocator(saved.await())
        OpeningPosition.Start -> null
      }
      val book = library.first { it.loaded }.byId[bookId]
      if (book == null) { coroutineContext.cancelChildren(); fail("This book is no longer in the library."); return@coroutineScope }
      _prefs.value = prefs.await()
      val level = brightness.await()
      edit { copy(brightness = level) }
      val session = ReaderSession(book, publication, positions, initial)
      unclaimed = null
      _load.value = ReaderLoad.Ready(session)
      // Off the opening path: neither write changes what is shown.
      persist.launch {
        store.markOpened(bookId)
        store.updatePageCount(bookId, positions.size)
      }
      startJobs(session)
      val ready = System.nanoTime()
      scope.launch { logOpening(session, started, parsed, positioned, ready) }
      request.target?.let { target -> targetJob = scope.launch { reportOutcome(session.goToTarget(target)) } }
      request.libraryQuery?.let { enterBookSearch(it) }
    } finally {
      // Opened but never handed to a session: the load failed or the reader was left while it ran.
      unclaimed?.close()
    }
  }

  /**
   * Logs how long each part of opening took, once the navigator has shown the book: the file opened, its positions
   * counted, the session ready for the screen, and the first page shown. `adb logcat -s ReaderOpen`.
   */
  private suspend fun logOpening(session: ReaderSession, started: Long, parsed: Long, positioned: Long, ready: Long) {
    val shown = if (session.awaitShown(SHOWN_LOG_TIMEOUT_MS)) System.nanoTime() else null
    fun ms(from: Long, to: Long) = (to - from) / 1_000_000
    Log.i(TAG, "book $bookId: file opened in ${ms(started, parsed)} ms, positions +${ms(parsed, positioned)}, " +
      "session +${ms(positioned, ready)} (ready at ${ms(started, ready)} ms), " +
      (shown?.let { "first page shown +${ms(ready, it)} (at ${ms(started, it)} ms)" } ?: "not shown within ${SHOWN_LOG_TIMEOUT_MS / 1000} s"))
  }

  private fun fail(message: String) {
    _load.value = ReaderLoad.Failed(message)
    releaseIndexer()
  }

  private fun reportOutcome(outcome: TargetOutcome) { outcome.message()?.let(toasts::show) }

  @OptIn(FlowPreview::class)
  private fun startJobs(session: ReaderSession) {
    scope.launch { store.readerPrefs(bookId).collect { _prefs.value = it } }
    scope.launch { store.bookmarks(bookId).collect { _bookmarks.value = it } }
    scope.launch { store.highlights(bookId).collect { _highlights.value = it } }
    scope.launch { store.brightness.collect { b -> edit { copy(brightness = b) } } }
    // Save the position a moment after the reader stops moving.
    scope.launch { session.current.filterNotNull().drop(1).debounce(800).collect { savePosition(session, it) } }
    // Reads every chapter file with an anchored entry; it waits for the first page so it never competes with it.
    scope.launch { session.awaitShown(ANCHORS_WAIT_MS); runCatching { session.resolveChapterAnchors() } }
    // Keep highlight decorations in step with the database and with the navigator coming and going.
    scope.launch { _highlights.collect { list -> runCatching { session.applyHighlights(list) } } }
  }

  private suspend fun savePosition(session: ReaderSession, locator: Locator) {
    store.savePosition(bookId, locator.toJSON().toString(), (locator.locations.totalProgression ?: 0.0).toFloat())
  }

  /** Saves where the reader is now; the app calls it when it goes to the background. */
  suspend fun saveNow() {
    val session = session() ?: return
    session.current.value?.let { locator -> runCatching { savePosition(session, locator) } }
  }

  /** Leaves the reader for the library; leaving closes this state. */
  fun leave() = nav.openLibrary()

  /**
   * Tears the reader down: stops everything collected for the book, saves where it was, closes the session and lets
   * background indexing resume. Called once the reader is left; calling it again does nothing.
   */
  fun close() {
    if (closed) return
    closed = true
    scope.cancel()
    session()?.let { session ->
      val locator = session.current.value
      // Written on the app's scope so the last page turn is never lost.
      if (locator != null) appScope.launch { savePosition(session, locator) }
      session.close()
    }
    _load.value = ReaderLoad.Idle
    _search.value = SearchUi()
    _bookSearch.value = BookSearchUi()
    _highlights.value = emptyList(); _bookmarks.value = emptyList()
    releaseIndexer()
  }

  /** Lets background indexing resume now that this reader is no longer opening or open. */
  private fun releaseIndexer() {
    if (!holdingIndexer) return
    holdingIndexer = false
    indexer.setReaderBusy(false)
    indexer.request()
  }

  private fun session(): ReaderSession? = (_load.value as? ReaderLoad.Ready)?.session

  fun toast(text: String) = toasts.show(text)

  // ── overlays ─────────────────────────────────────────────────────────────

  fun setChrome(show: Boolean) = edit { copy(chrome = show) }
  fun openSheet(sheet: Sheet?, tab: TocTab? = null) = edit { copy(sheet = sheet, tocTab = tab ?: tocTab) }
  fun setTocTab(tab: TocTab) = edit { copy(tocTab = tab) }
  fun setAdvancedOpen(open: Boolean) = edit { copy(advancedOpen = open) }
  fun showZones(show: Boolean) = edit { copy(showZones = show, sheet = if (show) null else sheet, chrome = if (show) false else chrome) }
  fun closeReaderOverlays() = edit { copy(sheet = null, chrome = false, textSearchOpen = false, showZones = false, activeHighlight = null, noteFor = null) }

  // ── reading settings ─────────────────────────────────────────────────────

  /**
   * Applies a change to the book's basic settings: the accepted state is published
   * once, and a persistence echo equal to it is just an acknowledgment (the state flow
   * drops it); an echo that differs is the stored truth and wins.
   */
  fun updatePrefs(change: (ReaderPrefs) -> ReaderPrefs) {
    if (session() == null) return
    val next = change(_prefs.value)
    _prefs.value = next
    persist.launch { store.setBookPrefs(bookId, next) }
  }

  /** Applies a change to the book's advanced controls; its basic group is untouched. */
  fun updateBookAdvanced(change: (AdvancedReaderPrefs) -> AdvancedReaderPrefs) {
    if (session() == null) return
    val next = change(_prefs.value.advanced)
    _prefs.value = _prefs.value.copy(advanced = next)
    persist.launch { store.setBookAdvancedPrefs(bookId, next) }
  }

  /** Chooses a paragraph preset, setting indent and spacing in one reduction. */
  fun chooseParagraphPreset(preset: ParagraphPreset) {
    val levels = AdvancedReaderPrefs.levelsFor(preset) ?: return
    updateBookAdvanced { it.copy(paragraphIndent = levels.first, paragraphSpacing = levels.second) }
  }

  fun resetBookPrefs() {
    if (session() == null) return
    persist.launch { store.clearBookPrefs(bookId); toasts.show("Using your default settings") }
  }

  /** Restores this book's advanced controls to the globals; its basic override stays. */
  fun restoreBookAdvanced() {
    if (session() == null) return
    _prefs.value = _prefs.value.copy(advanced = defaults.value.advanced)
    persist.launch {
      store.clearBookAdvancedPrefs(bookId)
      toasts.show("This book's advanced settings follow your defaults")
    }
  }

  fun useForAllBooks() {
    if (session() == null) return
    val prefs = _prefs.value
    persist.launch { store.useForAllBooks(bookId, prefs); toasts.show("These settings are now the default for every book") }
  }

  fun setBrightness(v: Int) { edit { copy(brightness = v) }; persist.launch { store.setBrightness(v) } }

  // ── bookmarks ────────────────────────────────────────────────────────────

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
    val label = session.chapterTitle().ifEmpty { "Bookmark" }
    val progress = session.totalProgress
    persist.launch {
      if (existing.isNotEmpty()) existing.forEach { store.deleteBookmark(it.id) }
      else store.addBookmark(BookmarkEntity(bookId = bookId, locatorJson = locator.toJSON().toString(), label = label, progress = progress, createdAt = System.currentTimeMillis()))
    }
  }

  fun deleteBookmark(id: Long) = persist.launch { store.deleteBookmark(id) }

  // ── highlights and notes ─────────────────────────────────────────────────

  fun onSelectionAction(action: SelectionAction) {
    val session = session() ?: return
    scope.launch {
      val locator = session.currentSelection() ?: return@launch
      when (action) {
        SelectionAction.Copy -> {
          copyText(locator.text.highlight.orEmpty())
          session.clearSelection(); toasts.show("Copied")
        }
        SelectionAction.Highlight, SelectionAction.Note -> {
          // The chapter goes into the locator's display title: note exports can label this highlight
          // forever, even once the book is gone. Canonical keys ignore the title, so merging is unaffected.
          val stamped = locator.copy(title = session.chapterTitle(locator).ifEmpty { locator.title })
          val id = store.addHighlight(
            HighlightEntity(
              bookId = bookId, locatorJson = stamped.toJSON().toString(), text = locator.text.highlight.orEmpty(),
              progress = (locator.locations.totalProgression ?: 0.0).toFloat(), createdAt = System.currentTimeMillis(),
            ),
          )
          session.clearSelection()
          if (action == SelectionAction.Note) edit { copy(noteFor = id) } else toasts.show("Highlighted. Find it under Contents → Highlights")
        }
      }
    }
  }

  fun setActiveHighlight(id: Long?) = edit { copy(activeHighlight = id, chrome = if (id != null) false else chrome) }
  fun editNote(id: Long?) = edit { copy(noteFor = id, activeHighlight = null) }
  fun saveNote(id: Long, note: String) { persist.launch { store.setHighlightNote(id, note) }; edit { copy(noteFor = null) } }
  fun deleteHighlight(id: Long) { persist.launch { store.deleteHighlight(id) }; edit { copy(activeHighlight = null, noteFor = null) } }

  fun copyHighlight(id: Long) {
    val text = _highlights.value.firstOrNull { it.id == id }?.text ?: return
    copyText(text)
    edit { copy(activeHighlight = null) }; toasts.show("Copied")
  }

  // ── search inside the book ───────────────────────────────────────────────

  fun setTextSearch(open: Boolean) {
    edit { copy(textSearchOpen = open, chrome = false, bookSearch = if (open) bookSearch else null) }
    if (!open) {
      searchJob?.cancel(); bookSearchJob?.cancel()
      _search.value = SearchUi(); _bookSearch.value = BookSearchUi()
      scope.launch { session()?.applySearchHits(emptyList()) }
    }
  }

  fun setTextQuery(q: String) {
    edit { copy(textQuery = q) }
    searchJob?.cancel()
    val session = session() ?: return
    if (q.trim().length < 2) { _search.value = SearchUi(); return }
    searchJob = scope.launch {
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
    scope.launch { session.applySearchHits(_search.value.hits) }
  }

  // ── search inside the book, library-search mode ──────────────────────────

  private fun enterBookSearch(query: String) {
    edit { copy(textSearchOpen = true, chrome = false, bookSearch = BookSearchMode(bookId, query)) }
    runBookSearch(query)
  }

  /** Leaves library-search mode; the overlay stays open as the ordinary in-book search. */
  fun closeBookSearch() {
    bookSearchJob?.cancel()
    _bookSearch.value = BookSearchUi()
    edit { copy(bookSearch = null) }
    scope.launch { session()?.applySearchHits(emptyList()) }
  }

  /** Edits the library-search query. It keeps library semantics, and only this book, until the mode is closed. */
  fun setBookSearchQuery(q: String) {
    val mode = _ui.value.bookSearch ?: return
    edit { copy(bookSearch = mode.copy(query = q)) }
    runBookSearch(q)
  }

  private fun runBookSearch(text: String) {
    bookSearchJob?.cancel()
    val plan = planBookSearch(text)
    _bookSearch.value = plan.ui
    val query = plan.query ?: return
    bookSearchJob = scope.launch {
      delay(250) // wait for the user to pause typing
      val page = try { store.searchBookPage(bookId, query) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
      if (page == null) { _bookSearch.value = BookSearchUi(); toasts.show("Couldn’t search this book"); return@launch }
      _bookSearch.value = bookSearchFirstPage(page)
    }
  }

  /** Loads the next page of library-search matches; called as the list scrolls near its end. */
  fun loadMoreBookSearch() {
    val mode = _ui.value.bookSearch ?: return
    val current = _bookSearch.value
    val after = current.nextAfterSeq ?: return
    if (current.loadingMore || current.status != BookSearchStatus.Results) return
    val query = (FtsQuery.parse(mode.query) as? FtsQuery.Result.Query) ?: return
    _bookSearch.value = current.copy(loadingMore = true)
    // The same job as the first page, so a newer query cancels a page that is still loading.
    bookSearchJob = scope.launch {
      val page = try { store.searchBookPage(bookId, query, after) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
      _bookSearch.update { if (page == null) it.copy(loadingMore = false) else it.withPage(after, page) }
    }
  }

  /** Jumps to a library-search match: its own passage is underlined, and the overlay steps aside. */
  fun openBookSearchHit(target: IndexTarget) {
    val session = session() ?: return
    if (target.bookId != bookId) return
    targetJob?.cancel()
    targetJob = scope.launch {
      if (!store.isCurrent(target)) { toasts.show(STALE_TARGET_MESSAGE); indexer.request(); return@launch }
      edit { copy(textSearchOpen = false, chrome = false) }
      reportOutcome(session.goToTarget(target))
    }
  }

  fun goTo(entry: TocEntry) { session()?.go(entry.link); closeReaderOverlays() }
  fun goTo(locator: Locator) { session()?.go(locator); closeReaderOverlays() }
}
