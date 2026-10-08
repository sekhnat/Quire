package com.quire.reader.ui

import androidx.lifecycle.ViewModelProvider
import com.quire.reader.ui.reader.ReaderLoad
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.MainActivity
import com.quire.reader.QuireApplication
import com.quire.reader.data.Book
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.index.EpubFixtures
import com.quire.reader.navigator.epub.ContinuousBookState
import com.quire.reader.navigator.epub.ContinuousBookWebView
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import com.quire.reader.navigator.epub.PressureTiers
import com.quire.reader.reader.ReaderSession
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.readium.r2.shared.InternalReadiumApi

/**
 * The continuous-scroll surface's disposal contract. `ContinuousBookWebView` owns its scope,
 * shell WebView, JavaScript bridges and pending callbacks, so every terminal path of the
 * reader must leave exactly one disposed, detached, collectible surface behind — and
 * in-flight callers (script requests, frame-load waits) must be released at once instead of
 * waiting out a timeout on a destroyed WebView.
 *
 * Books come from the folder given as the `booksDir` instrumentation argument, or, without
 * it, from the generated long fixture: the mid-wait release test needs a chapter far enough
 * outside the live window that it cannot load on its own while the test runs. Run only under
 * the `.dbtest` application id, never over the installed app: it indexes and opens books.
 */
@OptIn(InternalReadiumApi::class)
class ReaderDisposalTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private val args = InstrumentationRegistry.getArguments()
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var book: Book

  @Before fun setUp() = runBlocking {
    grantAllFilesAccess()
    app.settings.setOnboardingDone(true)
    val dir = args.getString("booksDir") ?: generatedLibrary()
    // Library isolation: drop folders other test classes left behind, so the generated
    // fixture is the only book.
    app.library.folders.first().filter { File(it.path).canonicalPath != File(dir).canonicalPath }.forEach { app.library.removeFolder(it.id) }
    app.library.addFolder(dir)
    app.library.rescan()
    while (app.indexer.runBatch(System.currentTimeMillis() + 120_000).stop != com.quire.reader.data.index.BatchStop.Drained) Unit
    book = app.library.books.first().maxBy { it.sizeBytes }
    scenario = ActivityScenario.launch(MainActivity::class.java)
    vm = awaitViewModel()
  }

  @After fun tearDown() {
    runBlocking { app.library.clearBookPrefs(book.id) }
    runCatching {
      scenario.onActivity { activity -> ViewModelProvider(activity)[QuireViewModel::class.java].closeReader() }
    }
    scenario.close()
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test fun `dispose is idempotent and rejects late callers`() {
    setScrollMode()
    open()
    val nav = session().navigator!!
    scenario.onActivity {
      nav.continuousBook!!.dispose()
      nav.continuousBook!!.dispose() // The second call must be a no-op, not a second destroy.
    }
    val disposed = nav.continuousBook!!
    assertEquals(ContinuousBookState.Disposed, disposed.state.value)

    // A frame request after disposal fails fast instead of waiting out the frame-load
    // timeout, and a script call is answered with a null result.
    var frameResult: Boolean? = true
    var elapsed = 0L
    runBlocking {
      elapsed = measureTimeMillis {
        frameResult = withTimeoutOrNull(4_000) { disposed.withFrame(farHref(nav)) { true } }
      }
    }
    assertTrue("withFrame answered in ${elapsed}ms, not at the frame-load timeout", elapsed < 5_000)
    assertNull("withFrame fails fast on a disposed surface", frameResult)
    assertEquals("null", runBlocking { disposed.runnerFor(farHref(nav))!!.runJavaScriptSuspend("1") })

    // The reader still closes cleanly: the navigator's own disposal is a no-op now.
    vm.closeReader()
    awaitCondition("back on the library") { vm.destination.value == Destination.Library }
    awaitCondition("navigator removed") { navigatorCount() == 0 }
  }

  @Test fun `a script caller suspended on the shell is answered once the surface is disposed`() {
    setScrollMode()
    open()
    val nav = session().navigator!!
    val href = nav.readingOrder.first().url().removeFragment()
    val done = CompletableDeferred<String?>()
    scenario.onActivity {
      val book = nav.continuousBook!!
      val runner = book.runnerFor(href)!!
      CoroutineScope(Job() + Dispatchers.Main.immediate).launch {
        // Runs to its suspension point on this same main-thread turn, so the shell's
        // answer cannot be delivered before the next loop pass: the disposal below is
        // what releases this caller.
        done.complete(runner.runJavaScriptSuspend("(function(){return 41 + 1})()"))
      }
      book.dispose()
    }
    val answer = runBlocking { withTimeoutOrNull(5_000) { done.await() } }
    assertEquals("the disposal answers the suspended caller", "null", answer)
  }

  @Test fun `a frame load wait is released at once when the surface is disposed mid wait`() {
    setScrollMode()
    open()
    val nav = session().navigator!!
    val href = nav.readingOrder.first().url().removeFragment()
    val done = CompletableDeferred<Boolean?>()
    scenario.onActivity {
      val book = nav.continuousBook!!
      // Marking a live frame's runner unloaded means nothing will ever report it loaded
      // again (the shell holds it live without a new frameLoaded event), so the frame-load
      // wait below can only end through the disposal - never on its own.
      book.runnerFor(href)!!.markUnloaded()
      CoroutineScope(Job() + Dispatchers.Main.immediate).launch {
        // Runs to its suspension point on this same main-thread turn: the wait is parked.
        done.complete(book.withFrame(href) { true })
      }
      book.dispose()
    }
    val answer = runBlocking { withTimeoutOrNull(5_000) { done.await() } }
    assertNull("the disposed surface releases the frame-load wait with null", answer)
  }

  @Test fun `closing the reader disposes the surface and drops the navigator`() {
    setScrollMode()
    open()
    val session = session()
    val nav = session.navigator!!
    var surface: ContinuousBookWebView? = null
    scenario.onActivity { surface = nav.continuousBook }
    vm.closeReader()
    awaitCondition("back on the library") { vm.destination.value == Destination.Library }
    awaitCondition("navigator removed") { navigatorCount() == 0 }
    assertEquals(ContinuousBookState.Disposed, surface!!.state.value)
    scenario.onActivity { assertNull("the surface left the container", surface!!.parent) }
  }

  @Test fun `switching to pages disposes the scroll surface`() {
    setScrollMode()
    open()
    val nav = session().navigator!!
    var surface: ContinuousBookWebView? = null
    scenario.onActivity { surface = nav.continuousBook }
    vm.reader.updatePrefs { it.copy(mode = ReadMode.Paged) }
    awaitCondition("the scroll surface is gone") { nav.continuousBook == null }
    assertEquals(ContinuousBookState.Disposed, surface!!.state.value)
    awaitCondition("the pager is live") { runCatching { nav.resourcePager }.isSuccess }
  }

  @Test fun `a renderer loss rebuilds the surface and a repeated loss disposes it`() {
    setScrollMode()
    open()
    val nav = session().navigator!!
    val first = nav.continuousBook!!
    scenario.onActivity { nav.bookHost.onRendererGone(false) }
    awaitCondition("the surface was rebuilt") { nav.continuousBook != null && nav.continuousBook !== first }
    awaitCondition("ready again", 60_000) { runBlocking { nav.awaitWholeBookReadiness() } }
    assertEquals("the dead surface was disposed", ContinuousBookState.Disposed, first.state.value)
    // The rebuilt surface starts with the reduced window, so the book is less likely to be killed again.
    assertEquals(PressureTiers.Tier.Reduced, nav.memoryPressureTier)
    assertEquals(PressureTiers.Tier.Reduced.ordinal, nav.continuousBook!!.pressureTier)

    val second = nav.continuousBook!!
    scenario.onActivity { nav.bookHost.onRendererGone(false) }
    awaitCondition("readiness failed") { nav.readiness.value is EpubNavigatorFragment.Readiness.Failed }
    assertNull("the failed surface is disposed immediately", nav.continuousBook)
    assertEquals(ContinuousBookState.Disposed, second.state.value)

    // Recovery: switching modes rebuilds a working reader around the failed one.
    vm.reader.updatePrefs { it.copy(mode = ReadMode.Paged) }
    awaitCondition("the pager is live") { runCatching { nav.resourcePager }.isSuccess }
    vm.reader.updatePrefs { it.copy(mode = ReadMode.Scroll) }
    awaitCondition("a fresh scroll surface") { nav.continuousBook != null }
    awaitCondition("ready again after recovery", 60_000) { runBlocking { nav.awaitWholeBookReadiness() } }
  }

@Test fun `repeated open and close cycles do not retain sessions or surfaces`() {
    val refs = mutableListOf<WeakReference<*>>()
    repeat(3) { cycle ->
      setScrollMode()
      open()
      cycleAndRecord(cycle, refs)
      vm.closeReader()
      awaitCondition("back on the library") { vm.destination.value == Destination.Library }
      awaitCondition("navigator removed") { navigatorCount() == 0 }
      // Collectibility after each close: 20 fixed collection rounds (about five seconds).
      // A real leak never clears; the window exists because collections are scheduled.
      gcRounds()
      val retained = refs.count { it.get() != null }
      assertTrue("cycle $cycle: retained after close: ${retainedNames(refs)}", retained == 0)
    }
  }

  /** Jumps to a position the restored locator is not at and waits for it to settle. */
  private fun cycleAndRecord(cycle: Int, refs: MutableList<WeakReference<*>>) {
    val target = listOf(0.5f, 0.25f, 0.75f)[cycle]
    val session = session()
    val nav = session.navigator!!
    nav.continuousBook?.let { refs.add(WeakReference(it)) }
    refs.add(WeakReference(nav))
    refs.add(WeakReference(session))
    scenario.onActivity { session.goToProgress(target) }
    awaitCondition("cycle $cycle settled near $target") {
      val progression = session.current.value?.locations?.totalProgression ?: return@awaitCondition false
      Math.abs(progression - target) < 0.05
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private fun farHref(nav: EpubNavigatorFragment) = nav.readingOrder.last().url().removeFragment()

  private fun setScrollMode() = runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }

  private fun open() {
    scenario.onActivity { vm.read(book.id) }
    awaitCondition("reader ready") { vm.readerLoad is ReaderLoad.Ready }
    awaitCondition("whole-book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun session(): ReaderSession = (vm.readerLoad as ReaderLoad.Ready).session

  private fun navigatorCount(): Int {
    var count = -1
    scenario.onActivity { count = it.supportFragmentManager.fragments.count { f -> f is EpubNavigatorFragment } }
    return count
  }

  /** Collects until every reference is cleared; names what survived when they did not.
   *  Collectibility is checked patiently - a busy process (indexing resumes on close,
   *  renderer teardown) can delay a collection by seconds, while a real leak never clears. */
  /** Fixed series of explicit collections; the caller checks what survived. */
  private fun gcRounds(rounds: Int = 20, sleepMs: Long = 250) {
    repeat(rounds) {
      Runtime.getRuntime().gc()
      Thread.sleep(sleepMs)
    }
  }

  private fun retainedNames(refs: List<WeakReference<*>>) = refs.mapNotNull { it.get()?.javaClass?.simpleName }

  /** All-files access, so a fresh start decides on the library like a granted install does. */
  private fun grantAllFilesAccess() {
    val command = instrumentation.uiAutomation.executeShellCommand("appops set ${app.packageName} MANAGE_EXTERNAL_STORAGE allow")
    // Read the command's output to EOF (and close the descriptor), or the command never finishes.
    android.os.ParcelFileDescriptor.AutoCloseInputStream(command).readBytes()
  }

  private fun generatedLibrary(): String {
    val dir = File(app.filesDir, "disposal-test-books").apply { deleteRecursively(); mkdirs() }
    EpubFixtures.writeLongBook(File(dir, "long-fixture.epub"))
    return dir.path
  }

  private fun awaitViewModel(): QuireViewModel {
    var found: QuireViewModel? = null
    awaitCondition("view model") {
      scenario.onActivity { activity -> found = runCatching { ViewModelProvider(activity)[QuireViewModel::class.java] }.getOrNull() }
      found != null
    }
    return found!!
  }

  private fun awaitCondition(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }
}
