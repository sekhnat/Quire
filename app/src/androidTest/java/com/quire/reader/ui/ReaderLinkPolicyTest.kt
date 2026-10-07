package com.quire.reader.ui

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
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
import com.quire.reader.reader.ReaderSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

/**
 * The reader's navigation policy against a book of hostile links: web (http/https) links may
 * leave through a browser hand-off, every other scheme (intent, file, content, mailto,
 * javascript) must be rejected before any URL parsing — no launch, no in-reader load, no
 * crash (an opaque URI used to throw inside shouldOverrideUrlLoading) — and normal reader
 * behavior (internal links, the book surface itself) is unchanged.
 *
 * One hostile scheme per chapter, so a chapter frame damaged by a refused navigation cannot
 * poison the next case. Runs on the real reader through the real publication-serving path.
 * Run only under the `.dbtest` application id: it indexes and opens books.
 */
class ReaderLinkPolicyTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var book: Book

  /** Scheme -> blocking monitor over ACTION_VIEW launches for that data scheme. */
  private lateinit var monitors: Map<String, Instrumentation.ActivityMonitor>

  @Before fun setUp() = runBlocking {
    app.settings.setOnboardingDone(true)
    val dir = File(app.filesDir, "link-policy-books").apply { deleteRecursively(); mkdirs() }
    writeHostileBook(File(dir, "hostile.epub"))
    // Library isolation: other instrumentation classes add their own fixture folders.
    app.library.folders.first().filter { File(it.path).canonicalPath != dir.canonicalPath }.forEach { app.library.removeFolder(it.id) }
    app.library.addFolder(dir.path)
    app.library.rescan()
    while (app.indexer.runBatch(System.currentTimeMillis() + 120_000).stop != BatchStop.Drained) Unit
    book = app.library.books.first().single { it.path.endsWith("hostile.epub") }
    monitors = hostileSchemes.associateWith { scheme ->
      instrumentation.addMonitor(
        IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme(scheme) },
        Instrumentation.ActivityResult(0, null),
        true,
      )
    }
    scenario = ActivityScenario.launch(MainActivity::class.java)
    vm = awaitViewModel()
  }

  @After fun tearDown() {
    runBlocking { app.library.clearBookPrefs(book.id) }
    scenario.onActivity { vm.closeReader() }
    scenario.close()
    monitors.values.forEach { instrumentation.removeMonitor(it) }
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test fun `scripted navigation to hostile schemes cannot escape the scroll reader`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val session = session()

    // One chapter-frame `window.location` assignment per hostile scheme. Nothing may
    // launch externally (the monitors stay at zero for the forbidden schemes), nothing
    // may crash, and the book surface must keep reading afterwards.
    for ((scheme, chapter) in schemeChapters) {
      script(session, href(chapter)) { nav, served ->
        nav.evaluateJavascript("window.location.href = ${JSONObject.quote(hostileUrl(scheme))}", served)
      }
      Thread.sleep(700)
      assertEquals("reader alive after a $scheme navigation", Lifecycle.State.RESUMED, scenario.state)
      if (scheme !in webSchemes) assertEquals("no external launch for $scheme", 0, monitors.getValue(scheme).hits)
    }
    assertTrue(
      "local and app schemes never launch externally",
      monitors.filterKeys { it !in webSchemes }.all { it.value.hits == 0 },
    )

    // The reader survives intact: an in-reader jump still lands on its target.
    jumpByToc(session, "Internal target")
    awaitCondition("the internal target chapter reported as current") { reportedChapter(session.current.value) == internalTargetChapter }
  }

  @Test fun `tapped links follow the reader policy in paged mode`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Paged)) }
    open { vm.read(book.id) }
    val session = session()

    // A mailto tap: consumed without a crash (an opaque URI used to throw inside
    // shouldOverrideUrlLoading) and without an external launch.
    jumpByToc(session, "Hostile mailto")
    awaitCondition("on the mailto chapter") { reportedChapter(session.current.value) == schemeChapters.getValue("mailto") }
    click(session, href(schemeChapters.getValue("mailto")), "l-mailto")
    Thread.sleep(700)
    assertEquals("reader alive after a mailto tap", Lifecycle.State.RESUMED, scenario.state)
    assertEquals("no external launch for mailto", 0, monitors.getValue("mailto").hits)

    // A web tap: browser hand-off attempted (blocked by the monitor), book surface unchanged.
    jumpByToc(session, "Hostile https")
    awaitCondition("on the https chapter") { reportedChapter(session.current.value) == schemeChapters.getValue("https") }
    click(session, href(schemeChapters.getValue("https")), "l-https")
    awaitCondition("a browser hand-off was attempted for the https tap") { monitors.getValue("https").hits >= 1 }
    assertEquals("the book did not navigate in-reader for an external link", schemeChapters.getValue("https"), reportedChapter(session.current.value))

    // A normal internal tap still navigates the reader.
    jumpByToc(session, "Hostile internal")
    awaitCondition("on the internal-link chapter") { reportedChapter(session.current.value) == internalLinkChapter }
    click(session, href(internalLinkChapter), "l-internal")
    awaitCondition("the internal tap landed on its target chapter") { reportedChapter(session.current.value) == internalTargetChapter }
  }

  // ── fixture ───────────────────────────────────────────────────────────────

  private val webSchemes = listOf("http", "https")

  /** Scheme and the chapter index that carries its hostile link (chapter 0 is a plain start chapter). */
  private val schemeChapters = linkedMapOf(
    "http" to 1,
    "https" to 2,
    "intent" to 3,
    "file" to 4,
    "content" to 5,
    "mailto" to 6,
    "javascript" to 7,
  )
  private val internalLinkChapter = 8
  private val internalTargetChapter = 9
  private val hostileSchemes = schemeChapters.keys.toList()

  private fun hostileUrl(scheme: String) = when (scheme) {
    "http" -> "http://example.com/quire-hostile"
    "https" -> "https://example.com/quire-hostile"
    "intent" -> "intent://quire.example/page#Intent;scheme=https;end"
    "file" -> "file:///etc/hostname"
    "content" -> "content://media/external/images/media/1"
    "mailto" -> "mailto:hostile@example.com"
    "javascript" -> "javascript:1"
    else -> error("no hostile url for $scheme")
  }

  private fun writeHostileBook(file: File) {
    // Every chapter is padded to a few reader viewports: the last chapter of a too-short
    // book is not reachable by an outer scroll (the scroll clamps before its top), and a
    // jump there could never report it as the current chapter.
    fun filler(chapter: Int) = (1..30).joinToString("") { p ->
      "<p>Chapter $chapter paragraph $p of the link-policy fixture, watched by the quiet heron of the west field $chapter-$p.</p>"
    }
    val resources = buildList {
      add(
        FixtureResource(
          "c0.xhtml",
          """<h2 id="h0">Plain start chapter</h2>""" + filler(0),
          "<title>Policy 0</title>",
        )
      )
      for ((scheme, chapter) in schemeChapters) {
        add(
          FixtureResource(
            "c$chapter.xhtml",
            """<h2 id="h$chapter">Hostile $scheme</h2><p><a id="l-$scheme" href="${hostileUrl(scheme)}">the hostile $scheme link</a></p>""" + filler(chapter),
            "<title>Policy $chapter</title>",
          )
        )
      }
      add(
        FixtureResource(
          "c$internalLinkChapter.xhtml",
          """<h2 id="h$internalLinkChapter">Hostile internal</h2><p><a id="l-internal" href="c$internalTargetChapter.xhtml">to the internal target</a></p>""" + filler(internalLinkChapter),
          "<title>Policy $internalLinkChapter</title>",
        )
      )
      add(
        FixtureResource(
          "c$internalTargetChapter.xhtml",
          """<h2 id="h$internalTargetChapter">Internal target</h2>""" + filler(internalTargetChapter),
          "<title>Policy $internalTargetChapter</title>",
        )
      )
    }
    val toc = (0..internalTargetChapter).map { c -> FixtureToc("c$c.xhtml", null, tocTitle(c)) }
    EpubFixtures.write(file, resources, toc)
  }

  private fun tocTitle(c: Int) = when (c) {
    0 -> "Plain start chapter"
    internalLinkChapter -> "Hostile internal"
    internalTargetChapter -> "Internal target"
    else -> "Hostile ${schemeChapters.entries.first { it.value == c }.key}"
  }

  // ── harness (the shape of ReaderContinuousScrollTest's) ───────────────────

  private fun href(chapter: Int) = Url("c$chapter.xhtml")!!

  /** The fixture chapter a reported locator belongs to, by its original href, or null. */
  private fun reportedChapter(locator: Locator?): Int? {
    val href = locator?.href?.toString() ?: return null
    return (0..internalTargetChapter).firstOrNull { c -> href.endsWith("c$c.xhtml") }
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
    awaitCondition("reader ready") { vm.reader.value is ReaderLoad.Ready }
    awaitCondition("whole-book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun session(): ReaderSession = (vm.reader.value as ReaderLoad.Ready).session

  private fun jumpByToc(session: ReaderSession, title: String) {
    val entry = session.toc.firstOrNull { it.title == title } ?: error("no TOC entry titled $title")
    // The paged navigator's go() swaps pager fragments: a main-thread call.
    scenario.onActivity { session.go(entry.link) }
  }

  /** Runs [block] against the navigator and the served href of [h], once the whole book is prepared. */
  private fun script(
    session: ReaderSession,
    h: Url,
    block: suspend (com.quire.reader.navigator.epub.EpubNavigatorFragment, Url) -> String?,
  ): String? = runBlocking {
    val deadline = System.currentTimeMillis() + 30_000
    var nav: com.quire.reader.navigator.epub.EpubNavigatorFragment? = null
    while (System.currentTimeMillis() < deadline) {
      nav = session.navigator
      if (nav != null && runCatching { nav.awaitWholeBookReadiness() }.getOrDefault(false)) break
      kotlinx.coroutines.delay(150)
    }
    val current = nav ?: error("no navigator")
    val served = current.servedUrlFor(h) ?: error("no served url for $h")
    block(current, served)
  }

  private fun click(session: ReaderSession, h: Url, id: String): String? {
    return script(session, h) { nav, _ ->
      // Address the eval by the manifest-spelled original href: the paged branch of
      // evaluateJavascript matches the page fragments' original links exactly (the scroll
      // branch additionally maps served URLs; this keeps the helper correct on both).
      // The paged WebView must be driven from the main thread, unlike the scroll surface's
      // frame runner, which hops internally.
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        nav.evaluateJavascript("document.getElementById('$id').click()", Url("OEBPS/${h.toString()}")!!)
      }
    }
  }

  private fun awaitCondition(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }
}
