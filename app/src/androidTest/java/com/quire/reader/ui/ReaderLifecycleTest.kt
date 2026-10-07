package com.quire.reader.ui

import android.content.pm.ActivityInfo
import com.quire.reader.ui.reader.ReaderLoad
import android.content.res.Configuration
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.MainActivity
import com.quire.reader.QuireApplication
import com.quire.reader.data.Book
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.index.EpubFixtures
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import com.quire.reader.reader.ReaderSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The reader across saved-state restoration. MainActivity hands the FragmentManager Readium's
 * dummy navigator factory before super.onCreate and drops whatever the framework restored right
 * after it, so process death, recreation and rotation can no longer crash on the vendored
 * navigator's missing default constructor. These tests pin the four lifecycle paths and the
 * reading position each of them returns to.
 *
 * Process death is emulated in-process: the ViewModel store is cleared before
 * [ActivityScenario.recreate], which rebuilds the activity from the same saved bundle the
 * framework delivers after a real kill, navigator fragment state included. A connected test
 * cannot kill the app process for real — the instrumentation runs inside it, and `am kill`
 * refuses an instrumented process — so the real-kill drill stays a manual step (open the
 * reader, home, `adb shell am kill`, relaunch, reopen).
 *
 * Books come from the folder given as the `booksDir` instrumentation argument, or, without it,
 * from the generated scroll fixture. Run only under the `.dbtest` application id, never over
 * the installed app: it indexes and opens books.
 */
class ReaderLifecycleTest {
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
    // Library isolation: drop folders other test classes left behind, so "largest book"
    // is always the scroll fixture.
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

  @Test fun `a process death restores without a crash and reopens at the persisted position`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val left = jumpAndCapture()
    awaitPersisted(left)

    // Emulate the kill: the ViewModel (and its open session) are gone, but the activity is
    // rebuilt from the same saved bundle a real kill delivers, navigator fragment state
    // included.
    scenario.onActivity { it.viewModelStore.clear() }
    scenario.recreate()
    vm = awaitViewModel()

    // The fresh start behaves like the relaunch after a kill: it decides on the library, not
    // the reader, and the FragmentManager holds nothing restored.
    awaitCondition("fresh start off the splash screen") { vm.destination.value != Destination.Splash }
    assertEquals(Destination.Library, vm.destination.value)
    awaitCondition("no restored navigator left") { navigatorCount() == 0 }

    // Reopening the book returns to the persisted position.
    open { vm.read(book.id) }
    assertAt(left)
  }

  @Test fun `an activity recreation keeps the reader open at the same position`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val left = jumpAndCapture()
    val sessionBefore = session()

    scenario.recreate()

    // The ViewModel and its session survive the recreation; the reader screen composes again
    // with the same session and a freshly added navigator.
    awaitCondition("reader ready again") { vm.readerLoad is ReaderLoad.Ready }
    awaitReaderRebuilt()
    assertSame("the open session survives recreation", sessionBefore, session())
    assertEquals("exactly one live navigator after recreation", 1, navigatorCount())
    assertAt(left)
  }

  @Test fun `a rotation keeps the reader open at the same position`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val left = jumpAndCapture()
    val sessionBefore = session()
    var activityBefore: MainActivity? = null
    scenario.onActivity { activityBefore = it }

    // MainActivity declares the orientation config changes, so a rotation re-anchors the
    // scroll surface in place instead of recreating the activity.
    rotate(toLandscape = true)
    awaitReaderRebuilt()
    assertSame("rotation is handled in place", activityBefore, currentActivity())
    assertSame("the open session survives a rotation", sessionBefore, session())
    assertAt(left)

    rotate(toLandscape = false)
    awaitReaderRebuilt()
    assertAt(left)
  }

  @Test fun `closing and reopening the reader returns to the persisted position`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val left = jumpAndCapture()
    awaitPersisted(left)

    vm.closeReader()
    awaitCondition("back on the library") { vm.destination.value == Destination.Library }

    open { vm.read(book.id) }
    assertAt(left)
  }

  @Test fun `an activity recreation in paged mode restores without a crash`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Paged)) }
    open { vm.read(book.id) }
    val left = jumpAndCapture()

    scenario.recreate()

    awaitCondition("reader ready again") { vm.readerLoad is ReaderLoad.Ready }
    awaitReaderRebuilt()
    // The restored navigator dummy and its restored page fragments are gone; the live pager
    // keeps its page fragments inside the navigator's own child manager.
    assertEquals("exactly one live navigator after recreation", 1, navigatorCount())
    scenario.onActivity { assertEquals(1, it.supportFragmentManager.fragments.size) }
    assertAt(left)
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** The fixture chapter a reported locator belongs to, by its original href, or null. */
  private fun reportedChapter(locator: org.readium.r2.shared.publication.Locator?): Int? {
    val href = locator?.href?.toString() ?: return null
    return (0..5).firstOrNull { href.endsWith("c$it.xhtml") }
  }

  /** Jumps to mid-book and returns the reported locator once it has stopped moving. */
  private fun jumpAndCapture(): org.readium.r2.shared.publication.Locator {
    val s = session()
    // The pager's jumps execute FragmentManager transactions, so they belong on the main thread.
    scenario.onActivity { s.goToProgress(0.5f) }
    var last: org.readium.r2.shared.publication.Locator? = null
    awaitCondition("jump settles on a stable locator") {
      val now = s.current.value
      val stable = now != null && now == last
      last = now
      stable
    }
    return last!!
  }

  /** Waits until the debounce has written [expected]'s position to the database. */
  private fun awaitPersisted(expected: org.readium.r2.shared.publication.Locator) = awaitCondition("position persisted") {
    val saved = runBlocking { app.library.readingState(book.id) } ?: return@awaitCondition false
    val locator = ReaderSession.parseLocator(saved.locatorJson) ?: return@awaitCondition false
    reportedChapter(locator) == reportedChapter(expected) &&
      abs((locator.locations.totalProgression ?: 0.0) - (expected.locations.totalProgression ?: 0.0)) < 1e-3
  }

  /** Waits until the reader reports [expected]'s chapter and progression again. */
  private fun assertAt(expected: org.readium.r2.shared.publication.Locator) {
    val s = session()
    awaitCondition("reader back at the persisted position") {
      val now = s.current.value ?: return@awaitCondition false
      reportedChapter(now) == reportedChapter(expected) &&
        abs((now.locations.totalProgression ?: 0.0) - (expected.locations.totalProgression ?: 0.0)) <= POSITION_TOLERANCE
    }
  }

  private fun awaitReaderRebuilt() {
    awaitCondition("navigator re-attached") { navigatorCount() == 1 }
    awaitCondition("whole-book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun navigatorCount(): Int {
    var count = -1
    scenario.onActivity { count = it.supportFragmentManager.fragments.count { f -> f is EpubNavigatorFragment } }
    return count
  }

  private fun currentActivity(): MainActivity? {
    var activity: MainActivity? = null
    scenario.onActivity { activity = it }
    return activity
  }

  private fun rotate(toLandscape: Boolean) {
    val requested = if (toLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    val expected = if (toLandscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
    scenario.onActivity { it.requestedOrientation = requested }
    awaitCondition("orientation applied") {
      var applied = false
      scenario.onActivity { applied = it.resources.configuration.orientation == expected }
      applied
    }
  }

  /** All-files access, so a fresh start decides on the library like a granted install does. */
  private fun grantAllFilesAccess() {
    val command = instrumentation.uiAutomation.executeShellCommand("appops set ${app.packageName} MANAGE_EXTERNAL_STORAGE allow")
    // Read the command's output to EOF (and close the descriptor), or the command never finishes.
    android.os.ParcelFileDescriptor.AutoCloseInputStream(command).readBytes()
  }

  private fun generatedLibrary(): String {
    val dir = File(app.filesDir, "lifecycle-test-books").apply { deleteRecursively(); mkdirs() }
    EpubFixtures.writeScrollBook(File(dir, "scroll-fixture.epub"))
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

  private fun open(start: () -> Unit) {
    scenario.onActivity { start() }
    awaitCondition("reader ready") { vm.readerLoad is ReaderLoad.Ready }
    // Whole-book readiness: the scroll surface is only asked for content once every chapter is
    // prepared.
    awaitCondition("whole-book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun session(): ReaderSession = (vm.readerLoad as ReaderLoad.Ready).session

  private fun awaitCondition(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }

  private companion object {
    /** Re-landing is by progression and positions are coarse; one position is well inside this. */
    const val POSITION_TOLERANCE = 0.05
  }
}
