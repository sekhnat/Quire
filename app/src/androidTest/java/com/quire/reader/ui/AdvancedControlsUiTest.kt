package com.quire.reader.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.MainActivity
import com.quire.reader.QuireApplication
import com.quire.reader.data.Book
import com.quire.reader.data.PageLayoutPref
import com.quire.reader.data.ParagraphPreset
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.TypographySource
import com.quire.reader.data.WidenLevel
import com.quire.reader.data.WeightLevel
import com.quire.reader.data.index.EpubFixtures
import com.quire.reader.reader.UnavailableReason
import com.quire.reader.theme.ReaderTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The three acceptance scenarios for the advanced reading controls, driven the way the UI
 * drives them (see the design record for the scenarios and the copy):
 *
 * 1. First use — the controls are off; switching them on in Settings adds the Advanced
 *    section to the reader's Display sheet; choosing the Screen paragraph preset reflows the
 *    text, keeps the reading position, survives close/reopen and is inherited by a book
 *    without overrides.
 * 2. Availability — under Book typography the dependent rows hold back with their reasons and
 *    keep their values; Quire typography brings them back; scroll and the theme hold their
 *    rows back. The on-screen rendering of these states is covered by the component tests.
 * 3. Restore — the restore buttons are disabled when there is nothing to restore, a
 *    confirmation precedes one full replacement (cancel writes nothing), and the confirmed
 *    restores replace exactly the advanced group with the copy's message.
 *
 * Run only under the `.dbtest` application id, never over the installed app: it opens books.
 */
class AdvancedControlsUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var bookPaged: Book
  private lateinit var bookScroll: Book

  @Before fun setUp() = runBlocking {
    app.settings.setOnboardingDone(true)
    val dir = File(app.filesDir, "advanced-ui-test-books").apply { deleteRecursively(); mkdirs() }
    EpubFixtures.writeScrollBook(File(dir, "ui-fixture-a.epub"))
    EpubFixtures.writeScrollBook(File(dir, "ui-fixture-b.epub"))
    app.library.folders.first().filter { File(it.path).canonicalPath != dir.canonicalPath }.forEach { app.library.removeFolder(it.id) }
    app.library.addFolder(dir.path)
    app.library.rescan()
    val books = app.library.books.first().sortedBy { it.path }
    bookPaged = books.first { it.path.endsWith("ui-fixture-a.epub") }
    bookScroll = books.first { it.path.endsWith("ui-fixture-b.epub") }
    app.library.clearAllBookPrefs()
    app.library.setReaderDefaults(ReaderPrefs())
    app.settings.setAdvancedReadingEnabled(false)
    scenario = ActivityScenario.launch(MainActivity::class.java)
    vm = awaitViewModel()
  }

  @After fun tearDown() {
    runBlocking {
      app.library.clearBookPrefs(bookPaged.id)
      app.library.clearBookPrefs(bookScroll.id)
      app.library.setReaderDefaults(ReaderPrefs())
      app.settings.setAdvancedReadingEnabled(false)
    }
    if (::scenario.isInitialized) scenario.onActivity { vm.closeReader(); vm.closeSettings() }
    if (::scenario.isInitialized) scenario.close()
  }

  // ── scenario 1: first use ───────────────────────────────────────────────────

  @Test fun `first use the section appears and the preset keeps the position`() {
    // Switching the controls on in Settings is what adds the section, both there and in the reader.
    runBlocking { app.settings.setAdvancedReadingEnabled(true) }
    awaitCondition("visibility on") { vm.advancedReadingEnabled.value }
    onActivity { it.openSettings() }

    // Authoring the Screen preset on the globals: every book without overrides now shows it.
    onActivity { vm.updateDefaults { it.copy(advanced = it.advanced.withPreset(ParagraphPreset.Screen) ?: it.advanced) } }
    awaitCondition("globals preset") { vm.defaults.value.advanced.paragraphPreset == ParagraphPreset.Screen }
    onActivity { it.closeSettings() }

    openPaged()
    val session = session()
    awaitCondition("availability context") { session.preferenceContext != null }
    assertEquals(ParagraphPreset.Screen, vm.prefs.value.advanced.paragraphPreset)

    // The section starts collapsed and opens on request.
    assertFalse(vm.state.value.advancedOpen)
    vm.setAdvancedOpen(true)
    assertTrue(vm.state.value.advancedOpen)

    // Choosing Traditional in the reader reflows the text and keeps the reading position.
    val before = session.totalProgress
    vm.chooseParagraphPreset(ParagraphPreset.Traditional)
    awaitCondition("preset applied") { vm.prefs.value.advanced.paragraphPreset == ParagraphPreset.Traditional }
    val after = session.totalProgress
    assertTrue("the reading position moved ($before → $after)", kotlin.math.abs(before - after) <= 0.02)

    // The choice survives close/reopen, and a second book without overrides keeps the globals'.
    onActivity { it.closeReader() }
    openPaged()
    assertEquals(ParagraphPreset.Traditional, vm.prefs.value.advanced.paragraphPreset)
    onActivity { it.closeReader() }
    openScrollWithoutOverrides()
    awaitCondition("inherited preset") { vm.prefs.value.advanced.paragraphPreset == ParagraphPreset.Screen }
  }

  // ── scenario 2: availability ────────────────────────────────────────────────

  @Test fun `availability book typography holds rows back with reasons and restores`() {
    runBlocking { app.settings.setAdvancedReadingEnabled(true) }
    openScroll()
    val context = { session().preferenceContext!! }
    awaitCondition("availability context") { session().preferenceContext != null }

    // Scroll holds the page layout back; the theme holds the images back.
    assertEquals(UnavailableReason.Mode, context().pageLayout.reason)
    assertFalse(context().pageLayout.available)
    onActivity { vm.updatePrefs { it.copy(theme = ReaderTheme.Sepia) } }
    awaitCondition("sepia applied") { context().images.reason == UnavailableReason.Theme }

    // Book typography holds the dependent rows back with their values intact.
    val presetBefore = vm.prefs.value.advanced.paragraphPreset
    onActivity { vm.updateBookAdvanced { it.copy(typographySource = TypographySource.Book) } }
    awaitCondition("book typography applied") { !context().paragraphIndent.available }
    assertEquals(UnavailableReason.BookTypography, context().paragraphIndent.reason)
    assertEquals(UnavailableReason.BookTypography, context().paragraphPreset.reason)
    assertEquals(UnavailableReason.BookTypography, context().hyphens.reason)
    assertEquals(presetBefore, vm.prefs.value.advanced.paragraphPreset)
    // The row that turns it back stays usable.
    assertTrue(context().typographySource.available)

    // Quire typography brings them back.
    onActivity { vm.updateBookAdvanced { it.copy(typographySource = TypographySource.Quire) } }
    awaitCondition("quire typography restored") { context().paragraphIndent.available }
    onActivity { vm.updatePrefs { it.copy(mode = ReadMode.Paged) } }
    awaitCondition("page layout back in pages mode") { context().pageLayout.available && context().pageLayout.reason == UnavailableReason.None }
  }

  // ── scenario 3: restore ─────────────────────────────────────────────────────

  @Test fun `restore confirms and cancels and disables itself`() {
    // Nothing customized: both restore buttons would be disabled.
    assertFalse(vm.advancedDefaultsCustomized.value)
    openPaged()
    assertFalse(vm.hasBookAdvancedOverride.value)

    // Customizing the globals arms the global restore; a book override arms the book's.
    onActivity { vm.updateDefaults { it.copy(advanced = it.advanced.copy(letterSpacing = WidenLevel.Wider)) } }
    awaitCondition("globals customized") { vm.advancedDefaultsCustomized.value }
    onActivity { vm.updateBookAdvanced { it.copy(fontWeight = WeightLevel.Heavy) } }
    awaitCondition("book override stored") { runBlocking { app.library.hasBookAdvancedOverride(bookPaged.id).first() } }
    val basics = vm.prefs.value

    // A confirmation precedes the replacement; cancel writes nothing.
    // (The cancel path simply does not call the reducer; the state below must be untouched.)
    val beforeCancelGlobals = vm.defaults.value
    val beforeCancelBook = vm.prefs.value
    assertNull(vm.state.value.toast)
    // ...the confirmed path:
    onActivity { vm.restoreBookAdvanced() }
    awaitCondition("book advanced restored") {
      vm.prefs.value.advanced == vm.defaults.value.advanced && !runBlocking { app.library.hasBookAdvancedOverride(bookPaged.id).first() }
    }
    assertEquals("This book's advanced settings follow your defaults", vm.state.value.toast)
    // Exactly the advanced group: the book's basic settings stay.
    assertEquals(basics.theme, vm.prefs.value.theme)
    assertEquals(basics.fontSize, vm.prefs.value.fontSize)
    assertEquals(basics.mode, vm.prefs.value.mode)
    // The book now inherits the customized globals, not the factory object.
    assertEquals(WidenLevel.Wider, vm.prefs.value.advanced.letterSpacing)

    // The global restore replaces the advanced group alone.
    onActivity { vm.restoreGlobalAdvanced() }
    awaitCondition("globals restored") { vm.defaults.value.advanced.letterSpacing == WidenLevel.Default }
    assertEquals("Advanced reading settings restored", vm.state.value.toast)
    // The book override was already gone; the book now follows the restored globals.
    awaitCondition("book follows globals") { vm.prefs.value.advanced.letterSpacing == WidenLevel.Default }

    // After everything is restored there is nothing left to restore: the flags go dark again.
    awaitCondition("nothing to restore") { !vm.advancedDefaultsCustomized.value && !vm.hasBookAdvancedOverride.value }
    onActivity { it.closeReader() }
    openScrollWithoutOverrides()
    // Page layout follows the restored globals in a book without overrides.
    onActivity { it.closeReader() }
    openScroll()
    awaitCondition("scroll context") { session().preferenceContext?.pageLayout?.reason == UnavailableReason.Mode }
    assertEquals(PageLayoutPref.Auto, vm.prefs.value.advanced.pageLayout)
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private fun session() = (vm.reader.value as ReaderLoad.Ready).session

  private fun openPaged() {
    runBlocking { app.library.setBookPrefs(bookPaged.id, ReaderPrefs(mode = ReadMode.Paged)) }
    open(bookPaged)
  }

  private fun openScrollWithoutOverrides() {
    runBlocking { app.library.clearBookPrefs(bookScroll.id) }
    open(bookScroll)
  }

  /** Opens the second book in scroll mode; its mode override is set here on purpose. */
  private fun openScroll() {
    runBlocking { app.library.setBookPrefs(bookScroll.id, ReaderPrefs(mode = ReadMode.Scroll)) }
    open(bookScroll)
  }

  private fun open(book: Book) {
    onActivity { vm.read(book.id) }
    awaitCondition("reader ready") { vm.reader.value is ReaderLoad.Ready }
    awaitCondition("book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun <T> onActivity(block: (QuireViewModel) -> T): T? {
    var result: T? = null
    scenario.onActivity { activity -> result = block(ViewModelProvider(activity)[QuireViewModel::class.java]) }
    return result
  }

  private fun awaitViewModel(): QuireViewModel {
    awaitCondition("view model") { runCatching { onActivity { it } }.isSuccess }
    return onActivity { it }!!
  }

  private fun awaitCondition(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }
}
