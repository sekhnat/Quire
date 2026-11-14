package com.quire.reader.ui.reader

import android.util.Log
import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.ParagraphPreset
import com.quire.reader.data.ReadAloudPrefs
import com.quire.reader.data.ReaderControls
import com.quire.reader.data.AutoScrollPrefs
import com.quire.reader.data.ReadMode
import com.quire.reader.data.TypographySource
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.index.BookTextPage
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.reader.OpeningPosition
import com.quire.reader.reader.ReadAloudOutcome
import com.quire.reader.reader.ReadAloudPlayback
import com.quire.reader.reader.ReadAloudStatus
import com.quire.reader.reader.ReadAloudSupport
import com.quire.reader.reader.ReadAloudUi
import com.quire.reader.reader.VoiceOption
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
import com.quire.reader.ui.wordsLabel
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.quire.reader.reader.UserFontRegistry
import com.quire.reader.data.dictionary.WordLookup
import com.quire.qdict.LookupResult
import com.quire.qdict.Selections
import com.quire.reader.navigator.epub.ReaderMedia
import org.readium.r2.shared.publication.Link
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
  suspend fun addReadingSession(bookId: Long, span: ReadingSpan)

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
  suspend fun setHighlightColor(id: Long, color: Int)
  /** The colour a new highlight gets: the one last picked. */
  val lastHighlightColor: Flow<Int>
  suspend fun setLastHighlightColor(color: Int)

  val readerControls: Flow<ReaderControls>
  suspend fun setReaderControls(controls: ReaderControls)
  val readAloudPrefs: Flow<ReadAloudPrefs>
  suspend fun setReadAloudPrefs(prefs: ReadAloudPrefs)
  val brightness: Flow<Int>
  suspend fun setBrightness(value: Int)

  suspend fun searchBookPage(bookId: Long, query: FtsQuery.Result.Query, afterSeq: Int = -1): BookTextPage
  suspend fun isCurrent(target: IndexTarget): Boolean
}

private const val TAG = "ReaderOpen"
/** How long the opening log waits for the book to show before reporting that it did not. */
private const val SHOWN_LOG_TIMEOUT_MS = 30_000L
/** The page follows the sentence being read aloud at most this often. */
private const val FOLLOW_INTERVAL_MS = 1_000L
/** With the screen off the place being read aloud is saved at most this often. */
private const val BACKGROUND_SAVE_INTERVAL_MS = 5_000L
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
  /** Colour the Highlights tab is narrowed to, as a `HighlightColor` index; null shows every highlight. */
  val highlightColorFilter: Int? = null,
  /** Auto-scroll or auto page turn, and what is pausing it. */
  val auto: AutoScrollState = AutoScrollState(),
  /** The definition card, open while a word chosen by a long press is selected. */
  val definition: DefinitionUi? = null,
  /** An image, SVG or table open full screen. */
  val media: ReaderMedia? = null,
  /** A passage waiting in the Share quote sheet. */
  val quote: QuoteDraft? = null,
  /** Where the last link inside the book was followed from, offered back as a chip. */
  val returnTo: ReturnPoint<Locator>? = null,
) {
  /** Whether anything is drawn over the page; the volume keys stay with the system then. */
  val overlaysOpen: Boolean get() = chrome || sheet != null || textSearchOpen || showZones || activeHighlight != null || noteFor != null || quote != null || media != null || definition != null
}

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
  private val shareText: (String) -> Unit,
  /** Hands text to an installed dictionary or translator; null when none is installed. */
  private val lookUp: ((String) -> Unit)?,
  private val scope: CoroutineScope,
  private val persist: CoroutineScope,
  private val appScope: CoroutineScope,
  private val now: () -> Long = System::currentTimeMillis,
  /** What reading aloud needs; null where it is not offered. */
  private val readAloudSupport: ReadAloudSupport? = null,
  userFonts: UserFontsPort = NoUserFonts,
  /** The bundled dictionary; null where the definition card is not offered. */
  private val wordLookup: WordLookup? = null,
) {
  /** The user's fonts, for the font picker. */
  val fonts = FontChoices(userFonts, scope, persist, toasts)
  val bookId: Long get() = request.bookId

  /** What the text-selection menu offers; "Look up" only when some app can take the text. */
  val selectionActions: List<SelectionAction> = SelectionAction.entries.filter {
    (it != SelectionAction.LookUp || lookUp != null) && (it != SelectionAction.ReadAloud || readAloudSupport != null)
  }

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

  /** How the reader behaves around the page (volume keys, screen-on, orientation); app-wide. */
  val controls: StateFlow<ReaderControls> = store.readerControls.stateIn(scope, SharingStarted.Eagerly, ReaderControls())

  private val lastHighlightColor: StateFlow<Int> = store.lastHighlightColor.stateIn(scope, SharingStarted.Eagerly, 0)

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
  /** The book's word count for the top bar ("92k words"); null while word counts are off or the book is not counted yet. */
  val wordCount: StateFlow<String?> = library.map { lib -> lib.byId[bookId]?.let { wordsLabel(it, lib.showWords) } }
    .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

  private var searchJob: Job? = null
  private var bookSearchJob: Job? = null
  private var targetJob: Job? = null
  private var holdingIndexer = false
  private var closed = false
  /** Measures the reading time of this opening; made once the book's pages are counted. */
  private var clock: ReadingClock? = null
  /** Whether the reader is the screen being looked at; read aloud keeps it counting as reading when it is not. */
  private var inFront = false

  private val _readAloud = MutableStateFlow(ReadAloudUi())
  /** Whether the book is being read aloud, and how fast. */
  val readAloud: StateFlow<ReadAloudUi> = _readAloud
  private var playback: ReadAloudPlayback? = null
  private var readAloudJob: Job? = null
  private var lastBackgroundSaveAt = 0L
  private val readAloudPrefs: StateFlow<ReadAloudPrefs> = store.readAloudPrefs.stateIn(scope, SharingStarted.Eagerly, ReadAloudPrefs())

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
      clock = ReadingClock(now, positions.size)
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
    scope.launch { session.current.filterNotNull().collect { clock?.moved((it.locations.totalProgression ?: 0.0).toFloat()) } }
    // A font added or removed while reading reaches the chapters already loaded (later ones are served with it).
    scope.launch {
      fonts.fonts.drop(1).collect { list ->
        UserFontRegistry.fonts = list
        session.navigator?.refreshUserFonts()
      }
    }
    // The return chip goes once the reader has moved on from where the link led.
    scope.launch {
      session.current.filterNotNull().collect { here ->
        val position = here.locations.position ?: return@collect
        edit { returnTo?.let { point -> copy(returnTo = point.movedTo(position)) } ?: this }
      }
    }
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

  /** The reader came to the front: reading time counts from now. */
  fun resumeReading() {
    inFront = true
    val session = session() ?: return
    clock?.resume(session.totalProgress)
  }

  /** The reader left the front (the app went to the background, or the reader was left): the session ends and is kept. */
  fun pauseReading() {
    inFront = false
    // Being read to counts as reading, so while it speaks the clock runs on with the screen off.
    if (_readAloud.value.status == ReadAloudStatus.Playing) return
    endReadingSession()
  }

  private fun endReadingSession() {
    clock?.pause()?.let { span -> persist.launch { store.addReadingSession(bookId, span) } }
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
    // Nothing of the dictionary stays in memory once the book is closed.
    runCatching { wordLookup?.close() }
    stopReadAloud(clearSentence = false)
    // Normally the screen already paused the clock; this catches a reader closed without being seen to leave.
    clock?.pause()?.let { span -> appScope.launch { store.addReadingSession(bookId, span) } }
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

  // ── read aloud ───────────────────────────────────────────────────────────

  /**
   * Starts reading the book aloud from [from], or from where the reader is. While it is already going, a [from] restarts it
   * there, and no [from] just plays or pauses it.
   */
  fun startReadAloud(from: Locator? = null) {
    if (_ui.value.auto.on) stopAutoScroll()
    val session = session() ?: return
    val support = readAloudSupport ?: run { toasts.show("Read aloud isn’t available"); return }
    if (_readAloud.value.active) {
      if (from == null) { toggleReadAloud(); return }
      stopReadAloud()
    }
    _readAloud.value = ReadAloudUi(ReadAloudStatus.Starting, readAloudPrefs.value.speed)
    readAloudJob = scope.launch {
      val start = from ?: session.speechStart(selection = false) ?: session.current.value
      val opened = support.opener.open(session.publication, start, store.readAloudPrefs.first(), scope, ::stopReadAloud)
      val reading = opened.getOrElse { error ->
        toasts.show(error.message ?: "Read aloud couldn’t start")
        _readAloud.value = ReadAloudUi()
        return@launch
      }
      if (closed) { reading.close(); return@launch }
      playback = reading
      coroutineScope {
        launch { reading.playing.collect { playing -> onReadAloudPlaying(playing) } }
        launch { reading.outcome.filterNotNull().collect { outcome -> onReadAloudOutcome(outcome) } }
        launch { followReadAloud(session, reading) }
        reading.play()
      }
    }
  }

  private fun onReadAloudPlaying(playing: Boolean) {
    _readAloud.update { it.copy(status = if (playing) ReadAloudStatus.Playing else ReadAloudStatus.Paused) }
    if (!playing && !inFront) endReadingSession()
  }

  private fun onReadAloudOutcome(outcome: ReadAloudOutcome) {
    toasts.show(when (outcome) { ReadAloudOutcome.Ended -> "Finished reading aloud"; is ReadAloudOutcome.Failed -> "Read aloud stopped: ${outcome.message}" })
    stopReadAloud()
  }

  /**
   * Keeps the page, the marked sentence, the reading clock and the saved place in step with the sentence being spoken.
   * The page follows at most once a second, so a quick run of short sentences does not make it jump.
   */
  private suspend fun followReadAloud(session: ReaderSession, reading: ReadAloudPlayback) = coroutineScope {
    val follow = MutableSharedFlow<Locator>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    launch {
      follow.collect { place ->
        session.go(place)
        saveSpokenPlace(session, place)
        delay(FOLLOW_INTERVAL_MS)
      }
    }
    reading.utterance.filterNotNull().collect { spoken ->
      runCatching { session.applyReadAloud(spoken.utterance) }
      clock?.tick()
      session.progressOf(spoken.follow)?.let { progress -> clock?.moved(progress) }
      follow.tryEmit(spoken.follow)
    }
  }

  /** With the screen off nothing turns the page, so the place being read is saved here, not at most every few seconds. */
  private suspend fun saveSpokenPlace(session: ReaderSession, place: Locator) {
    if (inFront || now() - lastBackgroundSaveAt < BACKGROUND_SAVE_INTERVAL_MS) return
    val progress = session.progressOf(place) ?: return
    lastBackgroundSaveAt = now()
    store.savePosition(bookId, place.toJSON().toString(), progress)
  }

  fun toggleReadAloud() {
    val reading = playback ?: return
    if (reading.playing.value) reading.pause() else reading.play()
  }

  fun readAloudNext() { playback?.next() }
  fun readAloudPrevious() { playback?.previous() }

  /** Stops reading aloud, from the controls, the notification, or the book ending. [clearSentence] is false when the reader is going away. */
  fun stopReadAloud(clearSentence: Boolean = true) {
    readAloudJob?.cancel()
    readAloudJob = null
    val reading = playback
    playback = null
    reading?.close()
    if (_readAloud.value.active && !inFront) endReadingSession()
    _readAloud.value = ReadAloudUi()
    if (clearSentence) scope.launch { session()?.applyReadAloud(null) }
  }

  /** Steps the speed up, wrapping round, and remembers it for the next book. */
  fun cycleReadAloudSpeed() {
    val speed = ReadAloudPrefs.nextSpeed(_readAloud.value.speed)
    playback?.setSpeed(speed)
    _readAloud.update { it.copy(speed = speed) }
    persist.launch { store.setReadAloudPrefs(store.readAloudPrefs.first().copy(speed = speed)) }
  }

  /** The voices to pick from, for the language being read; empty when nothing is playing. */
  fun readAloudVoices(): List<VoiceOption> = playback?.let { reading -> reading.voices().filter { it.language.substringBefore('-') == reading.language.substringBefore('-') } }.orEmpty()

  /** The id of the voice picked for the language being read, if any was. */
  fun readAloudVoiceId(): String? = playback?.let { readAloudPrefs.value.voices[it.language] }

  fun chooseReadAloudVoice(voice: VoiceOption) {
    playback?.setVoice(voice.language, voice.id)
    persist.launch { store.setReadAloudPrefs(store.readAloudPrefs.first().withVoice(voice.language, voice.id)) }
  }

  /** Asks the system to install more voices. */
  fun installVoices() { readAloudSupport?.installVoices?.invoke() }

  // ── overlays ─────────────────────────────────────────────────────────────

  fun setChrome(show: Boolean) = edit { copy(chrome = show) }
  fun openSheet(sheet: Sheet?, tab: TocTab? = null) = edit { copy(sheet = sheet, tocTab = tab ?: tocTab) }
  fun setTocTab(tab: TocTab) = edit { copy(tocTab = tab) }
  fun setAdvancedOpen(open: Boolean) = edit { copy(advancedOpen = open) }
  fun showZones(show: Boolean) = edit { copy(showZones = show, sheet = if (show) null else sheet, chrome = if (show) false else chrome) }
  /**
   * A link inside the book was tapped; returns whether the navigator should follow it. A link to the book's own
   * navigation document, which is not in the reading order and so cannot be shown, opens the Contents sheet instead.
   * Any followed link remembers where it was tapped, for the return chip.
   */
  fun onInternalLink(link: Link): Boolean {
    val session = session() ?: return true
    val target = link.url().removeFragment()
    if (session.publication.readingOrder.none { it.url().removeFragment() == target }) {
      if (isNavigationDocument(session.publication, link)) openSheet(Sheet.Contents, TocTab.Contents)
      return false
    }
    val from = session.current.value
    val page = from?.locations?.position
    if (from != null && page != null) edit { copy(returnTo = ReturnPoint(from, page)) }
    return true
  }

  /** Goes back to where the last followed link was tapped. */
  fun returnFromLink() {
    val point = _ui.value.returnTo ?: return
    edit { copy(returnTo = null) }
    session()?.go(point.locator)
  }

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

  /** Changes the app-wide reading controls (status line, warm light) from inside the reader. */
  fun updateControls(change: (ReaderControls) -> ReaderControls) {
    val next = change(controls.value)
    persist.launch { store.setReaderControls(next) }
  }

  /** Flips the warm light's switch: on or off, or, under a schedule, against it until its next change. */
  fun toggleWarmLight() {
    val nowMs = now()
    updateControls { it.copy(warmLight = it.warmLight.toggled(nowMs, minuteOfDay(nowMs))) }
  }

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
      if (action == SelectionAction.ReadAloud) {
        val start = session.speechStart(selection = true) ?: locator
        session.clearSelection()
        startReadAloud(start)
        return@launch
      }
      val text = locator.text.highlight.orEmpty()
      if (!onSelectedText(session, action, text)) saveHighlight(session, action, locator, text)
    }
  }

  /** The actions that only use the selected text; false for the ones that keep the selection in the book. */
  internal fun onSelectedText(session: ReaderSession, action: SelectionAction, text: String): Boolean {
    when (action) {
      SelectionAction.Copy -> { copyText(text); toasts.show("Copied") }
      SelectionAction.Share -> shareQuoteOf(session, text)
      SelectionAction.LookUp -> lookUp?.invoke(text)
      SelectionAction.Highlight, SelectionAction.Note, SelectionAction.ReadAloud -> return false
    }
    session.clearSelection()
    return true
  }

  private suspend fun saveHighlight(session: ReaderSession, action: SelectionAction, locator: Locator, text: String) {
    // The chapter goes into the locator's display title: note exports can label this highlight
    // forever, even once the book is gone. Canonical keys ignore the title, so merging is unaffected.
    val stamped = locator.copy(title = session.chapterTitle(locator).ifEmpty { locator.title })
    val id = store.addHighlight(
      HighlightEntity(
        bookId = bookId, locatorJson = stamped.toJSON().toString(), text = text,
        progress = (locator.locations.totalProgression ?: 0.0).toFloat(), createdAt = System.currentTimeMillis(),
        color = lastHighlightColor.value,
      ),
    )
    session.clearSelection()
    if (action == SelectionAction.Note) edit { copy(noteFor = id) } else toasts.show("Highlighted. Find it under Contents → Highlights")
  }

  /** Recolours a highlight, and makes that colour the one new highlights get. */
  fun setHighlightColor(id: Long, color: Int) {
    persist.launch { store.setHighlightColor(id, color); store.setLastHighlightColor(color) }
  }

  fun setHighlightColorFilter(color: Int?) = edit { copy(highlightColorFilter = color) }

  fun setActiveHighlight(id: Long?) = edit { copy(activeHighlight = id, chrome = if (id != null) false else chrome) }
  fun editNote(id: Long?) = edit { copy(noteFor = id, activeHighlight = null) }
  fun saveNote(id: Long, note: String) { persist.launch { store.setHighlightNote(id, note) }; edit { copy(noteFor = null) } }
  fun deleteHighlight(id: Long) { persist.launch { store.deleteHighlight(id) }; edit { copy(activeHighlight = null, noteFor = null) } }

  fun shareHighlight(id: Long) {
    val text = _highlights.value.firstOrNull { it.id == id }?.text ?: return
    val book = (session()?.book) ?: return
    edit { copy(activeHighlight = null) }
    shareQuoteOf(session() ?: return, text)
  }

  /** Opens the Share quote sheet for [text]; a passage too long for a card is shared as text straight away. */
  private fun shareQuoteOf(session: ReaderSession, text: String) {
    val book = session.book
    if (text.trim().length > QuoteCardSpec.MAX_IMAGE_CHARS) return shareText(shareQuote(text, book.title, book.author))
    val prefs = _prefs.value
    val font = if (prefs.advanced.typographySource == TypographySource.Book) null else prefs.font
    edit { copy(quote = QuoteDraft(text, book.title, book.author, prefs.theme, font)) }
  }

  fun closeQuote() = edit { copy(quote = null) }

  // ── auto-scroll and auto page turn ───────────────────────────────────────

  private var autoJob: Job? = null

  /** Starts or stops auto-scroll (scroll mode) or auto page turn (paged mode). Read aloud and auto-scroll exclude each other. */
  fun toggleAutoScroll() {
    if (_ui.value.auto.on) return stopAutoScroll()
    if (readAloud.value.active) stopReadAloud()
    edit { copy(auto = AutoScrollState(on = true), chrome = false) }
    restartAuto()
  }

  fun stopAutoScroll() {
    edit { copy(auto = AutoScrollState()) }
    restartAuto()
  }

  fun autoFaster() = changeAutoSpeed(AutoScrollSpeed::faster)
  fun autoSlower() = changeAutoSpeed(AutoScrollSpeed::slower)

  /** A new speed applies at once (the stored controls catch up a moment later) and is remembered for this mode. */
  private fun changeAutoSpeed(step: (AutoScrollPrefs, Boolean) -> AutoScrollPrefs) {
    val next = step(controls.value.autoScroll, _prefs.value.mode == ReadMode.Paged)
    updateControls { it.copy(autoScroll = next) }
    restartAuto(next)
  }

  /** The reading mode, text size or spacing changed: auto-scroll goes on in the new layout at the same reading pace. */
  fun refreshAuto() { if (_ui.value.auto.on) restartAuto() }

  /** Something paused or released auto-scroll. A finger lifting resumes it a second later, unless another touch comes first. */
  fun pauseAuto(why: AutoPause, paused: Boolean) {
    val before = _ui.value.auto
    if (!before.on) return
    if (why == AutoPause.Touch && !paused) {
      touchResume?.cancel()
      touchResume = scope.launch {
        delay(AutoScrollState.RESUME_AFTER_TOUCH_MS)
        edit { copy(auto = auto.paused(AutoPause.Touch, false)) }
        restartAuto()
      }
      return
    }
    if (why == AutoPause.Touch) touchResume?.cancel()
    val after = before.paused(why, paused)
    if (after == before) return
    edit { copy(auto = after) }
    restartAuto()
  }

  private var touchResume: Job? = null

  /** Applies the current state: scrolls (or times page turns) while running, stands still otherwise. */
  private fun restartAuto(speeds: AutoScrollPrefs = controls.value.autoScroll) {
    autoJob?.cancel()
    val session = session() ?: return
    val state = _ui.value.auto
    val prefs = _prefs.value
    if (!state.running) { session.navigator?.setAutoScroll(null); return }
    if (prefs.mode == ReadMode.Scroll) {
      session.navigator?.setAutoScroll(AutoScrollSpeed.pxPerSecond(speeds.linesPerMinute, prefs.fontSize, prefs.lineHeight))
      // Stops at the end of the book.
      autoJob = scope.launch {
        session.current.filterNotNull().collect { if ((it.locations.totalProgression ?: 0.0) >= 0.999) endOfBook() }
      }
    } else {
      // Each page shows for the set time; any turn (the timer's own, a tap, a volume key) starts the count again.
      autoJob = scope.launch {
        session.current.collectLatest {
          delay(speeds.secondsPerPage * 1000L)
          if (!session.goForward()) endOfBook()
        }
      }
    }
  }

  private fun endOfBook() {
    stopAutoScroll()
    toasts.show("End of book")
  }

  // ── the definition card ──────────────────────────────────────────────────

  private var definitionJob: Job? = null

  /**
   * A long press made a selection (the selection menu just opened). When looking up on long press is on and the book is
   * in English (or does not say), the selection's word or short phrase is looked up and the card opens; it then follows
   * the selection as its handles are dragged, and closes when the selection goes.
   */
  fun onSelectionStarted() {
    val lookup = wordLookup ?: return
    val session = session() ?: return
    if (!controls.value.lookUpOnLongPress || !Selections.isEnglish(session.book.language)) return
    definitionJob?.cancel()
    definitionJob = scope.launch {
      // The selection settles a moment after the menu opens.
      delay(120)
      var shown: String? = null
      var candidate: String? = null
      var since = 0L
      while (true) {
        val text = session.selectedText() ?: break
        if (text != shown) {
          if (text != candidate) { candidate = text; since = now() }
          // The first selection is looked up at once; a handle being dragged, once the selection holds still.
          if (shown == null || now() - since >= SELECTION_SETTLE_MS) {
            showDefinition(lookup, text)
            shown = text
          }
        }
        delay(SELECTION_POLL_MS)
      }
      edit { copy(definition = null) }
    }
  }

  private suspend fun showDefinition(lookup: WordLookup, text: String) {
    val query = Selections.normalise(text)
    if (query == null) {
      edit { copy(definition = null) }
      return
    }
    edit { copy(definition = DefinitionUi(query, null)) }
    val result = lookup.lookUp(query)
    edit { definition?.takeIf { it.query == query }?.let { copy(definition = it.copy(result = result)) } ?: this }
  }

  /** The selection menu closed: the selection is gone, and so is the card. */
  fun onSelectionEnded() {
    definitionJob?.cancel()
    definitionJob = null
    edit { copy(definition = null) }
  }

  fun closeDefinition() {
    onSelectionEnded()
    session()?.clearSelection()
  }

  fun expandDefinition() = edit { definition?.let { copy(definition = it.copy(expanded = true)) } ?: this }

  /** Looks a synonym up inside the card; Back returns to the entry it came from. */
  fun lookUpInCard(word: String) {
    val lookup = wordLookup ?: return
    val current = _ui.value.definition ?: return
    if (current.result == null) return
    scope.launch {
      val result = lookup.lookUp(word)
      edit { definition?.let { copy(definition = it.copy(result = result, history = it.history + listOfNotNull(it.result), expanded = false)) } ?: this }
    }
  }

  fun definitionBack() = edit {
    val d = definition ?: return@edit this
    val previous = d.history.lastOrNull() ?: return@edit copy(definition = null)
    copy(definition = d.copy(result = previous, history = d.history.dropLast(1)))
  }

  /** Hands the looked-up text to another dictionary app (the card's *Open in app*); null when none is installed. */
  val openInApp: ((String) -> Unit)? get() = lookUp

  /** Opens an image, SVG or table full screen; the page underneath stays exactly where it is. */
  fun openMedia(media: ReaderMedia) = edit { copy(media = media, chrome = false, activeHighlight = null) }
  fun closeMedia() = edit { copy(media = null) }

  /** An image file's bytes, read from the book. */
  suspend fun mediaBytes(image: ReaderMedia.Image): ByteArray? = withContext(Dispatchers.IO) {
    val publication = session()?.publication ?: return@withContext null
    runCatching {
      val resource = publication.get(image.link) ?: return@runCatching null
      try { resource.read().getOrNull() } finally { resource.close() }
    }.getOrNull()
  }

  /** Shares the plain-text quote (the sheet's Share text). */
  fun shareQuoteText(text: String) = shareText(text)

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

/** Whether [link] is the book's navigation document (EPUB 3 nav or EPUB 2 NCX). */
internal fun isNavigationDocument(publication: Publication, link: Link): Boolean {
  if ("contents" in link.rels) return true
  if (link.mediaType?.toString()?.startsWith("application/x-dtbncx+xml") == true) return true
  val target = link.url().removeFragment()
  return publication.resources.any { ("contents" in it.rels) && it.url().removeFragment() == target }
}

/** Minutes after local midnight at [epochMs]. */
internal fun minuteOfDay(epochMs: Long): Int {
  val c = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
  return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
}

/** How often the card looks at the selection while it is open, and how still it must hold before a new lookup. */
private const val SELECTION_POLL_MS = 150L
private const val SELECTION_SETTLE_MS = 300L

/**
 * The definition card. [query] is the selection as looked up, [result] null while looking. [history] holds the entries
 * synonyms were followed from.
 */
data class DefinitionUi(
  val query: String,
  val result: LookupResult?,
  val history: List<LookupResult> = emptyList(),
  val expanded: Boolean = false,
)
