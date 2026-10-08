package com.quire.reader.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scroll shell's window policy (`QuireShellHost.planWindow` in assets/quire/continuous-scroll.js) on
 * synthetic books: which slots it loads first and which it unloads, for a reader at rest, flinging, turning
 * back, under memory pressure and over the weight budget. The script runs in a bare WebView; no book is
 * opened.
 *
 * Every book here is a column of one-viewport slots (800 CSS px) with the reader at the top of slot 10.
 */
class ShellWindowPolicyTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private lateinit var webView: WebView

  private val vp = 800.0
  private val y = 10 * vp
  private val mb = 1e6

  @SuppressLint("SetJavaScriptEnabled")
  @Before fun setUp() {
    val script = instrumentation.targetContext.assets.open("quire/continuous-scroll.js").use { String(it.readBytes()) }
    val loaded = CountDownLatch(1)
    instrumentation.runOnMainSync {
      webView = WebView(instrumentation.targetContext)
      webView.settings.javaScriptEnabled = true
      webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) = loaded.countDown()
      }
      webView.loadDataWithBaseURL("https://shell.invalid/", "<html><body><script>$script</script></body></html>", "text/html", "utf-8", null)
    }
    assertTrue("the shell script did not load", loaded.await(10, TimeUnit.SECONDS))
  }

  @After fun tearDown() {
    instrumentation.runOnMainSync { webView.destroy() }
  }

  // ── static: the fixed window ──────────────────────────────────────────────

  @Test fun `static mounts the fixed range around the viewport in book order`() {
    val plan = plan(book(40), policy = "static")
    // [y − 1.5vp, y + vp + 2.5vp] touches slots 8 to 13.
    assertEquals(listOf(8, 9, 10, 11, 12, 13), plan.mount)
    assertEquals(emptyList<Int>(), plan.unmount)
  }

  @Test fun `static unloads live slots outside the keep range and keeps the rest`() {
    val slots = book(40).apply {
      for (i in listOf(3, 5, 6, 16, 17)) this[i] = slot(state = "live")
    }
    val plan = plan(slots, policy = "static")
    // Keep range [y − 4vp, y + vp + 6vp] = slots 6 to 16.
    assertEquals(listOf(3, 5, 17), plan.unmount.sorted())
    assertEquals(listOf(8, 9, 10, 11, 12, 13), plan.mount)
  }

  @Test fun `static caps the live documents by dropping the farthest outside the mount range`() {
    val slots = book(40).apply {
      for (i in listOf(6, 7, 15, 16) + (8..13)) this[i] = slot(state = "live")
    }
    // Ten held, two over: 16 and 15 are the farthest from the viewport; 6 and 7 stay.
    val plan = plan(slots, policy = "static")
    assertEquals(listOf(15, 16), plan.unmount.sorted())
  }

  // ── adaptive: at rest ─────────────────────────────────────────────────────

  @Test fun `at rest the viewport loads first and then ahead with at most three loads at a time`() {
    val plan = plan(book(40))
    assertEquals(listOf(10, 11, 12, 13), plan.mount)
  }

  @Test fun `while the book is preparing the whole first window loads at once`() {
    assertEquals(listOf(10, 11, 12, 13, 9, 8), plan(book(40), preparing = true).mount)
  }

  @Test fun `a load already running counts against the loads at a time`() {
    val slots = book(40).apply { this[11] = slot(state = "loading") }
    assertEquals(listOf(10, 12, 13), plan(slots).mount)
  }

  @Test fun `a background measurement neither counts nor is touched`() {
    val slots = book(40).apply { this[30] = slot(state = "loading", bg = true) }
    val plan = plan(slots)
    assertEquals(listOf(10, 11, 12, 13), plan.mount)
    assertFalse(30 in plan.unmount)
  }

  // ── adaptive: flinging ────────────────────────────────────────────────────

  @Test fun `a fast fling loads around its predicted stop first and skips what it flies past`() {
    val slots = book(80).apply { for (i in 6..9) this[i] = slot(state = "live") }
    // 60 viewports a second, predicted to stop 10 viewports on, at the top of slot 20: due there in 0.4 s.
    val plan = plan(slots, velocity = 60 * vp, stop = y + 10 * vp)
    assertEquals(10, plan.mount[0])
    assertEquals(20, plan.mount[1])
    assertTrue("the slots around the stop come next: ${plan.mount}", plan.mount.drop(2).all { it in 19..21 })
    // At this speed the viewport passes slots 11–17 before a document could load there.
    assertTrue("loaded slots the fling flies past: ${plan.mount}", plan.mount.none { it in 11..17 })
    // Behind a fast fling the window shrinks to a quarter viewport, kept to three quarters.
    assertEquals(listOf(6, 7, 8), plan.unmount.sorted())
  }

  @Test fun `a fling still far from its stop does not load there yet`() {
    // 30 viewports a second, predicted to stop 40 viewports on: about five seconds away, and most flings are
    // caught well before. The lookahead past the slots it flies through loads instead.
    val plan = plan(book(80), velocity = 30 * vp, stop = y + 40 * vp)
    assertEquals(listOf(10, 18, 19, 20), plan.mount)
    // The fling's own timing decides when it is given: due in 0.8 s, the slots around the stop load.
    assertEquals(listOf(10, 50, 49, 51), plan(book(80), velocity = 30 * vp, stop = y + 40 * vp, stopIn = 800.0).mount)
    assertEquals(listOf(10, 18, 19, 20), plan(book(80), velocity = 30 * vp, stop = y + 40 * vp, stopIn = 1_500.0).mount)
  }

  @Test fun `a fling with no time to spare still loads the slots it will stop in`() {
    // Stopping inside slot 12: the slots up to the stop are needed when it ends, not passed.
    val plan = plan(book(40), velocity = 30 * vp, stop = y + 2.2 * vp)
    assertTrue("${plan.mount}", plan.mount.containsAll(listOf(10, 12)))
  }

  @Test fun `without a predicted stop nothing ahead is skipped`() {
    val plan = plan(book(40), velocity = 30 * vp)
    assertEquals(listOf(10, 11, 12, 13), plan.mount)
  }

  @Test fun `scrolling back leans the window the other way`() {
    val slots = book(40).apply { this[12] = slot(state = "live") }
    val plan = plan(slots, velocity = -30 * vp)
    assertEquals(listOf(10, 9, 8, 7), plan.mount)
    assertEquals(listOf(12), plan.unmount)
  }

  @Test fun `the last direction of travel holds when the reader stops`() {
    val plan = plan(book(40), direction = -1)
    assertEquals(listOf(10, 9, 8, 7), plan.mount)
  }

  // ── adaptive: weight ──────────────────────────────────────────────────────

  @Test fun `over the budget the heavy far document goes before the light near one`() {
    val slots = book(40).apply {
      this[11] = slot(state = "live"); this[12] = slot(state = "live")
      this[15] = slot(state = "live", weight = 50 * mb)
      this[16] = slot(state = "live", weight = 2 * mb)
    }
    val plan = plan(slots, budget = 40 * mb)
    assertEquals(listOf(15), plan.unmount)
  }

  @Test fun `a visible or pinned document is never refused whatever it weighs`() {
    val slots = book(40).apply {
      this[10] = slot(weight = 500 * mb)
      this[30] = slot(pins = 1)
      this[35] = slot(state = "live", pins = 1, weight = 100 * mb)
    }
    val plan = plan(slots)
    assertEquals(listOf(10, 30), plan.mount)
    assertEquals(emptyList<Int>(), plan.unmount)
  }

  @Test fun `a document that failed recently is not retried`() {
    val slots = book(40).apply { this[11] = slot(retry = false) }
    // Ahead holds only 12 and 13 at rest; the third load goes to the nearest slot behind.
    assertEquals(listOf(10, 12, 13, 9), plan(slots).mount)
  }

  // ── adaptive: memory pressure ─────────────────────────────────────────────

  @Test fun `each pressure tier shrinks the window`() {
    val slots = book(40).apply { for (i in listOf(7, 8, 9, 11, 12, 13, 14)) this[i] = slot(state = "live") }
    // Reduced: behind 0.5vp kept to 2vp, ahead 1.5vp kept to 3vp, five documents.
    val reduced = plan(slots, tier = 1)
    assertEquals(listOf(10), reduced.mount)
    assertEquals(listOf(7, 13, 14), reduced.unmount.sorted())
    // Minimal: the viewport and a sliver either side, three documents.
    val minimal = plan(slots, tier = 2)
    assertEquals(listOf(10), minimal.mount)
    assertEquals(listOf(7, 8, 12, 13, 14), minimal.unmount.sorted())
  }

  @Test fun `the minimal tier stops background measurement`() {
    val slots = book(40).apply { this[30] = slot(state = "loading", bg = true) }
    assertEquals(listOf(30), plan(slots, tier = 2).unmount)
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private class Plan(val mount: List<Int>, val unmount: List<Int>)

  private fun slot(state: String = "placeholder", pins: Int = 0, weight: Double = 4 * mb, retry: Boolean = true, bg: Boolean = false, height: Double = vp) =
    JSONObject().put("height", height).put("state", state).put("pins", pins).put("weight", weight).put("retry", retry).put("bg", bg)

  private fun book(slots: Int) = MutableList(slots) { slot() }

  private fun plan(
    slots: List<JSONObject>,
    policy: String = "adaptive",
    tier: Int = 0,
    velocity: Double = 0.0,
    direction: Int = 1,
    stop: Double? = null,
    stopIn: Double? = null,
    loadMs: Double = 300.0,
    budget: Double = 192 * mb,
    preparing: Boolean = false,
  ): Plan {
    val input = JSONObject()
      .put("slots", JSONArray(slots)).put("y", y).put("vp", vp).put("policy", policy).put("tier", tier)
      .put("velocity", velocity).put("direction", direction).put("stop", stop ?: JSONObject.NULL).put("stopIn", stopIn ?: JSONObject.NULL)
      .put("loadMs", loadMs).put("budget", budget).put("preparing", preparing)
    val raw = evaluate("JSON.stringify(QuireShellHost.planWindow($input))")
    val result = JSONObject(JSONTokener(raw).nextValue() as String)
    fun ints(name: String) = result.getJSONArray(name).let { a -> List(a.length()) { a.getInt(it) } }
    return Plan(ints("mount"), ints("unmount"))
  }

  private fun evaluate(script: String): String {
    var result: String? = null
    val done = CountDownLatch(1)
    instrumentation.runOnMainSync { webView.evaluateJavascript(script) { result = it; done.countDown() } }
    assertTrue("no answer from the shell script", done.await(10, TimeUnit.SECONDS))
    return result ?: "null"
  }
}
