package com.quire.reader.ui

import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.MainActivity
import com.quire.reader.QuireApplication
import com.quire.reader.data.Book
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.data.index.EpubFixtures
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.BatchStop
import com.quire.reader.data.index.Snippet
import com.quire.reader.data.index.FixtureResource
import com.quire.reader.data.index.FixtureToc
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import com.quire.reader.reader.ReaderSession
import com.quire.reader.reader.STALE_TARGET_MESSAGE
import com.quire.reader.reader.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import androidx.room.useReaderConnection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.Url
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Drives the real reader through the view-model's text-search entry points on a device: opens a book at an indexed
 * passage, checks from the page itself what is underlined, and covers the fallback, the stale target and library-search mode.
 *
 * Books come from the folder given as the `booksDir` instrumentation argument (EPUBs already on the device), or, without
 * it, from one generated novel. Screenshots go to `Download/quire-target-tests` when `screenshots=true` is passed.
 * Run only under the `.dbtest` application id, never over the installed app: it indexes and opens books.
 */
class ReaderTargetTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private val args = InstrumentationRegistry.getArguments()
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var book: Book
  private val shots = args.getString("screenshots") == "true"

  @Before fun setUp() = runBlocking {
    app.settings.setOnboardingDone(true)
    val dir = args.getString("booksDir") ?: generatedLibrary()
    // Library isolation: other instrumentation classes add their own fixture folders;
    // this class's assertions (largest book, snippet counts) need only its own books.
    app.library.folders.first().filter { File(it.path).canonicalPath != File(dir).canonicalPath }.forEach { app.library.removeFolder(it.id) }
    app.library.addFolder(dir)
    app.library.rescan()
    while (app.indexer.runBatch(System.currentTimeMillis() + 120_000).stop != BatchStop.Drained) Unit
    // The book with the most text, so paging has something to page through.
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
    val dir = File(app.filesDir, "target-test-books").apply { deleteRecursively(); mkdirs() }
    val chapters = (1..3).map { c ->
      EpubFixtures.chapter("Part $c", *Array(40) { p -> "The garden of part $c, paragraph $p, held the quiet and the dark and the light of the evening; nobody in the house spoke of the crimson heron $c-$p because the old story said that the bird sang only to the lonely. ".repeat(2) })
    }
    // One optional image in the first part, so the optional-image readiness contract can be
    // exercised by making its bytes undecodable.
    val resources = chapters.mapIndexed { i, c ->
      FixtureResource("c$i.xhtml", c.body + if (i == 0) """<p><img id="opt" src="img/optional.png" alt="optional"/></p>""" else "")
    } + FixtureResource("img/optional.png", body = "", mediaType = "image/png", inSpine = false, bytes = ByteArray(64) { 0x42 })
    EpubFixtures.write(
      File(dir, "generated.epub"),
      resources,
      chapters.mapIndexed { i, c -> FixtureToc("c$i.xhtml", null, c.title) },
    )
    return dir.path
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test fun `opening a snippet target underlines the exact passage`() {
    val snippet = firstSnippet(phrase())
    open { vm.openTextHit(snippet.target) }
    val underlined = awaitUnderlined(href = hrefOf(snippet.target)) { it.isNotEmpty() }
    assertEquals(normalize(snippet.target.highlight), normalize(underlined.first()))
    assertTrue(
      "the search underline must be rendered in the document, not only registered",
      awaitRenderedDecorationBoxes(hrefOf(snippet.target)) > 0,
    )
    shoot("target-open")
    assertNull(vm.toastText.value)
  }

  @Test fun `opening a snippet target in continuous scroll mode underlines the exact passage`() {
    val snippet = firstSnippet(phrase())
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.openTextHit(snippet.target) }
    val underlined = awaitUnderlined(href = hrefOf(snippet.target)) { it.isNotEmpty() }
    assertEquals(normalize(snippet.target.highlight), normalize(underlined.first()))
    // A frame must have run the per-resource Readium initialization, or the decoration
    // registers in the JS model but renders nothing (the 7.5a regression: empty host box,
    // zero-pixel A/B diff).
    assertTrue(
      "the search underline must be rendered in the frame, not only registered",
      awaitRenderedDecorationBoxes(hrefOf(snippet.target)) > 0,
    )
    // The underlined passage is on screen: the match itself must be scrolled into view,
    // not merely underlined somewhere in the prepared book, and the reader's place is
    // within a page or two of it.
    val inView = awaitUnderlinedInViewport(hrefOf(snippet.target))
    assertTrue("underlined at ${inView.first} of ${inView.second}", inView.first >= 0 && inView.first < inView.second)
    Thread.sleep(1_000)
    val here = session().current.value!!.locations.totalProgression!!
    assertTrue("at $here, target ${snippet.target.progression}", kotlin.math.abs(here - snippet.target.progression) < 0.01)
    shoot("target-open-scroll")
    assertNull(vm.toastText.value)
  }

  @Test fun `the newest jump and decoration win while the book is still preparing`() {
    // Two jumps asked for on the same session before it is ready, then the newest
    // decoration request: only the latest of each pair may take effect (4.5).
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    val all = allSnippets(phrase())
    val early = Locator.fromJSON(JSONObject(all.first().target.locatorJson))!!
    val late = Locator.fromJSON(JSONObject(all.last().target.locatorJson))!!
    assertTrue("fixture needs two distinct targets", early.href != late.href)

    // Hold one resource response so the surface stays in preparation while both jumps land.
    val release = java.util.concurrent.CountDownLatch(1)
    val held = java.util.concurrent.atomic.AtomicBoolean(false)
    val heldFile = early.href.removeFragment().toString().substringAfterLast('/')
    com.quire.reader.navigator.epub.WebViewServer.onInterceptResource = { url, stream ->
      if (url.endsWith(heldFile)) {
        held.set(true)
        val bytes = stream.use { it.readBytes() }
        object : java.io.InputStream() {
          private var started = false
          private var at = 0
          private fun await() { if (!started) { release.await(); started = true } }
          override fun read(): Int { await(); return if (at >= bytes.size) -1 else bytes[at++].toInt() and 0xFF }
          override fun read(b: ByteArray, off: Int, len: Int): Int {
            await()
            if (at >= bytes.size) return -1
            val n = minOf(len, bytes.size - at)
            System.arraycopy(bytes, at, b, off, n); at += n
            return n
          }
        }
      } else stream
    }
    try {
      open { vm.read(book.id) }
      // Wait out any preference-driven container rebuild first: a rebuild re-applies the
      // saved locator over our jumps, so the test must run against the surface that survives.
      Thread.sleep(2_000)
      awaitCondition("a prepared surface", 30_000) { held.get() && session().navigator?.readiness?.value is EpubNavigatorFragment.Readiness.Preparing }
      scenario.onActivity {
        // Both jumps arrive while the surface is still preparing: the newer must win.
        session().go(early)
        session().go(late)
      }
      assertTrue("the resource gate must actually hold a chapter", held.get())
      release.countDown()
      awaitCondition("whole-book readiness", 30_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
      Thread.sleep(1_000)
      val landed = session().current.value!!
      // The latest request owns the landing: the early target's resource must not be it.
      // (Within the winning resource, a first-viewport reflow legitimately re-anchors,
      // so the assertion is on which resource won, not on an exact offset.)
      assertTrue("landed in ${landed.href}, expected ${late.href}", landed.href.removeFragment().toString().endsWith(late.href.removeFragment().toString().substringAfterLast('/')))
      assertFalse(
        "the superseded target's resource must not be the landing: ${landed.href}",
        landed.href.removeFragment().toString().endsWith(early.href.removeFragment().toString().substringAfterLast('/')),
      )

      // The decoration for the winning target renders in its resource. (Readium applies
      // per-resource decoration diffs, so a resource that never held a decoration stays
      // clean; the app applies a single target's hits per navigation.)
      runBlocking { session().applySearchHits(listOf(SearchHit(late, "", "late", "", ""))) }
      assertTrue("the winning target must be underlined", awaitRenderedDecorationBoxes(late.href.removeFragment()) > 0)
      assertTrue(
        "the superseded target's resource must not be underlined",
        awaitRenderedDecorationBoxes(early.href.removeFragment(), timeoutMs = 2_000) == 0,
      )
    } finally {
      com.quire.reader.navigator.epub.WebViewServer.onInterceptResource = null
    }
  }

  @Test fun `a book with a broken optional image still becomes ready`() {
    // The spec's optional-image contract: a failed image settles the frame instead of
    // holding the book in preparation forever (the shell awaits load *or* error). A
    // dedicated book and a unique image name keep the WebView's own cache out of it.
    val dir = File(app.filesDir, "broken-image-books").apply { deleteRecursively(); mkdirs() }
    val imageName = "img/optional-${System.nanoTime()}.png"
    EpubFixtures.write(
      File(dir, "broken-image.epub"),
      listOf(
        FixtureResource("c0.xhtml", """<h2>Broken image part</h2><p>The garden of the broken image part, paragraph 1, held the quiet and the dark and the light of the evening; nobody in the house spoke of the crimson heron because the old story said that the bird sang only to the lonely.</p><p><img id="opt" src="$imageName" alt="optional"/></p>"""),
      ),
      listOf(FixtureToc("c0.xhtml", null, "Broken image part")),
    )
    val target = runBlocking {
      app.library.addFolder(dir.path)
      app.library.rescan()
      val found = app.library.books.first().first { File(it.path).parentFile?.name == "broken-image-books" }
      app.library.setBookPrefs(found.id, ReaderPrefs(mode = ReadMode.Scroll))
      found
    }

    val broken = java.util.concurrent.atomic.AtomicBoolean(false)
    com.quire.reader.navigator.epub.WebViewServer.onInterceptResource = { url, stream ->
      if (url.endsWith(".png")) {
        broken.set(true)
        // Bytes no image decoder accepts: the frame must settle on the error path.
        java.io.ByteArrayInputStream(ByteArray(64) { 0x42 })
      } else stream
    }
    try {
      open { vm.read(target.id) }
      // Readiness waits for the image to complete (load or error), so by the time it is
      // reached the image has necessarily been requested.
      val ready = runBlocking { session().navigator?.awaitWholeBookReadiness() == true }
      assertTrue("the book must actually request its image", broken.get())
      assertTrue("whole-book readiness despite the broken image", ready)
      val state = runBlocking { session().navigator?.readiness?.value }
      assertFalse("a broken optional image must not fail the book: $state", state is EpubNavigatorFragment.Readiness.Failed)
      assertNull("a broken optional image must not warn", vm.toastText.value)
    } finally {
      com.quire.reader.navigator.epub.WebViewServer.onInterceptResource = null
      // The folder stays registered for the rest of this class's run; @Before removes
      // every other folder before the next test, so no teardown race with the scanner.
    }
  }

  @Test fun `an unresolved target in continuous scroll mode also falls back without an underline`() {
    val real = firstSnippet(phrase()).target
    val locator = JSONObject(real.locatorJson)
    locator.put("text", JSONObject().put("highlight", "zxqv wjkl nonexistent passage").put("before", "no such ").put("after", " anywhere"))
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.openTextHit(real.copy(locatorJson = locator.toString(), highlight = "zxqv wjkl nonexistent passage")) }
    awaitToast("Exact passage unavailable")
    assertTrue(awaitUnderlined(timeoutMs = 1_000, href = hrefOf(real), ok = { true }).isEmpty())
    shoot("target-unresolved-scroll")
  }

  @Test fun `a slow but healthy scroll startup still resolves its exact target`() {
    // Hold one real resource response beyond the old five-second navigator deadline, release it,
    // and verify the target lands underlined WITHOUT the unresolved-target toast: loading duration
    // alone must never trigger the fallback.
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    val snippet = firstSnippet(phrase())
    val fileName = hrefOf(snippet.target).removeFragment().toString().substringAfterLast('/')

    val release = java.util.concurrent.CountDownLatch(1)
    val held = java.util.concurrent.atomic.AtomicBoolean(false)
    val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()
    com.quire.reader.navigator.epub.WebViewServer.onInterceptResource = { url, stream ->
      seen.add(url)
      Log.e("SlowStart", "gate saw: $url")
      if (url.endsWith(fileName)) {
        held.set(true)
        // Serve the real bytes, but only after the old deadline has clearly passed.
        // The bytes are buffered up front (resources are small); the reader thread
        // blocks on the latch when the WebView asks for the body, which is fine: it
        // happens off the main thread inside the serving pipeline.
        val bytes = stream.use { it.readBytes() }
        object : java.io.InputStream() {
          private var started = false
          private var at = 0
          override fun read(): Int {
            if (!started) { release.await(); started = true }
            if (at >= bytes.size) return -1
            return bytes[at++].toInt() and 0xFF
          }
          override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!started) { release.await(); started = true }
            if (at >= bytes.size) return -1
            val n = minOf(len, bytes.size - at)
            System.arraycopy(bytes, at, b, off, n)
            at += n
            return n
          }
        }
      } else stream
    }
    try {
      open { vm.openTextHit(snippet.target) }
      // Hold the gate until the reader is attached (the reader must be *preparing*, not failed).
      scenario.onActivity { }
      Thread.sleep(5_500) // the old navigator deadline, and then some
      release.countDown()
      awaitCondition("held resource released") { held.get() && release.count == 0L }
      Log.e("SlowStart", "intercepted: $seen")

      val underlined = awaitUnderlined(timeoutMs = 30_000, href = hrefOf(snippet.target)) { it.isNotEmpty() }
      assertEquals(normalize(snippet.target.highlight), normalize(underlined.first()))
      assertNull("a slow book must not fall back because of its loading time", vm.toastText.value)
      shoot("target-slow-scroll")
    } finally {
      com.quire.reader.navigator.epub.WebViewServer.onInterceptResource = null
    }
  }

  @Test fun `an explicit target replaces the saved position for that opening only`() {
    val snippet = firstSnippet(phrase())
    runBlocking { app.library.savePosition(book.id, savedStart(), 0.01f) }
    open { vm.openTextHit(snippet.target) }
    assertTrue(awaitUnderlined(href = hrefOf(snippet.target)) { it.isNotEmpty() }.isNotEmpty())
    val here = session().current.value!!.locations.totalProgression!!
    assertTrue("opened at $here, not at the saved position", kotlin.math.abs(here - snippet.target.progression) < 0.15)
    // Leaving saves where the reader was; an ordinary opening then restores that, with nothing underlined.
    Thread.sleep(1_200)
    scenario.onActivity { vm.closeReader() }
    val saved = runBlocking { app.library.readingState(book.id) }!!
    assertTrue(kotlin.math.abs(saved.progress - snippet.target.progression) < 0.15)
    open { vm.read(book.id) }
    assertTrue(kotlin.math.abs(session().current.value!!.locations.totalProgression!! - saved.progress) < 0.15)
    assertTrue(awaitUnderlined(timeoutMs = 1_500, ok = { true }).isEmpty())
  }

  @Test fun `a reader replaced while the screen is not drawing is shown without crashing when drawing resumes`() {
    // While the activity is stopped Compose composes nothing, so the second reader replaces the first without any
    // composition in between: the reader screen goes straight from one session to the next at the same place in the tree.
    open { vm.read(book.id) }
    val first = session()
    scenario.moveToState(Lifecycle.State.CREATED)
    scenario.onActivity { vm.closeReader(); vm.read(book.id) }
    awaitCondition("the second reader") { (vm.reader.value as? ReaderLoad.Ready)?.session?.let { it !== first } == true }
    scenario.moveToState(Lifecycle.State.RESUMED)
    awaitCondition("the second reader's pages") { session().navigator != null }
    assertTrue(awaitUnderlined(timeoutMs = 1_000, ok = { true }).isEmpty())
    awaitCondition("the first reader to let go of its pages") { first.navigator == null }
  }

  @Test fun `a target that cannot be found falls back to its progression with a message and no underline`() {
    val real = firstSnippet(phrase()).target
    val locator = JSONObject(real.locatorJson)
    locator.put("text", JSONObject().put("highlight", "zxqv wjkl nonexistent passage").put("before", "no such ").put("after", " anywhere"))
    val lost = real.copy(locatorJson = locator.toString(), highlight = "zxqv wjkl nonexistent passage")
    open { vm.openTextHit(lost) }
    awaitToast("Exact passage unavailable")
    shoot("target-unresolved")
    assertTrue(awaitUnderlined(timeoutMs = 1_000, ok = { true }).isEmpty())
    Thread.sleep(1_500)
    val positions = session().positions
    val expected = positions[(lost.progression * (positions.size - 1)).toInt()].locations.totalProgression!!
    assertTrue("at ${session().current.value!!.locations.totalProgression}, expected near $expected", kotlin.math.abs(session().current.value!!.locations.totalProgression!! - expected) < 0.05)
  }

  @Test fun `a stale target is not opened and says the book changed`() {
    val stale = firstSnippet(phrase()).target.copy(indexedMtime = 1L)
    scenario.onActivity { vm.openTextHit(stale) }
    awaitToast(STALE_TARGET_MESSAGE)
    assertEquals(Destination.Library, vm.destination.value)
    assertTrue(vm.reader.value is ReaderLoad.Idle)
  }

  @Test fun `library search mode pages one book and each tap underlines its own match`() {
    open { vm.openBookSearch(book.id, "the") }
    awaitCondition("first page") { vm.bookSearchUi.value.status == BookSearchStatus.Results }
    assertEquals(book.id, vm.state.value.bookSearch?.bookId)
    assertTrue(vm.state.value.textSearchOpen)
    val first = vm.bookSearchUi.value
    assertEquals(20, first.snippets.size)
    assertTrue(first.hasMore)
    shoot("library-mode-first-page")

    scenario.onActivity { vm.loadMoreBookSearch() }
    awaitCondition("second page") { vm.bookSearchUi.value.snippets.size == 40 }
    val seqs = vm.bookSearchUi.value.snippets.map { it.seq }
    assertEquals(seqs.sorted(), seqs)
    assertEquals(seqs.distinct(), seqs)
    scenario.onActivity { vm.loadMoreBookSearch(); vm.loadMoreBookSearch() }
    Thread.sleep(500)
    assertFalse("a repeated request must not duplicate a page", vm.bookSearchUi.value.snippets.map { it.seq }.let { it != it.distinct() })

    // Editing keeps library semantics and the chosen book until the mode is closed.
    val query = phrase()
    scenario.onActivity { vm.setBookSearchQuery(query) }
    awaitCondition("edited query") { vm.bookSearchUi.value.let { it.status == BookSearchStatus.Results && it.snippets.size < 40 } }
    assertEquals(book.id, vm.state.value.bookSearch?.bookId)
    assertEquals(query, vm.state.value.bookSearch?.query)
    assertTrue(vm.state.value.textQuery.isEmpty())

    // Tapping a match navigates and underlines that match, and only that one.
    val tapped = vm.bookSearchUi.value.snippets.first()
    scenario.onActivity { vm.openBookSearchHit(tapped.target) }
    val underlined = awaitUnderlined(href = hrefOf(tapped.target)) { it.isNotEmpty() }
    assertEquals(1, underlined.size)
    assertEquals(normalize(tapped.target.highlight), normalize(underlined.first()))
    assertFalse(vm.state.value.textSearchOpen)
    shoot("library-mode-after-tap")

    // Closing the mode returns to ordinary state.
    scenario.onActivity { vm.setTextSearch(true); vm.setTextSearch(false) }
    assertNull(vm.state.value.bookSearch)
    assertEquals(BookSearchUi(), vm.bookSearchUi.value)
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** A few consecutive words from the middle of the book's text, quoted, so the search is a phrase that exists. */
  private fun phrase(): String = runBlocking {
    val state = app.indexDatabase.states().of(book.id)!!
    // A middle chunk, whole inside one chunk, so the phrase is found there (a phrase across two chunks would not be).
    val id = state.firstChunkId!! + state.chunkCount / 2
    val text = app.indexDatabase.useReaderConnection { c -> c.usePrepared("SELECT text FROM chunk WHERE id = $id") { it.step(); it.getText(0) } }
    val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    "\"" + words.drop(8).take(4).joinToString(" ") + "\""
  }

  private fun firstSnippet(query: String): Snippet = runBlocking {
    val parsed = FtsQuery.parse(query) as FtsQuery.Result.Query
    app.library.searchBookPage(book.id, parsed).snippets.first()
  }

  /**
   * Every match of [query] in the book, in book order, so a test can pick two far apart.
   * Pages through the real search entry point.
   */
  private fun allSnippets(query: String): List<Snippet> = runBlocking {
    val parsed = FtsQuery.parse(query) as FtsQuery.Result.Query
    val all = mutableListOf<Snippet>()
    var after = -1
    do {
      val page = app.library.searchBookPage(book.id, parsed, after)
      all += page.snippets
      after = page.nextAfterSeq ?: -1
    } while (after != -1 && all.size < 400)
    all.ifEmpty { error("no snippets for $query") }
  }

  /**
   * The number of decoration boxes actually rendered in [href]'s document, as opposed to
   * the decorations merely registered in Readium's JS model. Before the per-resource
   * initialization ran in a frame, `getDecorations(...).items` was populated while the
   * host box stayed empty and nothing was drawn — an assertion on `items` alone cannot
   * catch that.
   */
  private fun awaitRenderedDecorationBoxes(href: Url, timeoutMs: Long = 8_000): Int {
    val script = "(function(){" +
      " var hosts = document.querySelectorAll('[data-group=\"search\"]');" +
      " var boxes = 0; for (var i = 0; i < hosts.length; i++) { boxes += hosts[i].children.length; }" +
      " var styles = ''; for (var s = 0; s < document.styleSheets.length; s++) { try { styles += (document.styleSheets[s].ownerNode.textContent || ''); } catch (e) { } }" +
      " return JSON.stringify({ boxes: boxes, styled: styles.indexOf('--underline-color') >= 0 }); })()"
    var last = 0
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val raw = runBlocking(Dispatchers.Main) {
        val nav = session().navigator ?: return@runBlocking null
        runCatching { withTimeoutOrNull(2_000) { nav.evaluateJavascript(script, href) } }.getOrNull()
      }
      if (raw != null) {
        var v: Any = JSONTokener(raw).nextValue()
        var guard = 0
        while (v is String && guard++ < 4) v = JSONTokener(v).nextValue()
        val json = v as? JSONObject
        if (json != null) {
          last = json.optInt("boxes", 0)
          if (last > 0 && json.optBoolean("styled")) return last
        }
      }
      Thread.sleep(150)
    }
    return last
  }

  /** A locator near the start of the book; the test only needs it to be somewhere other than the target. */
  private fun savedStart(): String = runBlocking {
    val publication = app.publicationLoader.open(File(book.path)).getOrNull()!!
    try { publication.positions()[1].toJSON().toString() } finally { publication.close() }
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
  }

  private fun session(): ReaderSession = (vm.reader.value as ReaderLoad.Ready).session

  /** Texts under the search underline in the page now showing, polled until [ok] accepts them. */
  private fun awaitUnderlined(timeoutMs: Long = 12_000, href: Url? = null, ok: (List<String>) -> Boolean): List<String> {
    val deadline = System.currentTimeMillis() + timeoutMs
    var texts = emptyList<String>()
    while (System.currentTimeMillis() < deadline) {
      texts = underlined(href)
      if (ok(texts)) return texts
      Thread.sleep(150)
    }
    return texts
  }

  private fun underlined(href: Url? = null): List<String> = runBlocking(Dispatchers.Main) {
    val nav = session().navigator ?: return@runBlocking emptyList()
    val script = "JSON.stringify(window.readium.getDecorations('search').items.map(function(i){return i.range.toString()}))"
    val raw = runCatching { withTimeoutOrNull(2_000) { if (href != null) nav.evaluateJavascript(script, href) else nav.evaluateJavascript(script) } }.getOrNull() ?: return@runBlocking emptyList()
    val json = JSONTokener(raw).nextValue() as? String ?: return@runBlocking emptyList()
    JSONArray(json).let { a -> List(a.length()) { a.getString(it) } }
  }

  /**
   * Where the search underline sits in the outer viewport: its top in CSS px and the viewport
   * height. The frame is same-origin with the shell, so it can report its own place in the
   * outer document — an underline that is scrolled out of sight is not "positioned visibly".
   */
  private fun awaitUnderlinedInViewport(href: Url, timeoutMs: Long = 12_000): Pair<Double, Double> {
    val script = "(function(){" +
      " var items = window.readium ? window.readium.getDecorations('search').items : [];" +
      " if (!items.length) return null;" +
      " var r = items[0].range.getBoundingClientRect();" +
      " var frameEl = window.frameElement;" +
      " var local = r.top + window.pageYOffset;" +
      " var frameTop = frameEl.getBoundingClientRect().top + window.parent.pageYOffset;" +
      " return JSON.stringify({ top: frameTop + local - window.parent.pageYOffset, viewport: window.parent.innerHeight }); })()"
    var found: Pair<Double, Double>? = null
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val raw = runBlocking(Dispatchers.Main) {
        val nav = session().navigator ?: return@runBlocking null
        runCatching { withTimeoutOrNull(2_000) { nav.evaluateJavascript(script, href) } }.getOrNull()
      }
      if (raw != null) {
        var v: Any = JSONTokener(raw).nextValue()
        var guard = 0
        while (v is String && guard++ < 4) v = JSONTokener(v).nextValue()
        (v as? JSONObject)?.let { found = it.optDouble("top", 0.0) to it.optDouble("viewport", 0.0) }
      }
      // has a real one before judging whether the underline is visible.
      val measured = found
      if (measured != null && measured.second > 0.0) return measured
      Thread.sleep(150)
    }
    throw AssertionError("no search underline measured in ${href.removeFragment()}")
  }

  private fun awaitToast(part: String) = awaitCondition("toast containing '$part'", 6_000) { vm.toastText.value?.contains(part) == true }

  private fun awaitCondition(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }

  private fun hrefOf(target: IndexTarget): Url = Locator.fromJSON(JSONObject(target.locatorJson))!!.href.removeFragment()

  private fun normalize(s: String) = s.replace(Regex("\\s+"), " ").trim().lowercase()

  private fun shoot(name: String) {
    if (!shots) return
    Thread.sleep(700)
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "quire-target-tests").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
