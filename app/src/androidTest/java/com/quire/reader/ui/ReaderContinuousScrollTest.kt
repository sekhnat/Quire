package com.quire.reader.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.MainActivity
import com.quire.reader.QuireApplication
import com.quire.reader.data.Book
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.index.EpubFixtures
import com.quire.reader.reader.ReaderSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The real reader in scroll mode against the deterministic continuous-scroll fixture: chapters beyond the old
 * three-chapter window are independently addressable (content identity, distinct fragments, duplicate IDs across
 * chapters) through the real publication-serving path. Geometry, seams and selection are covered by the later
 * scroll tasks; this file carries the "which chapter is this" contract.
 *
 * Books come from the folder given as the `booksDir` instrumentation argument, or, without it, from the generated
 * scroll fixture. Screenshots go to `Download/quire-target-tests` when `screenshots=true` is passed.
 * Run only under the `.dbtest` application id, never over the installed app: it indexes and opens books.
 */
class ReaderContinuousScrollTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private val args = InstrumentationRegistry.getArguments()
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var book: Book

  /** Outer viewport height in CSS px, reported by the frames alongside their own positions. */
  private var viewportHeightPx = 0.0

  @Before fun setUp() = runBlocking {
    app.settings.setOnboardingDone(true)
    val dir = args.getString("booksDir") ?: generatedLibrary()
    // Library isolation: drop folders other test classes left behind, so "largest book"
    // is always the scroll fixture.
    app.library.folders.first().filter { File(it.path).canonicalPath != File(dir).canonicalPath }.forEach { app.library.removeFolder(it.id) }
    app.library.addFolder(dir)
    app.library.rescan()
    while (app.indexer.runBatch(System.currentTimeMillis() + 120_000).stop != com.quire.reader.data.index.BatchStop.Drained) Unit
    // The scroll fixture is the only (and largest) book of the generated folder.
    book = app.library.books.first().maxBy { it.sizeBytes }
    scenario = ActivityScenario.launch(MainActivity::class.java)
    vm = awaitViewModel()
  }

  @After fun tearDown() {
    runBlocking { app.library.clearBookPrefs(book.id) }
    scenario.onActivity { vm.closeReader() }
    scenario.close()
  }

  private fun generatedLibrary(): String {
    val dir = File(app.filesDir, "scroll-test-books").apply { deleteRecursively(); mkdirs() }
    EpubFixtures.writeScrollBook(File(dir, "scroll-fixture.epub"))
    return dir.path
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test fun `the whole book is addressable in scroll mode beyond the old three-chapter window`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val session = session()

    // Every chapter answers with its own heading text, addressed through its original href.
    val headings = chapterHeadings(session)
    for (c in 0..5) {
      assertEquals("chapter $c heading", EpubFixtures.scrollHeading(c), headings[c])
    }
  }

  @Test fun `two toc fragments resolve to their own authored targets`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val session = session()

    val offsetA = offsetForFragment(session, href(1), "target-a")
    val offsetB = offsetForFragment(session, href(2), "target-b")
    assertTrue("target-a offset $offsetA", offsetA > 0)
    assertTrue("target-b offset $offsetB", offsetB > 0)
    // Each target sits at its own text, not at the start of the resource.
    assertEquals("Target A lives in the first long chapter of the scroll fixture.", textAtFragment(session, href(1), "target-a"))
    assertEquals("Target B lives in the second long chapter of the scroll fixture.", textAtFragment(session, href(2), "target-b"))
  }
  @Test fun `a toc fragment deep inside a resource is scrolled into view`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val session = session()
    awaitSettledSurface(session, href(1))

    // Both TOC entries target the same resource: one at its first paragraph, one near its
    // end. Each jump must land on its own authored position, not on the resource start.
    jumpToToc(session, "Deep target A")
    val deep = awaitTargetInView(session, href(1), "target-deep")
    assertTrue("deep target visible at ${deep.first} of ${deep.second}", deep.first in 0.0..(deep.second / 2))
    val shallowBelow = awaitVisibleTop(session, href(1), "target-a")
    assertTrue("the shallow fragment must be above the deep landing, at $shallowBelow", shallowBelow < 0)

    jumpToToc(session, "Target A")
    val shallow = awaitTargetInView(session, href(1), "target-a")
    assertTrue("shallow target visible at ${shallow.first} of ${shallow.second}", shallow.first in 0.0..(shallow.second / 2))
    val deepBelow = awaitVisibleTop(session, href(1), "target-deep")
    assertTrue("the deep fragment must be below the shallow landing, at $deepBelow", deepBelow > shallow.second)
  }

  @Test fun `scrolling across a chapter seam reports the visible resource and progression`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val session = session()
    awaitSettledSurface(session, href(1))

    // A known start, whatever position the previous test in this class left behind: the short
    // single-paragraph first chapter owns the reading position, then reader scrolling (not a
    // programmatic jump) must move the reported locator across the seam into a later resource.
    session.goToProgress(0f)
    awaitCondition("the book-start locator, currently ${session.current.value?.href}") { reportedChapter(session.current.value) == 0 }

    flingUp()

    // Reader scrolling (no programmatic jump) must move the reported locator into a later
    // resource, with that resource's own progression.
    awaitCondition("a locator past the first chapter, currently ${session.current.value?.href}") { (reportedChapter(session.current.value) ?: 0) > 0 }
    val locator = session.current.value!!
    val reported = reportedChapter(locator) ?: error("locator outside the fixture: ${locator.href}")
    val progression = locator.locations.progression ?: 0.0
    assertTrue("progression $progression in chapter $reported", progression > 0.0 && progression < 1.0)
    // The reported resource is the one on screen: its heading has scrolled above the
    // viewport while the next chapter's heading is still below it.
    val viewport = awaitVisibleTop(session, locator.href, "h$reported")
    assertTrue("chapter $reported heading at $viewport", viewport <= 0)
    if (reported < 5) {
      val nextHeading = awaitVisibleTop(session, href(reported + 1), "h${reported + 1}")
      assertTrue("chapter ${reported + 1} heading at $nextHeading", nextHeading > 0)
    }

    // Leaving the reader persists the position the reader actually reached.
    Thread.sleep(600)
    scenario.onActivity { vm.closeReader() }
    val saved = runBlocking { app.library.readingState(book.id) }!!
    assertTrue("saved progress ${saved.progress} after scrolling into chapter $reported", saved.progress > 0f)
  }


  @Test fun `duplicate ids in different chapters resolve to their own chapter`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val session = session()

    assertEquals(
      "The shared id of chapter two, which must not be confused with chapter four's.",
      textAtId(session, href(2), "shared"),
    )
    assertEquals(
      "The shared id of chapter four, which must not be confused with chapter two's.",
      textAtId(session, href(4), "shared"),
    )
  }

  @Test fun `relative assets and links resolve against the original resource`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val session = session()

    // The relative image of chapter four loaded through the publication server.
    assertEquals(1, imageCount(session, href(4)))
    // The relative link of chapter four resolves (against the resource's own base) to
    // the first chapter's served URL, not to a chapter-four sibling or an error page.
    val target = linkTarget(session, href(4), "rel-link")
    assertTrue("relative link resolved to $target", target.endsWith("c0.xhtml") && target.contains("readium_package"))
  }

  @Test fun `a viewport sized image is one reader viewport tall`() {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.read(book.id) }
    val session = session()
    awaitSettledSurface(session, href(1))

    // The chapter holds two `height: 100vh` images. Their viewport is the reader's, not the
    // frame's own expanded box: otherwise each image would grow to the chapter's height and
    // the chapter would be laid out (and clipped) far beyond its measured content.
    val json = probe(session, href(1), "tall1") ?: error("no viewport sized image in chapter one")
    val imageHeight = json.optDouble("elementHeight", -1.0)
    val viewport = json.optDouble("viewport", 0.0)
    val frameHeight = json.optDouble("frameH", 0.0)
    val contentHeight = json.optDouble("docH", 0.0)
    assertTrue("image $imageHeight against a ${viewport}px reader viewport", kotlin.math.abs(imageHeight - viewport) <= 2.0)
    assertTrue("image $imageHeight against a ${frameHeight}px frame", imageHeight < frameHeight)
    assertTrue(
      "chapter content $contentHeight in a ${frameHeight}px frame",
      kotlin.math.abs(contentHeight - frameHeight) <= 2.0,
    )
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private fun href(chapter: Int) = org.readium.r2.shared.util.Url("c$chapter.xhtml")!!

  /** The fixture chapter a reported locator belongs to, by its original href, or null. */
  private fun reportedChapter(locator: Locator?): Int? {
    val href = locator?.href?.toString() ?: return null
    return (0..5).firstOrNull { href.endsWith("c$it.xhtml") }
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
    // Whole-book readiness: the scroll surface is only asked for content once every chapter is prepared.
    awaitCondition("whole-book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun session(): ReaderSession = (vm.reader.value as ReaderLoad.Ready).session

  private fun chapterHeadings(session: ReaderSession): List<String> = runBlocking {
    (0..5).map { c ->
      script(session, href(c)) { nav, h ->
        nav.evaluateJavascript(
          "JSON.stringify({heading:(document.getElementById('h$c')||{}).textContent||'', paragraphs:document.getElementsByTagName('p').length})",
          h,
        )
      }
    }.map { raw ->
      val json = JSONTokener(JSONTokener(raw).nextValue().toString()).nextValue() as org.json.JSONObject
      json.getString("heading").trim()
    }
  }

  private fun textAtFragment(session: ReaderSession, h: org.readium.r2.shared.util.Url, id: String): String = runBlocking {
    textOf(script(session, h) { nav, href -> nav.evaluateJavascript(elementScript(id), href) })
  }

  private fun textAtId(session: ReaderSession, h: org.readium.r2.shared.util.Url, id: String): String = runBlocking {
    textOf(script(session, h) { nav, href -> nav.evaluateJavascript(elementScript(id), href) })
  }

  private fun elementScript(id: String) =
    "JSON.stringify({text:(document.getElementById('$id')||{}).textContent||''})"

  private fun textOf(raw: String?): String {
    val json = JSONTokener(JSONTokener(raw ?: "{}").nextValue().toString()).nextValue() as org.json.JSONObject
    return json.optString("text").trim()
  }

  private fun offsetForFragment(session: ReaderSession, h: org.readium.r2.shared.util.Url, id: String): Int = runBlocking {
    val raw = script(session, h) { nav, href -> nav.evaluateJavascript(offsetScript(id), href) } ?: return@runBlocking 0
    // Unwrap JSON string layers until an object appears; the wrapper encodes the
    // frame's own string result.
    var v: Any = JSONTokener(raw).nextValue()
    var guard = 0
    while (v is String && guard++ < 4) v = JSONTokener(v).nextValue()
    val json = v as? org.json.JSONObject ?: return@runBlocking 0
    json.optDouble("top", 0.0).toInt()
  }

  private fun offsetScript(id: String) =
    "JSON.stringify({top:(function(){var el=document.getElementById('$id'); if(!el) return 0; return el.getBoundingClientRect().top;})()})"

  private fun imageCount(session: ReaderSession, h: org.readium.r2.shared.util.Url): Int = runBlocking {
    JSONTokener(script(session, h) { nav, href -> nav.evaluateJavascript("document.getElementById('pic').naturalWidth", href) } ?: "0")
      .nextValue().toString().toIntOrNull() ?: 0
  }.let { if (it > 0) 1 else 0 }

  private fun linkTarget(session: ReaderSession, h: org.readium.r2.shared.util.Url, id: String): String = runBlocking {
    JSONTokener(script(session, h) { nav, href -> nav.evaluateJavascript("document.getElementById('$id').href", href) } ?: "\"\"").nextValue().toString()
  }


  /** Jumps through the TOC entry titled [title], exactly as the contents sheet does. */
  private fun jumpToToc(session: ReaderSession, title: String) {
    val entry = session.toc.firstOrNull { it.title == title } ?: error("no TOC entry titled $title")
    session.go(entry.link)
  }

  /**
   * Reads the frame's own place in the outer document: the element's position and the outer
   * geometry around it. The frame is same-origin with the shell, so it can report all of it.
   */
  private fun probe(session: ReaderSession, h: org.readium.r2.shared.util.Url, id: String): JSONObject? {
    val js = "(function(){" +
      " var el = document.getElementById(${JSONObject.quote(id)});" +
      " if (!el) return null;" +
      " var frameEl = window.frameElement;" +
      " var local = el.getBoundingClientRect().top + window.pageYOffset;" +
      " var frameTop = frameEl.getBoundingClientRect().top + window.parent.pageYOffset;" +
      " return JSON.stringify({ top: frameTop + local - window.parent.pageYOffset," +
      " elementHeight: el.getBoundingClientRect().height," +
      " viewport: window.parent.innerHeight, frameTop: frameTop, local: local," +
      " winW: window.innerWidth, docH: document.documentElement.scrollHeight," +
      " frameH: frameEl.getBoundingClientRect().height," +
      " outerY: window.parent.pageYOffset, h: window.parent.document.documentElement.scrollHeight }); })()"
    val raw = script(session, h) { nav, served -> nav.evaluateJavascript(js, served) } ?: return null
    var v: Any = JSONTokener(raw).nextValue()
    var guard = 0
    while (v is String && guard++ < 4) v = JSONTokener(v).nextValue()
    return v as? JSONObject
  }

  /** Where the element with [id] sits in the outer viewport (negative when scrolled above it). */
  private fun visibleTop(session: ReaderSession, h: org.readium.r2.shared.util.Url, id: String): Double? {
    val json = probe(session, h, id) ?: return null
    viewportHeightPx = json.optDouble("viewport", viewportHeightPx)
    return json.optDouble("top", 0.0)
  }

  /**
   * Waits until the reading surface has been laid out at a real size and its geometry has
   * settled: a book prepared while the reading screen was still measuring is re-measured
   * once the reader viewport exists, and a jump issued in between would use stale geometry.
   */
  private fun awaitSettledSurface(session: ReaderSession, h: org.readium.r2.shared.util.Url, id: String = "h1", timeoutMs: Long = 30_000) {
    var lastHeight = -1.0
    var stable = 0
    awaitCondition("the reading surface laid out and settled", timeoutMs) {
      val json = probe(session, h, id)
      val viewport = json?.optDouble("viewport", 0.0) ?: 0.0
      val width = json?.optDouble("winW", 0.0) ?: 0.0
      val frameHeight = json?.optDouble("frameH", -1.0) ?: -1.0
      val contentHeight = json?.optDouble("docH", -1.0) ?: -1.0
      // The frame's box holds its content (no clipped or inflated chapter), the reader viewport
      // exists, and the frame height has stopped moving.
      val consistent = viewport > 0.0 && width > 0.0 && frameHeight > 0.0 &&
        contentHeight > 0.0 && kotlin.math.abs(contentHeight - frameHeight) <= 2.0
      val settled = consistent && frameHeight == lastHeight
      lastHeight = frameHeight
      stable = if (settled) stable + 1 else 0
      settled && stable >= 2
    }
  }


  private fun awaitVisibleTop(session: ReaderSession, h: org.readium.r2.shared.util.Url, id: String, timeoutMs: Long = 15_000): Double {
    val deadline = System.currentTimeMillis() + timeoutMs
    var found: Double? = null
    while (System.currentTimeMillis() < deadline) {
      found = visibleTop(session, h, id)
      if (found != null) return found
      Thread.sleep(150)
    }
    throw AssertionError("$id was never measurable in ${h.removeFragment()}")
  }

  /** Waits until [id] is scrolled into the top half of the viewport, and returns its top and the viewport height. */
  private fun awaitTargetInView(session: ReaderSession, h: org.readium.r2.shared.util.Url, id: String, timeoutMs: Long = 15_000): Pair<Double, Double> {
    val deadline = System.currentTimeMillis() + timeoutMs
    var top: Double? = null
    while (System.currentTimeMillis() < deadline) {
      top = visibleTop(session, h, id)
      val measured = top
      if (measured != null && measured >= -2.0 && measured < viewportHeightPx / 2) return measured to viewportHeightPx
      Thread.sleep(150)
    }
    throw AssertionError("$id at $top of a ${viewportHeightPx}px viewport, not scrolled into view")
  }

  /**
   * A real upward drag on the reading surface: no programmatic jump is involved, so only the
   * surface's own scroll reporting can move the navigator's locator.
   */
  private fun flingUp() {
    val start = android.os.SystemClock.uptimeMillis()
    var t = start
    fun send(action: Int, y: Float) {
      val e = android.view.MotionEvent.obtain(0, t, action, 540f, y, 0)
      instrumentation.sendPointerSync(e)
      e.recycle()
      t += 16
    }
    send(android.view.MotionEvent.ACTION_DOWN, 1700f)
    for (i in 1..20) send(android.view.MotionEvent.ACTION_MOVE, 1700f - i * 60f)
    send(android.view.MotionEvent.ACTION_UP, 500f)
    Thread.sleep(1_500)
  }
  /** Runs [block] against the navigator and the served href of [h], once the whole book is prepared. */
  private fun script(
    session: ReaderSession,
    h: org.readium.r2.shared.util.Url,
    block: suspend (com.quire.reader.navigator.epub.EpubNavigatorFragment, org.readium.r2.shared.util.Url) -> String?,
  ): String? = runBlocking {
    val deadline = System.currentTimeMillis() + 30_000
    var nav: com.quire.reader.navigator.epub.EpubNavigatorFragment? = null
    // Preference invalidation may rebuild the surface; wait for the current one each time.
    while (System.currentTimeMillis() < deadline) {
      nav = session.navigator
      if (nav != null && runCatching { nav.awaitWholeBookReadiness() }.getOrDefault(false)) break
      kotlinx.coroutines.delay(150)
    }
    val current = nav ?: error("no navigator")
    val served = current.servedUrlFor(h) ?: error("no served url for $h")
    block(current, served)
  }

  private fun awaitCondition(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }
}
