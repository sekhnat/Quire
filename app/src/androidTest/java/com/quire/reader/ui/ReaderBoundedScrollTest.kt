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
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import com.quire.reader.navigator.epub.PressureTiers
import com.quire.reader.reader.ReaderSession
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
import org.readium.r2.shared.util.Url
import java.io.File

/**
 * Scroll mode keeps only the chapters near the viewport alive (bounded-scroll-window): a book with far more
 * chapters than the live window still opens, jumps to a chapter nobody has measured yet, lands on its target and
 * stays put while the background measurement corrects the heights above it, and a chapter outside the window is
 * loaded on demand when something addresses it.
 *
 * Run only under the `.dbtest` application id, never over the installed app: it indexes and opens books.
 */
class ReaderBoundedScrollTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var book: Book

  /** The most documents the shell may hold: the live window plus the background measurement's own frames. */
  private val maxHeld = 8 + 2

  @Before fun setUp() = runBlocking {
    app.settings.setOnboardingDone(true)
    val dir = File(app.filesDir, "bounded-scroll-test-books").apply { deleteRecursively(); mkdirs() }
    EpubFixtures.writeLongBook(File(dir, "long-fixture.epub"))
    app.library.folders.first().filter { File(it.path).canonicalPath != dir.canonicalPath }.forEach { app.library.removeFolder(it.id) }
    app.library.addFolder(dir.path)
    app.library.rescan()
    book = app.library.books.first().maxBy { it.sizeBytes }
    scenario = ActivityScenario.launch(MainActivity::class.java)
    vm = awaitViewModel()
  }

  @After fun tearDown() {
    runBlocking { app.library.clearBookPrefs(book.id) }
    scenario.onActivity { vm.closeReader() }
    scenario.close()
  }

  @Test fun `a jump to a far chapter leaves only a bounded window of documents live`() {
    open()
    val session = session()
    val nav = session.navigator!!

    session.go(session.toc.first { it.title == EpubFixtures.longHeading(30) }.link)
    awaitCondition("chapter 30 under the viewport") { visibleTop(session, 30, "h30")?.let { it > -2000 && it < 2000 } == true }

    // The background pass measures all 40 chapters over the next seconds; the shell must never hold
    // more than the window plus its own measurement frames at any moment of it.
    var worst = 0
    val until = System.currentTimeMillis() + 8_000
    while (System.currentTimeMillis() < until) {
      worst = maxOf(worst, runBlocking { nav.continuousBook!!.liveFrameCount() })
      Thread.sleep(100)
    }
    assertTrue("held up to $worst documents, more than the $maxHeld allowed", worst in 1..maxHeld)

    // The start of the book is far outside the window and was unloaded.
    val first = nav.readingOrder.first().url()
    assertFalse("chapter 0 should have been unloaded", nav.continuousBook!!.runnerFor(first)!!.isLoaded.value)
    assertTrue("chapter 30 should be live", nav.continuousBook!!.runnerFor(nav.readingOrder[30].url())!!.isLoaded.value)
  }

  @Test fun `a far toc fragment lands on its target and holds still while heights are measured`() {
    open()
    val session = session()

    session.go(session.toc.first { it.title == "Far target" }.link)
    val landed = awaitTargetInView(session, EpubFixtures.LONG_TARGET_CHAPTER, "far-target")

    // Heights above the reader change as the background pass measures them; the text being read must not move.
    Thread.sleep(1_500)
    val before = visibleTop(session, EpubFixtures.LONG_TARGET_CHAPTER, "far-target")!!
    Thread.sleep(6_000)
    val after = visibleTop(session, EpubFixtures.LONG_TARGET_CHAPTER, "far-target")!!
    assertTrue("the target drifted from $before to $after (landed at $landed)", kotlin.math.abs(after - before) <= 2.0)
  }

  @Test fun `a font size change keeps the reader on the same text`() {
    open()
    val session = session()

    session.go(session.toc.first { it.title == "Far target" }.link)
    awaitTargetInView(session, EpubFixtures.LONG_TARGET_CHAPTER, "far-target")
    Thread.sleep(1_500)

    scenario.onActivity { vm.reader.updatePrefs { it.copy(fontSize = 25) } }
    Thread.sleep(2_000)

    // The text is larger now, so the same paragraph is taller, but it must still be what the reader sees:
    // within a screen of where it was, not sent back to the start of the chapter or somewhere else.
    var top: Double? = null
    awaitCondition("the target near the viewport after the reflow", 30_000) {
      top = visibleTop(session, EpubFixtures.LONG_TARGET_CHAPTER, "far-target")
      top?.let { it > -1_200.0 && it < 1_200.0 } == true
    }
    assertTrue("after the font size change the target is at $top", top != null)
  }

  @Test fun `a jump to a chapter start reports that chapter and not the end of the one before`() {
    open()
    val session = session()

    for (chapter in listOf(12, 25, 7)) {
      session.go(session.toc.first { it.title == EpubFixtures.longHeading(chapter) }.link)
      awaitCondition("chapter $chapter under the viewport") { visibleTop(session, chapter, "h$chapter")?.let { it > -2.0 && it < 100.0 } == true }
      // Let the scroll listener and the geometry batches publish the position, then check what is reported.
      Thread.sleep(800)
      val reported = session.current.value?.href?.toString().orEmpty()
      assertTrue("jumped to chapter $chapter but the reader reports $reported", reported.endsWith("l$chapter.xhtml"))
    }
  }

  @Test fun `a chapter that was unloaded and loaded again shows all of its text`() {
    open()
    val session = session()

    // Let every chapter be measured once, so a later load of chapter 5 knows its height already.
    session.go(session.toc.first { it.title == EpubFixtures.longHeading(5) }.link)
    awaitTargetInView(session, 5, "h5")
    awaitCondition("every chapter measured", 60_000) { runBlocking { session.navigator!!.continuousBook!!.measuredFrameCount() } >= 40 }

    // Far away and back: chapter 5 is unloaded in between, then loaded again.
    session.go(session.toc.first { it.title == EpubFixtures.longHeading(35) }.link)
    awaitTargetInView(session, 35, "h35")
    Thread.sleep(1_000)
    assertFalse("chapter 5 should have been unloaded", session.navigator!!.continuousBook!!.runnerFor(session.navigator!!.readingOrder[5].url())!!.isLoaded.value)
    session.go(session.toc.first { it.title == EpubFixtures.longHeading(5) }.link)
    awaitTargetInView(session, 5, "h5")

    // The frame must be as tall as the chapter, not left in its small loading box with the rest clipped.
    var shown = ""
    awaitCondition("chapter 5 fully shown", 15_000) {
      val raw = script(session, 5) { nav, h -> nav.evaluateJavascript("JSON.stringify({frame: window.frameElement.getBoundingClientRect().height, doc: document.documentElement.scrollHeight})", h) }
      var v: Any = JSONTokener(raw ?: "null").nextValue()
      var guard = 0
      while (v is String && guard++ < 4) v = JSONTokener(v).nextValue()
      val json = v as? JSONObject
      shown = json?.toString().orEmpty()
      json != null && json.optDouble("frame") >= json.optDouble("doc") - 2.0
    }
    assertTrue("chapter 5 frame and document: $shown", shown.isNotEmpty())
  }

  @Test fun `a script addressed to a chapter outside the window loads it first`() {
    open()
    val session = session()
    val nav = session.navigator!!

    val last = nav.readingOrder.size - 1
    val runner = nav.continuousBook!!.runnerFor(nav.readingOrder[last].url())!!
    assertFalse("the last chapter starts outside the window", runner.isLoaded.value)

    val raw = script(session, last) { n, h -> n.evaluateJavascript("document.getElementById('h$last').textContent", h) }
    assertEquals(EpubFixtures.longHeading(last), JSONTokener(raw ?: "null").nextValue().toString().trim())
  }

  @Test fun `memory pressure shrinks the live window to the viewport and its release restores it`() {
    open()
    val session = session()
    val nav = session.navigator!!
    session.go(session.toc.first { it.title == EpubFixtures.longHeading(20) }.link)
    awaitTargetInView(session, 20, "h20")
    // Background measurement adds documents of its own; let it finish first.
    awaitCondition("every chapter measured", 60_000) { runBlocking { nav.continuousBook!!.measuredFrameCount() } >= 40 }

    scenario.onActivity { nav.simulateMemoryPressure(PressureTiers.Tier.Minimal) }
    var held = emptyList<Pair<Double, Double>>()
    awaitCondition("the window shrinks to the viewport", 5_000) {
      held = heldSlots(nav)
      // The minimal tier keeps three documents at most, all within a viewport above and a viewport and a half below.
      shellTier(nav) == 2 && held.size in 1..3 && held.all { (top, bottom) -> top < 2.5 && bottom > -1.0 }
    }
    assertTrue("the visible chapter stays loaded: $held", held.any { (top, bottom) -> top <= 0.0 && bottom > 0.0 })

    scenario.onActivity { nav.simulateMemoryPressure(null) }
    awaitCondition("the normal window is back", 5_000) { shellTier(nav) == 0 }
    assertTrue("the reader is still on chapter 20", visibleTop(session, 20, "h20") != null)
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** The slots holding a document, as their top and bottom in reader viewports from the viewport top. */
  private fun heldSlots(nav: EpubNavigatorFragment): List<Pair<Double, Double>> {
    val js = "JSON.stringify(Array.prototype.filter.call(document.querySelectorAll('.slot'), function (s) { return s.querySelector('iframe'); })" +
      ".map(function (s) { var r = s.getBoundingClientRect(); return [r.top / innerHeight, r.bottom / innerHeight]; }))"
    val raw = runBlocking { nav.continuousBook!!.evaluateShell(js) } ?: return emptyList()
    val list = org.json.JSONArray(JSONTokener(raw).nextValue() as String)
    return List(list.length()) { list.getJSONArray(it).let { r -> r.getDouble(0) to r.getDouble(1) } }
  }

  private fun shellTier(nav: EpubNavigatorFragment): Int? =
    runBlocking { nav.continuousBook!!.evaluateShell("QuireShellHost.pressureTier()") }?.trim()?.toIntOrNull()

  private fun href(chapter: Int) = Url("l$chapter.xhtml")!!

  private fun awaitViewModel(): QuireViewModel {
    var found: QuireViewModel? = null
    awaitCondition("view model") {
      scenario.onActivity { activity -> found = runCatching { ViewModelProvider(activity)[QuireViewModel::class.java] }.getOrNull() }
      found != null
    }
    return found!!
  }

  private fun open() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    scenario.onActivity { vm.read(book.id) }
    awaitCondition("reader ready") { vm.readerLoad is ReaderLoad.Ready }
    awaitCondition("book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun session(): ReaderSession = (vm.readerLoad as ReaderLoad.Ready).session

  /** Where the element with [id] in chapter [chapter] sits in the outer viewport (negative: scrolled above it), or null. */
  private fun visibleTop(session: ReaderSession, chapter: Int, id: String): Double? {
    val js = "(function(){" +
      " var el = document.getElementById(${JSONObject.quote(id)});" +
      " if (!el) return null;" +
      " var frameEl = window.frameElement;" +
      " var frameTop = frameEl.getBoundingClientRect().top;" +
      " return JSON.stringify({ top: frameTop + el.getBoundingClientRect().top, viewport: window.parent.innerHeight }); })()"
    val raw = script(session, chapter) { nav, served -> nav.evaluateJavascript(js, served) } ?: return null
    var v: Any = JSONTokener(raw).nextValue()
    var guard = 0
    while (v is String && guard++ < 4) v = JSONTokener(v).nextValue()
    return (v as? JSONObject)?.optDouble("top")
  }

  /** Waits until [id] is in the top half of the viewport and returns its top. */
  private fun awaitTargetInView(session: ReaderSession, chapter: Int, id: String, timeoutMs: Long = 30_000): Double {
    val deadline = System.currentTimeMillis() + timeoutMs
    var top: Double? = null
    while (System.currentTimeMillis() < deadline) {
      top = visibleTop(session, chapter, id)
      val measured = top
      if (measured != null && measured >= -2.0 && measured < 900.0) return measured
      Thread.sleep(200)
    }
    throw AssertionError("$id at $top, not scrolled into view")
  }

  private fun script(
    session: ReaderSession,
    chapter: Int,
    block: suspend (EpubNavigatorFragment, Url) -> String?,
  ): String? = runBlocking {
    val nav = session.navigator ?: error("no navigator")
    val served = nav.servedUrlFor(href(chapter)) ?: error("no served url for chapter $chapter")
    block(nav, served)
  }

  private fun awaitCondition(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }
}
