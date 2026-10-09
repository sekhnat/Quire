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
import com.quire.reader.data.ReaderPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

  @Before fun setUp() = Dispatchers.setMain(dispatcher)
  @After fun tearDown() = Dispatchers.resetMain()

  private fun stubPublication() = Publication(Manifest(metadata = Metadata(identifier = "t", localizedTitle = LocalizedString("Emma"))))

  private fun TestScope.reader(
    request: ReaderRequest = ReaderRequest(1),
    open: suspend (File) -> Result<Publication> = { opened += it; Result.success(stubPublication()) },
  ) = ReaderState(request, store, library, open, indexer, nav, toasts, {}, backgroundScope.childScope(), backgroundScope, backgroundScope)

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
}
