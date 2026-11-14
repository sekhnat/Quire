package com.quire.reader.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.MainActivity
import com.quire.reader.QuireApplication
import com.quire.reader.data.Book
import com.quire.reader.data.index.BatchStop
import com.quire.reader.data.index.EpubFixtures
import com.quire.reader.data.index.FixtureResource
import com.quire.reader.data.index.FixtureToc
import com.quire.reader.navigator.R2BasicWebView
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import com.quire.reader.reader.ReaderSession
import com.quire.reader.ui.reader.ReaderLoad
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fixed-layout books, touched for real: a tap on a link follows it, a tap elsewhere shows the menu, and a swipe turns the
 * page. Fixed-layout pages sit in a zoomable layout that takes every touch before the page sees it, so none of this comes
 * from the page itself. Run only under the `.dbtest` application id: it indexes and opens books.
 */
class ReaderFixedLayoutTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var book: Book

  @Before fun setUp() = runBlocking {
    app.settings.setOnboardingDone(true)
    // The notification prompt would cover the reader and take the touches.
    instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    val dir = File(app.filesDir, "fixed-layout-books").apply { deleteRecursively(); mkdirs() }
    writeBook(File(dir, "fixed.epub"))
    // Library isolation: other instrumentation classes add their own fixture folders.
    app.library.folders.first().filter { File(it.path).canonicalPath != dir.canonicalPath }.forEach { app.library.removeFolder(it.id) }
    app.library.addFolder(dir.path)
    app.library.rescan()
    while (app.indexer.runBatch(System.currentTimeMillis() + 120_000).stop != BatchStop.Drained) Unit
    book = app.library.books.first().single { it.path.endsWith("fixed.epub") }
    scenario = ActivityScenario.launch(MainActivity::class.java)
    vm = awaitViewModel()
    open()
  }

  @After fun tearDown() {
    scenario.onActivity { vm.closeReader() }
    scenario.close()
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test fun `a tap on a link follows it`() {
    val (x, y) = screenCenterOf("to-last")
    tap(x, y)
    awaitCondition("the link landed on page 3") { currentHref() == "p2.xhtml" }
    assertFalse("following a link does not show the menu", vm.reader.ui.value.chrome)
  }

  @Test fun `a tap away from a link shows the menu and stays on the page`() {
    val (x, y) = screenCenterOf("plain")
    tap(x, y)
    awaitCondition("the menu shows") { vm.reader.ui.value.chrome }
    Thread.sleep(500)
    assertEquals("still on page 1", "p0.xhtml", currentHref())
  }

  @Test fun `a swipe turns the page both ways`() {
    val (_, y) = screenCenterOf("plain")
    swipe(fromX = 950f, toX = 130f, y = y)
    awaitCondition("a left swipe goes to page 2") { currentHref() == "p1.xhtml" }
    swipe(fromX = 130f, toX = 950f, y = y)
    awaitCondition("a right swipe comes back to page 1") { currentHref() == "p0.xhtml" }
  }

  // ── fixture ───────────────────────────────────────────────────────────────

  private fun writeBook(file: File) {
    val head = { p: Int -> """<title>Page $p</title><meta name="viewport" content="width=600, height=800"/>""" }
    val block = "display:block;position:absolute;left:150px;width:300px;height:120px;font-size:40px"
    val resources = (0..2).map { p ->
      val body = if (p == 0) {
        """<div style="position:relative;width:600px;height:800px"><a id="to-last" href="p2.xhtml" style="$block;top:120px">To page 3</a>""" +
          """<p id="plain" style="$block;top:480px;margin:0">Plain text</p></div>"""
      } else {
        """<div style="position:relative;width:600px;height:800px"><p id="plain" style="$block;top:480px;margin:0">Page ${p + 1}</p></div>"""
      }
      FixtureResource("p$p.xhtml", body, head(p))
    }
    EpubFixtures.write(file, resources, (0..2).map { p -> FixtureToc("p$p.xhtml", null, "Page ${p + 1}") }, fixedLayout = true)
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Where [id] on the shown page is on the screen: its box from the page, scaled by the page's zoom. */
  private fun screenCenterOf(id: String): Pair<Float, Float> {
    var webView: R2BasicWebView? = null
    awaitCondition("the page's WebView") {
      scenario.onActivity { activity -> webView = findShownPage(activity.window.decorView) }
      webView != null
    }
    val latch = CountDownLatch(1)
    var json: String? = null
    val script = """(function(){var r=document.getElementById('$id').getBoundingClientRect();var v=window.visualViewport;
      return JSON.stringify({x:r.left+r.width/2-(v?v.offsetLeft:0),y:r.top+r.height/2-(v?v.offsetTop:0),w:v?v.width:window.innerWidth});})()"""
    var location = IntArray(2)
    var width = 0
    scenario.onActivity {
      val page = webView!!
      page.getLocationOnScreen(location)
      width = page.width
      page.evaluateJavascript(script) { json = it; latch.countDown() }
    }
    assertTrue("the page answered", latch.await(10, TimeUnit.SECONDS))
    val box = JSONObject(JSONTokener(json).nextValue() as String)
    val scale = width / box.getDouble("w")
    return (location[0] + box.getDouble("x") * scale).toFloat() to (location[1] + box.getDouble("y") * scale).toFloat()
  }

  private fun findShownPage(view: View): R2BasicWebView? {
    if (view is R2BasicWebView && view.isShown && view.width > 0) {
      val onScreen = IntArray(2).also(view::getLocationOnScreen)
      if (onScreen[0] >= 0 && onScreen[0] < view.resources.displayMetrics.widthPixels) return view
    }
    if (view is ViewGroup) for (i in 0 until view.childCount) findShownPage(view.getChildAt(i))?.let { return it }
    return null
  }

  private fun tap(x: Float, y: Float) {
    val down = SystemClock.uptimeMillis()
    send(down, down, MotionEvent.ACTION_DOWN, x, y)
    send(down, down + 60, MotionEvent.ACTION_UP, x, y)
  }

  private fun swipe(fromX: Float, toX: Float, y: Float) {
    val down = SystemClock.uptimeMillis()
    var t = down
    send(down, t, MotionEvent.ACTION_DOWN, fromX, y)
    for (i in 1..12) { t += 16; send(down, t, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / 12, y) }
    send(down, t + 16, MotionEvent.ACTION_UP, toX, y)
  }

  private fun send(downTime: Long, time: Long, action: Int, x: Float, y: Float) {
    val e = MotionEvent.obtain(downTime, time, action, x, y, 0)
    instrumentation.sendPointerSync(e)
    e.recycle()
  }

  private fun currentHref(): String = navigator().currentLocator.value.href.toString().substringAfterLast('/')

  private fun awaitViewModel(): QuireViewModel {
    var found: QuireViewModel? = null
    awaitCondition("view model") {
      scenario.onActivity { activity -> found = runCatching { ViewModelProvider(activity)[QuireViewModel::class.java] }.getOrNull() }
      found != null
    }
    return found!!
  }

  private fun open() {
    scenario.onActivity { vm.read(book.id) }
    awaitCondition("reader ready") { vm.readerLoad is ReaderLoad.Ready }
    awaitCondition("whole-book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
    awaitCondition("on page 1") { currentHref() == "p0.xhtml" }
    Thread.sleep(1_000)
  }

  private fun session(): ReaderSession = (vm.readerLoad as ReaderLoad.Ready).session

  private fun navigator(): EpubNavigatorFragment = session().navigator ?: error("no navigator")

  private fun awaitCondition(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }
}
