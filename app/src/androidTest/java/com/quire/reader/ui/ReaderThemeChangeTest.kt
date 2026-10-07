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
import com.quire.reader.theme.ReaderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Changes the reading theme while a book is open, the way the settings sheet does (`updatePrefs`), in both reading modes.
 * A crash anywhere in the navigator takes the instrumentation process down, so a pass means the reader survived every
 * theme and still reports the theme it was given.
 * Run only under the `.dbtest` application id, never over the installed app: it opens books.
 */
class ReaderThemeChangeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var book: Book

  @Before fun setUp() = runBlocking {
    app.settings.setOnboardingDone(true)
    val dir = File(app.filesDir, "theme-test-books").apply { deleteRecursively(); mkdirs() }
    EpubFixtures.writeScrollBook(File(dir, "theme-fixture.epub"))
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

  @Test fun `changing the theme with a paged book open does not crash`() = themesSurvive(ReadMode.Paged)

  @Test fun `changing the theme with a scrolling book open does not crash`() = themesSurvive(ReadMode.Scroll)

  private fun themesSurvive(mode: ReadMode) {
    runBlocking { app.library.setBookPrefs(book.id, ReaderPrefs(mode = mode, theme = ReaderTheme.Night)) }
    scenario.onActivity { vm.read(book.id) }
    awaitCondition("reader ready") { vm.readerLoad is ReaderLoad.Ready }
    Thread.sleep(2_000)
    for (theme in listOf(ReaderTheme.Paper, ReaderTheme.Sepia, ReaderTheme.Black, ReaderTheme.Dusk, ReaderTheme.Night)) {
      scenario.onActivity { vm.reader.updatePrefs { it.copy(theme = theme) } }
      Thread.sleep(1_500)
      assertEquals(theme, vm.reader.prefs.value.theme)
      assertTrue("the reader is still open after switching to $theme", vm.readerLoad is ReaderLoad.Ready)
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
