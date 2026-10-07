package com.quire.reader.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.MainActivity
import com.quire.reader.QuireApplication
import com.quire.reader.data.Book
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.TypographySource
import com.quire.reader.data.index.EpubFixtures
import com.quire.reader.navigator.epub.EpubDefaults
import com.quire.reader.navigator.epub.EpubSettingsResolver
import com.quire.reader.reader.toEpubPreferences
import com.quire.reader.theme.ReaderTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The reader computes the advanced controls' availability from the preferences it actually
 * submitted, against the real vendored editor: book typography disables the dependent rows
 * and Quire typography restores them, scroll disables the page layout, and non-dark themes
 * disable the image controls. No probe preferences are ever submitted — the navigator's
 * preferences stay exactly the mapped semantic ones (C8).
 *
 * Run only under the `.dbtest` application id, never over the installed app: it opens books.
 */
class ReaderAvailabilityTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var book: Book

  @Before fun setUp() = runBlocking {
    app.settings.setOnboardingDone(true)
    val dir = File(app.filesDir, "availability-test-books").apply { deleteRecursively(); mkdirs() }
    EpubFixtures.writeScrollBook(File(dir, "availability-fixture.epub"))
    app.library.folders.first().filter { File(it.path).canonicalPath != dir.canonicalPath }.forEach { app.library.removeFolder(it.id) }
    app.library.addFolder(dir.path)
    app.library.rescan()
    book = app.library.books.first().maxBy { it.sizeBytes }
    scenario = ActivityScenario.launch(MainActivity::class.java)
    vm = awaitViewModel()
  }

  @After fun tearDown() {
    runBlocking { app.library.clearBookPrefs(book.id) }
    if (::scenario.isInitialized) scenario.onActivity { vm.closeReader() }
    if (::scenario.isInitialized) scenario.close()
  }

  @Test fun `book typography disables the dependent rows and Quire typography restores them`() {
    open(ReadMode.Paged)
    awaitCondition("availability context") { session().preferenceContext != null }
    val context = session().preferenceContext!!
    for (row in listOf(context.paragraphIndent, context.paragraphSpacing, context.weight, context.hyphens, context.lineHeight, context.textAlign, context.pageLayout)) {
      assertTrue("expected available under Quire typography, was ${row.reason}", row.available)
    }

    vm.updateBookAdvanced { it.copy(typographySource = TypographySource.Book) }
    awaitCondition("book typography applied") { session().preferenceContext?.let { !it.paragraphIndent.available } == true }
    val bookRules = session().preferenceContext!!
    for (row in listOf(bookRules.paragraphIndent, bookRules.paragraphSpacing, bookRules.weight, bookRules.hyphens, bookRules.lineHeight, bookRules.textAlign)) {
      assertFalse("expected BookTypography, was ${row.reason}", row.available)
      assertEquals(com.quire.reader.reader.UnavailableReason.BookTypography, row.reason)
    }
    // The row that turns book typography off stays usable, and nothing about the theme changed.
    assertTrue(bookRules.typographySource.available)
    assertTrue(bookRules.images.available)
    // The submitted preferences are exactly the mapped semantic ones — no probe ever went out.
    awaitSubmittedMatches()

    vm.updateBookAdvanced { it.copy(typographySource = TypographySource.Quire) }
    awaitCondition("Quire typography restored") { session().preferenceContext?.let { it.paragraphIndent.available } == true }
    awaitSubmittedMatches()
  }

  @Test fun `scroll disables the page layout and switching back restores it`() {
    open(ReadMode.Scroll)
    awaitCondition("availability context") { session().preferenceContext != null }
    val scrolling = session().preferenceContext!!
    assertFalse(scrolling.pageLayout.available)
    assertEquals(com.quire.reader.reader.UnavailableReason.Mode, scrolling.pageLayout.reason)

    vm.updatePrefs { it.copy(mode = ReadMode.Paged) }
    awaitCondition("page layout restored in pages mode") { session().preferenceContext?.let { it.pageLayout.available } == true }
    awaitSubmittedMatches()
  }

  @Test fun `the theme gates the image controls`() {
    open(ReadMode.Paged)
    awaitCondition("availability context") { session().preferenceContext != null }
    assertTrue(session().preferenceContext!!.images.available)

    vm.updatePrefs { it.copy(theme = ReaderTheme.Sepia) }
    awaitCondition("sepia applied") { session().preferenceContext?.let { !it.images.available } == true }
    assertEquals(com.quire.reader.reader.UnavailableReason.Theme, session().preferenceContext!!.images.reason)

    vm.updatePrefs { it.copy(theme = ReaderTheme.Paper) }
    awaitCondition("paper applied") { session().preferenceContext?.let { !it.images.available } == true }

    vm.updatePrefs { it.copy(theme = ReaderTheme.Night) }
    awaitCondition("night applied") { session().preferenceContext?.let { it.images.available } == true }
  }

  private fun open(mode: ReadMode) {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = mode)) }
    scenario.onActivity { vm.read(book.id) }
    awaitCondition("reader ready") { vm.reader.value is ReaderLoad.Ready }
    awaitCondition("book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun session() = (vm.reader.value as ReaderLoad.Ready).session

  /**
   * The navigator resolves exactly the settings the semantic mapping implies — a probe
   * submission (a preference set only for an availability check) would show up here.
   */
  private fun awaitSubmittedMatches() {
    awaitCondition("submitted preferences match the mapping") {
      val session = session()
      val mapped = session.layout.let { vm.prefs.value.toEpubPreferences(it) }
      val expected = EpubSettingsResolver(session.publication.metadata, EpubDefaults()).settings(mapped)
      session.navigator?.settings?.value == expected
    }
  }

  private fun awaitViewModel(): QuireViewModel {
    var found: QuireViewModel? = null
    awaitCondition("view model") {
      scenario.onActivity { activity -> found = runCatching { ViewModelProvider(activity)[QuireViewModel::class.java] }.getOrNull() }
      found != null
    }
    return found!!
  }

  private fun awaitCondition(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }
}
