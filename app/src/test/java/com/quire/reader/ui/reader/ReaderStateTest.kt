package com.quire.reader.ui.reader

import com.quire.reader.reader.STALE_TARGET_MESSAGE
import com.quire.reader.ui.BookSearchStatus
import com.quire.reader.ui.FakeIndexer
import com.quire.reader.ui.FakeReaderStore
import com.quire.reader.ui.LibraryData
import com.quire.reader.ui.ReaderRequest
import com.quire.reader.ui.RecordingNavigator
import com.quire.reader.ui.RecordingToasts
import com.quire.reader.ui.Sheet
import com.quire.reader.ui.Visit
import com.quire.reader.ui.childScope
import com.quire.reader.ui.testBook
import com.quire.reader.ui.testPage
import com.quire.reader.ui.testTarget
import com.quire.reader.data.ReaderControls
import com.quire.reader.data.ReaderOrientation
import com.quire.reader.data.ReaderPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.quire.reader.data.ReadAloudPrefs
import com.quire.reader.reader.ReadAloudOutcome
import com.quire.reader.reader.ReadAloudPlayback
import com.quire.reader.reader.ReadAloudPosition
import com.quire.reader.reader.ReadAloudStatus
import com.quire.reader.reader.ReadAloudSupport
import com.quire.reader.reader.ReadAloudUnavailable
import com.quire.reader.reader.ReaderSession
import com.quire.reader.reader.VoiceOption
import com.quire.reader.reader.SelectionAction
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import java.io.File
import java.io.IOException

/** The reader built from fakes: no app, database, indexer or WebView, and a stub publication for the opened path. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderStateTest {
  private val dispatcher = StandardTestDispatcher()
  private val store = FakeReaderStore(paths = mapOf(1L to "/books/1.epub"))
  private val indexer = FakeIndexer()
  private val nav = RecordingNavigator()
  private val toasts = RecordingToasts()
  private val library = flowOf(LibraryData(listOf(testBook(1, "Emma")), emptyList()))
  private val opened = mutableListOf<File>()
  private val shared = mutableListOf<String>()
  private val lookedUp = mutableListOf<String>()

  @Before fun setUp() = Dispatchers.setMain(dispatcher)
  @After fun tearDown() = Dispatchers.resetMain()

  private fun stubPublication() = Publication(Manifest(metadata = Metadata(identifier = "t", localizedTitle = LocalizedString("Emma"))))

  private fun TestScope.reader(
    request: ReaderRequest = ReaderRequest(1),
    open: suspend (File) -> Result<Publication> = { opened += it; Result.success(stubPublication()) },
    lookUp: ((String) -> Unit)? = { lookedUp += it },
    readAloud: ReadAloudSupport? = support,
  ) = ReaderState(request, store, library, open, indexer, nav, toasts, {}, { shared += it }, lookUp, backgroundScope.childScope(), backgroundScope, backgroundScope, { time }, readAloud)

  /** A playback that records what it is asked, and says what it is told to. */
  private class FakePlayback(override val language: String = "en-US") : ReadAloudPlayback {
    override val playing = MutableStateFlow(false)
    override val utterance = MutableStateFlow<ReadAloudPosition?>(null)
    override val outcome = MutableStateFlow<ReadAloudOutcome?>(null)
    val calls = mutableListOf<String>()
    var closed = false
    override fun play() { calls += "play"; playing.value = true }
    override fun pause() { calls += "pause"; playing.value = false }
    override fun next() { calls += "next" }
    override fun previous() { calls += "previous" }
    override fun setSpeed(speed: Double) { calls += "speed $speed" }
    override fun voices() = listOf(VoiceOption("en-US", "en-a", "English (United States) · High"), VoiceOption("en-GB", "en-b", "English (United Kingdom) · Normal"), VoiceOption("fr-FR", "fr-a", "French (France) · High"))
    override fun setVoice(language: String, id: String) { calls += "voice $language $id" }
    override fun close() { closed = true }
  }

  private val playback = FakePlayback()
  private val speechOpens = mutableListOf<ReadAloudPrefs>()
  private var openResult: Result<ReadAloudPlayback> = Result.success(playback)
  private var voiceInstalls = 0
  private val support = ReadAloudSupport({ _, _, prefs, _, _ -> speechOpens += prefs; openResult }, { voiceInstalls++ })

  private var time = 1_000_000L

  private fun session() = ReaderSession(testBook(1, "Emma", author = "Jane Austen"), stubPublication(), emptyList(), null)

  /** Waits in real time: opening reads the publication's positions on the IO dispatcher. */
  private suspend fun ReaderState.awaitSettled() =
    withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5_000) { load.first { it !is ReaderLoad.Loading } } }

  @Test fun `a book no longer in the library fails and lets indexing resume`() = runTest(dispatcher) {
    val reader = reader(ReaderRequest(2))
    runCurrent()
    assertEquals(ReaderLoad.Failed("This book is no longer in the library."), reader.load.value)
    assertEquals(listOf(true, false), indexer.busy)
    assertEquals(1, indexer.requests)
  }

  @Test fun `a book that can't be opened is marked unreadable`() = runTest(dispatcher) {
    val reader = reader(open = { Result.failure(IOException("damaged")) })
    runCurrent()
    assertTrue(reader.load.value is ReaderLoad.Failed)
    assertEquals(listOf("unreadable 1"), store.writes)
    assertEquals(listOf(true, false), indexer.busy)
  }

  @Test fun `closing releases the indexer exactly once, even when called again`() = runTest(dispatcher) {
    val reader = reader(ReaderRequest(2))
    runCurrent()
    reader.close(); reader.close()
    assertEquals(ReaderLoad.Idle, reader.load.value)
    assertEquals(listOf(true, false), indexer.busy)
  }

  @Test fun `leaving while the book is still opening stops the load and releases the indexer`() = runTest(dispatcher) {
    val gate = CompletableDeferred<Result<Publication>>()
    val reader = reader(open = { gate.await() })
    runCurrent()
    assertEquals(ReaderLoad.Loading, reader.load.value)
    reader.close()
    gate.complete(Result.success(stubPublication())); runCurrent()
    assertEquals(ReaderLoad.Idle, reader.load.value)
    assertEquals(listOf(true, false), indexer.busy)
    assertFalse("a cancelled load must not count as opening the book", "opened 1" in store.writes)
  }

  @Test fun `an opened book is ready, marked opened, and keeps the indexer away until closed`() = runTest(dispatcher) {
    val reader = reader()
    val load = reader.awaitSettled()
    assertTrue(load is ReaderLoad.Ready)
    assertEquals(listOf(File("/books/1.epub")), opened)
    runCurrent()
    assertTrue("opened 1" in store.writes)
    assertEquals(listOf(true), indexer.busy)
    reader.leave()
    assertEquals(listOf(Visit.Library), nav.visits)
    reader.close()
    assertEquals(listOf(true, false), indexer.busy)
  }

  @Test fun `the book is ready with its own settings and brightness, so the page is never laid out twice`() = runTest(dispatcher) {
    store.prefs.value = ReaderPrefs(fontSize = 25)
    store.brightness.value = 40
    val reader = reader()
    // Runs the moment the load turns ready, before anything started afterwards could fill the settings in.
    val atReady = backgroundScope.async { reader.load.first { it is ReaderLoad.Ready }; reader.prefs.value.fontSize to reader.ui.value.brightness }
    reader.awaitSettled()
    runCurrent()
    assertEquals(25 to 40, atReady.await())
  }

  @Test fun `settings edits are published at once and written for this book`() = runTest(dispatcher) {
    val reader = reader()
    reader.awaitSettled()
    reader.updatePrefs { it.copy(fontSize = 25) }
    assertEquals(25, reader.prefs.value.fontSize)
    runCurrent()
    assertTrue("prefs 1 25" in store.writes)
    reader.resetBookPrefs(); runCurrent()
    assertEquals("Using your default settings", toasts.shown.last())
  }

  @Test fun `overlays open and close as before`() = runTest(dispatcher) {
    val reader = reader()
    reader.setChrome(true); reader.openSheet(Sheet.Display)
    reader.showZones(true)
    assertNull(reader.ui.value.sheet); assertFalse(reader.ui.value.chrome); assertTrue(reader.ui.value.showZones)
    reader.setChrome(true); reader.setActiveHighlight(3)
    assertFalse(reader.ui.value.chrome); assertEquals(3L, reader.ui.value.activeHighlight)
    reader.closeReaderOverlays()
    assertEquals(ReaderUiState(), reader.ui.value)
  }

  @Test fun `show all in this book pages the book's matches in order`() = runTest(dispatcher) {
    store.pages[-1] = testPage(1, 0..19, next = 19)
    store.pages[19] = testPage(1, 20..29, next = null)
    val reader = reader(ReaderRequest(1, libraryQuery = "pemberley"))
    reader.awaitSettled()
    assertTrue(reader.ui.value.textSearchOpen)
    assertEquals(1L, reader.ui.value.bookSearch?.bookId)
    runCurrent(); advanceTimeBy(300); runCurrent()
    assertEquals(BookSearchStatus.Results, reader.bookSearchUi.value.status)
    reader.loadMoreBookSearch(); reader.loadMoreBookSearch(); runCurrent()
    assertEquals((0..29).toList(), reader.bookSearchUi.value.snippets.map { it.seq })
    assertFalse(reader.bookSearchUi.value.hasMore)
  }

  @Test fun `a stale match is not opened and asks for the book to be indexed again`() = runTest(dispatcher) {
    val reader = reader()
    reader.awaitSettled()
    store.current = false
    reader.openBookSearchHit(testTarget(1)); runCurrent()
    assertEquals(listOf(STALE_TARGET_MESSAGE), toasts.shown)
    assertEquals(1, indexer.requests)
  }

  @Test fun `sharing a selection sends it as a quote with its book`() = runTest(dispatcher) {
    reader().onSelectedText(session(), SelectionAction.Share, "It is a truth universally acknowledged")
    assertEquals(listOf("“It is a truth universally acknowledged”\n— Emma, Jane Austen"), shared)
    assertTrue(lookedUp.isEmpty())
  }

  @Test fun `looking up a selection hands over the bare text`() = runTest(dispatcher) {
    reader().onSelectedText(session(), SelectionAction.LookUp, "truth")
    assertEquals(listOf("truth"), lookedUp)
    assertTrue(shared.isEmpty())
  }

  @Test fun `highlighting and noting are left to the book, not handled as text`() = runTest(dispatcher) {
    assertFalse(reader().onSelectedText(session(), SelectionAction.Highlight, "x"))
    assertFalse(reader().onSelectedText(session(), SelectionAction.Note, "x"))
    assertFalse(reader().onSelectedText(session(), SelectionAction.ReadAloud, "x"))
    assertTrue(shared.isEmpty() && lookedUp.isEmpty())
  }

  @Test fun `the reading controls come from the store and follow it`() = runTest(dispatcher) {
    store.controls.value = ReaderControls(keepScreenOn = true)
    val reader = reader()
    runCurrent()
    assertEquals(ReaderControls(keepScreenOn = true), reader.controls.value)
    store.controls.value = ReaderControls(volumeKeys = true, orientation = ReaderOrientation.Portrait)
    runCurrent()
    assertEquals(ReaderControls(volumeKeys = true, orientation = ReaderOrientation.Portrait), reader.controls.value)
  }

  @Test fun `recolouring a highlight writes it and makes that colour the next one's`() = runTest(dispatcher) {
    val reader = reader()
    reader.setHighlightColor(7, 2)
    runCurrent()
    assertTrue("colour 7 2" in store.writes)
    assertEquals(2, store.lastColor.value)
  }

  @Test fun `the highlights list can be narrowed to a colour and widened again`() = runTest(dispatcher) {
    val reader = reader()
    reader.setHighlightColorFilter(3)
    assertEquals(3, reader.ui.value.highlightColorFilter)
    reader.setHighlightColorFilter(null)
    assertNull(reader.ui.value.highlightColorFilter)
  }

  @Test fun `reading time between coming to the front and leaving is kept as a session`() = runTest(dispatcher) {
    val reader = reader()
    reader.awaitSettled(); runCurrent()
    reader.resumeReading()
    time += 90_000
    reader.pauseReading()
    runCurrent()
    val (book, span) = store.sessions.single()
    assertEquals(1L, book)
    assertEquals(90_000L, span.activeMs)
  }

  @Test fun `a short visit is not kept`() = runTest(dispatcher) {
    val reader = reader()
    reader.awaitSettled(); runCurrent()
    reader.resumeReading()
    time += 10_000
    reader.pauseReading()
    runCurrent()
    assertTrue(store.sessions.isEmpty())
  }

  @Test fun `closing the reader while it is being read keeps the session once`() = runTest(dispatcher) {
    val reader = reader()
    reader.awaitSettled(); runCurrent()
    reader.resumeReading()
    time += 60_000
    reader.close()
    reader.pauseReading()
    runCurrent()
    assertEquals(1, store.sessions.size)
  }

  // ── read aloud ───────────────────────────────────────────────────────────

  private suspend fun TestScope.readingAloud(): ReaderState {
    val reader = reader()
    reader.awaitSettled(); runCurrent()
    reader.startReadAloud(); runCurrent()
    return reader
  }

  @Test fun `read aloud is not offered, and says so, where there is no speech support`() = runTest(dispatcher) {
    val reader = reader(readAloud = null)
    reader.awaitSettled(); runCurrent()
    reader.startReadAloud()
    assertEquals(listOf("Read aloud isn’t available"), toasts.shown)
    assertFalse(reader.readAloud.value.active)
    assertFalse(com.quire.reader.reader.SelectionAction.ReadAloud in reader.selectionActions)
    assertTrue(com.quire.reader.reader.SelectionAction.ReadAloud in reader().selectionActions)
  }

  @Test fun `reading aloud opens with the saved speed and voices, then plays`() = runTest(dispatcher) {
    store.readAloud.value = ReadAloudPrefs(speed = 1.5, voices = mapOf("en-US" to "en-a"))
    val reader = readingAloud()
    assertEquals(listOf(ReadAloudPrefs(speed = 1.5, voices = mapOf("en-US" to "en-a"))), speechOpens)
    assertEquals(listOf("play"), playback.calls)
    assertEquals(ReadAloudStatus.Playing, reader.readAloud.value.status)
    assertEquals(1.5, reader.readAloud.value.speed, 0.0)
  }

  @Test fun `a book that cannot be read aloud says why and stays quiet`() = runTest(dispatcher) {
    openResult = Result.failure(ReadAloudUnavailable("This book can’t be read aloud."))
    val reader = readingAloud()
    assertEquals(listOf("This book can’t be read aloud."), toasts.shown)
    assertEquals(ReadAloudStatus.Off, reader.readAloud.value.status)
    assertTrue(playback.calls.isEmpty())
  }

  @Test fun `play and pause follow the controls and the playback's own state`() = runTest(dispatcher) {
    val reader = readingAloud()
    reader.toggleReadAloud(); runCurrent()
    assertEquals(ReadAloudStatus.Paused, reader.readAloud.value.status)
    reader.toggleReadAloud(); runCurrent()
    assertEquals(ReadAloudStatus.Playing, reader.readAloud.value.status)
    // Paused from the notification, not from the reader.
    playback.pause(); runCurrent()
    assertEquals(ReadAloudStatus.Paused, reader.readAloud.value.status)
    reader.readAloudNext(); reader.readAloudPrevious()
    assertEquals(listOf("play", "pause", "play", "pause", "next", "previous"), playback.calls)
  }

  @Test fun `stopping closes the playback and puts the controls away`() = runTest(dispatcher) {
    val reader = readingAloud()
    reader.stopReadAloud(); runCurrent()
    assertTrue(playback.closed)
    assertEquals(ReadAloudStatus.Off, reader.readAloud.value.status)
  }

  @Test fun `leaving the reader stops it`() = runTest(dispatcher) {
    val reader = readingAloud()
    reader.close()
    assertTrue(playback.closed)
  }

  @Test fun `starting again while it is going plays or pauses instead of opening a second one`() = runTest(dispatcher) {
    val reader = readingAloud()
    reader.startReadAloud(); runCurrent()
    assertEquals(1, speechOpens.size)
    assertEquals(ReadAloudStatus.Paused, reader.readAloud.value.status)
  }

  @Test fun `the speed steps up, wraps round, and is remembered`() = runTest(dispatcher) {
    val reader = readingAloud()
    val seen = mutableListOf<Double>()
    repeat(6) { reader.cycleReadAloudSpeed(); runCurrent(); seen += reader.readAloud.value.speed }
    assertEquals(listOf(1.25, 1.5, 1.75, 2.0, 0.75, 1.0), seen)
    assertEquals("speed 1.25", playback.calls[1])
    assertEquals(1.0, store.readAloud.value.speed, 0.0)
  }

  @Test fun `a voice is picked from the book's language, and remembered for it`() = runTest(dispatcher) {
    val reader = readingAloud()
    assertEquals(listOf("en-a", "en-b"), reader.readAloudVoices().map { it.id })
    assertNull(reader.readAloudVoiceId())
    reader.chooseReadAloudVoice(reader.readAloudVoices().first()); runCurrent()
    assertEquals("voice en-US en-a", playback.calls.last())
    assertEquals(mapOf("en-US" to "en-a"), store.readAloud.value.voices)
    assertEquals("en-a", reader.readAloudVoiceId())
  }

  @Test fun `asking for more voices goes to the system`() = runTest(dispatcher) {
    readingAloud().installVoices()
    assertEquals(1, voiceInstalls)
  }

  @Test fun `reaching the end of the book stops it and says so`() = runTest(dispatcher) {
    val reader = readingAloud()
    playback.outcome.value = ReadAloudOutcome.Ended; runCurrent()
    assertEquals(listOf("Finished reading aloud"), toasts.shown)
    assertTrue(playback.closed)
    assertEquals(ReadAloudStatus.Off, reader.readAloud.value.status)
  }

  @Test fun `an engine failure stops it with its reason`() = runTest(dispatcher) {
    val reader = readingAloud()
    playback.outcome.value = ReadAloudOutcome.Failed("engine died"); runCurrent()
    assertEquals(listOf("Read aloud stopped: engine died"), toasts.shown)
    assertTrue(playback.closed)
    assertFalse(reader.readAloud.value.active)
  }

  @Test fun `being read to with the screen off still counts as reading, until it stops`() = runTest(dispatcher) {
    val reader = readingAloud()
    reader.resumeReading()
    time += 60_000
    reader.pauseReading()           // the app goes to the background; it keeps speaking
    runCurrent()
    assertTrue(store.sessions.isEmpty())
    time += 120_000
    playback.pause(); runCurrent()  // paused from the notification
    val (_, span) = store.sessions.single()
    assertEquals(180_000L, span.activeMs)
  }

  @Test fun `with the screen on a pause ends nothing, the reader still being looked at`() = runTest(dispatcher) {
    val reader = readingAloud()
    reader.resumeReading()
    time += 60_000
    playback.pause(); runCurrent()
    assertTrue(store.sessions.isEmpty())
    reader.pauseReading(); runCurrent()
    assertEquals(1, store.sessions.size)
  }

  @Test fun `any overlay over the page counts as open`() {
    assertFalse(ReaderUiState().overlaysOpen)
    listOf(
      ReaderUiState(chrome = true), ReaderUiState(sheet = Sheet.Display), ReaderUiState(textSearchOpen = true),
      ReaderUiState(showZones = true), ReaderUiState(activeHighlight = 3), ReaderUiState(noteFor = 3),
    ).forEach { assertTrue("$it", it.overlaysOpen) }
  }

  @Test fun `the menu offers look up only when an app can take the text`() = runTest(dispatcher) {
    assertTrue(SelectionAction.LookUp in reader().selectionActions)
    assertFalse(SelectionAction.LookUp in reader(lookUp = null).selectionActions)
    assertTrue(SelectionAction.Share in reader(lookUp = null).selectionActions)
  }
}
