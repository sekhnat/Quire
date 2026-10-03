package com.quire.reader.ui

import android.graphics.Bitmap
import android.os.Environment
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
import com.quire.reader.reader.ReaderSession
import com.quire.reader.reader.STALE_TARGET_MESSAGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    EpubFixtures.write(File(dir, "generated.epub"), chapters)
    return dir.path
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test fun `opening a snippet target underlines the exact passage`() {
    val snippet = firstSnippet(phrase())
    open { vm.openTextHit(snippet.target) }
    val underlined = awaitUnderlined(href = hrefOf(snippet.target)) { it.isNotEmpty() }
    assertEquals(normalize(snippet.target.highlight), normalize(underlined.first()))
    shoot("target-open")
    assertNull(vm.state.value.toast)
  }

  @Test fun `opening a snippet target in continuous scroll mode underlines the exact passage`() {
    val snippet = firstSnippet(phrase())
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open { vm.openTextHit(snippet.target) }
    val underlined = awaitUnderlined(href = hrefOf(snippet.target)) { it.isNotEmpty() }
    assertEquals(normalize(snippet.target.highlight), normalize(underlined.first()))
    // The underlined passage is on screen: the reader's place is within a page or two of it.
    Thread.sleep(1_000)
    val here = session().current.value!!.locations.totalProgression!!
    assertTrue("at $here, target ${snippet.target.progression}", kotlin.math.abs(here - snippet.target.progression) < 0.01)
    shoot("target-open-scroll")
    assertNull(vm.state.value.toast)
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
    assertEquals(Screen.Library, vm.state.value.screen)
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
  private fun phrase(): String {
    val db = app.database.openHelper.writableDatabase
    val seq = db.query("SELECT chunkCount FROM index_state WHERE bookId = ${book.id}").use { it.moveToFirst(); it.getInt(0) } / 2
    val text = db.query("SELECT text FROM text_chunk WHERE bookId = ${book.id} AND seq = $seq").use { it.moveToFirst(); it.getString(0) }
    val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return ("\"" + words.drop(8).take(4).joinToString(" ") + "\"")
  }

  private fun firstSnippet(query: String): Snippet = runBlocking {
    val parsed = FtsQuery.parse(query) as FtsQuery.Result.Query
    app.library.searchBookPage(book.id, parsed).snippets.first()
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

  private fun awaitToast(part: String) = awaitCondition("toast containing '$part'", 6_000) { vm.state.value.toast?.contains(part) == true }

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
