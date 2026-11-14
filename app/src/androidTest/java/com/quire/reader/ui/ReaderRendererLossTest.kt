package com.quire.reader.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.MainActivity
import com.quire.reader.QuireApplication
import com.quire.reader.data.Book
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.index.BatchStop
import com.quire.reader.data.index.EpubFixtures
import com.quire.reader.data.index.FixtureResource
import com.quire.reader.data.index.FixtureToc
import com.quire.reader.navigator.R2BasicWebView
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import com.quire.reader.navigator.epub.ReaderMedia
import com.quire.reader.reader.ReaderSession
import com.quire.reader.ui.reader.ReaderLoad
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File

/**
 * A WebView renderer that dies (a crash, or the system reclaiming its memory) must not take the app down in paged mode,
 * in a fixed-layout book or in the image viewer: the first loss rebuilds the pages where the reader was, and a second one
 * within 30 s fails the book while the app stays usable. The renderer is killed for real, through
 * `WebView.getWebViewRenderProcess().terminate()`. Run only under the `.dbtest` application id: it indexes and opens books.
 */
class ReaderRendererLossTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var reflowable: Book
  private lateinit var fixed: Book

  @Before fun setUp() = runBlocking {
    app.settings.setOnboardingDone(true)
    // The notification prompt would pause the activity over the reader, and the tests check it stays resumed.
    instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    val dir = File(app.filesDir, "renderer-loss-books").apply { deleteRecursively(); mkdirs() }
    writeReflowable(File(dir, "reflowable.epub"))
    writeFixed(File(dir, "fixed.epub"))
    // Library isolation: other instrumentation classes add their own fixture folders.
    app.library.folders.first().filter { File(it.path).canonicalPath != dir.canonicalPath }.forEach { app.library.removeFolder(it.id) }
    app.library.addFolder(dir.path)
    app.library.rescan()
    while (app.indexer.runBatch(System.currentTimeMillis() + 120_000).stop != BatchStop.Drained) Unit
    val books = app.library.books.first()
    reflowable = books.single { it.path.endsWith("reflowable.epub") }
    fixed = books.single { it.path.endsWith("fixed.epub") }
    scenario = ActivityScenario.launch(MainActivity::class.java)
    vm = awaitViewModel()
  }

  @After fun tearDown() {
    runBlocking { app.library.clearBookPrefs(reflowable.id) }
    scenario.onActivity { vm.closeReader() }
    scenario.close()
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test fun `a lost renderer rebuilds the paged reader where it was`() {
    runBlocking { app.library.setBookPrefs(reflowable.id, ReaderPrefs(mode = ReadMode.Paged)) }
    open(reflowable)
    val nav = navigator()
    scenario.onActivity { session().go(session().publication.readingOrder[2]) }
    awaitCondition("on chapter 2") { currentHref(nav) == "c2.xhtml" }

    val before = nav.containerGeneration
    killRenderer { it is R2BasicWebView }
    awaitRebuilt(nav, before)
    assertEquals("still on chapter 2 after the rebuild", "c2.xhtml", currentHref(nav))
  }

  @Test fun `a lost renderer rebuilds a fixed-layout book on the same page`() {
    open(fixed)
    val nav = navigator()
    scenario.onActivity { session().go(session().publication.readingOrder[1]) }
    awaitCondition("on page 2") { currentHref(nav) == "p1.xhtml" }

    val before = nav.containerGeneration
    killRenderer { it is R2BasicWebView }
    awaitRebuilt(nav, before)
    assertEquals("still on page 2 after the rebuild", "p1.xhtml", currentHref(nav))
  }

  @Test fun `a second loss within 30 s fails the book and the app stays usable`() {
    runBlocking { app.library.setBookPrefs(reflowable.id, ReaderPrefs(mode = ReadMode.Paged)) }
    open(reflowable)
    val nav = navigator()

    val before = nav.containerGeneration
    killRenderer { it is R2BasicWebView }
    awaitRebuilt(nav, before)
    killRenderer { it is R2BasicWebView }
    awaitCondition("the book failed") { nav.readiness.value is EpubNavigatorFragment.Readiness.Failed }
    Thread.sleep(500)
    assertEquals("the app is alive", Lifecycle.State.RESUMED, scenario.state)

    scenario.onActivity { vm.closeReader() }
    awaitCondition("back in the library") { vm.readerLoad is ReaderLoad.Idle }
  }

  @Test fun `a lost renderer closes the image viewer and the reader recovers`() {
    runBlocking { app.library.setBookPrefs(reflowable.id, ReaderPrefs(mode = ReadMode.Paged)) }
    open(reflowable)
    val nav = navigator()
    val base = nav.servedUrlFor(session().publication.readingOrder[0].url()) as? AbsoluteUrl ?: error("no served url")
    scenario.onActivity {
      vm.reader.openMedia(ReaderMedia.Markup("<table><tr><td>a</td><td>b</td></tr></table>", base, emptyList(), null, table = true))
    }
    awaitCondition("the viewer's WebView is showing") { findWebView { it !is R2BasicWebView } != null }

    // The viewer shares the renderer with the pages, so both lose it: the viewer closes and the pages are rebuilt.
    val before = nav.containerGeneration
    killRenderer { it !is R2BasicWebView }
    awaitCondition("the viewer closed") { vm.reader.ui.value.media == null }
    awaitRebuilt(nav, before)
    assertNull("no viewer WebView left", findWebView { it !is R2BasicWebView })
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private fun writeReflowable(file: File) {
    fun filler(c: Int) = (1..40).joinToString("") { p -> "<p>Chapter $c paragraph $p, about the slow barge on the grey canal $c-$p.</p>" }
    val resources = (0..4).map { c -> FixtureResource("c$c.xhtml", """<h2>Chapter $c</h2>""" + filler(c), "<title>Chapter $c</title>") }
    EpubFixtures.write(file, resources, (0..4).map { c -> FixtureToc("c$c.xhtml", null, "Chapter $c") })
  }

  private fun writeFixed(file: File) {
    val head = { p: Int -> """<title>Page $p</title><meta name="viewport" content="width=600, height=800"/>""" }
    val resources = (0..2).map { p ->
      FixtureResource("p$p.xhtml", """<div style="width:600px;height:800px"><h1>Fixed page $p</h1></div>""", head(p))
    }
    EpubFixtures.write(file, resources, (0..2).map { p -> FixtureToc("p$p.xhtml", null, "Page $p") }, fixedLayout = true)
  }

  /** Kills the renderer of the first shown WebView that [which] accepts. */
  private fun killRenderer(which: (WebView) -> Boolean) {
    var killed = false
    scenario.onActivity { activity ->
      val webView = findWebView(activity.window.decorView, which) ?: error("no WebView to kill")
      killed = webView.webViewRenderProcess?.terminate() == true
    }
    check(killed) { "the renderer could not be terminated" }
  }

  private fun findWebView(which: (WebView) -> Boolean): WebView? {
    var found: WebView? = null
    scenario.onActivity { activity -> found = findWebView(activity.window.decorView, which) }
    return found
  }

  private fun findWebView(view: View, which: (WebView) -> Boolean): WebView? {
    if (view is WebView && view.isShown && which(view)) return view
    if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i), which)?.let { return it }
    return null
  }

  /** Waits until the navigator rebuilt its container (from generation [before]) and the rebuilt page is ready. */
  private fun awaitRebuilt(nav: EpubNavigatorFragment, before: Int) {
    awaitCondition("the pages rebuilt and ready") {
      nav.containerGeneration > before && nav.readiness.value is EpubNavigatorFragment.Readiness.Ready
    }
    assertEquals("the app is alive", Lifecycle.State.RESUMED, scenario.state)
  }

  private fun currentHref(nav: EpubNavigatorFragment): String = nav.currentLocator.value.href.toString().substringAfterLast('/')

  private fun awaitViewModel(): QuireViewModel {
    var found: QuireViewModel? = null
    awaitCondition("view model") {
      scenario.onActivity { activity -> found = runCatching { ViewModelProvider(activity)[QuireViewModel::class.java] }.getOrNull() }
      found != null
    }
    return found!!
  }

  private fun open(book: Book) {
    scenario.onActivity { vm.read(book.id) }
    awaitCondition("reader ready") { vm.readerLoad is ReaderLoad.Ready }
    awaitCondition("whole-book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun session(): ReaderSession = (vm.readerLoad as ReaderLoad.Ready).session

  private fun navigator(): EpubNavigatorFragment = session().navigator ?: error("no navigator")

  private fun awaitCondition(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }
}
