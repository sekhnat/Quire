package com.quire.reader.ui

import android.graphics.Bitmap
import android.view.PixelCopy
import android.os.Handler
import android.os.Looper
import android.view.Window
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.MainActivity
import com.quire.reader.QuireApplication
import com.quire.reader.data.Book
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.TextAlignPref
import com.quire.reader.data.index.EpubFixtures
import com.quire.reader.reader.ReaderSession
import com.quire.reader.theme.ReaderTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The upgrade release gate (plans/advanced-reading-controls.md): on the pre-advanced build,
 * [captureBaselineEvidence] configures non-factory basic settings on two books — one with its
 * own override (Pages), one inheriting the globals (Scroll) — navigates both, and records the
 * stored preferences, the mapped navigator preferences, the reading positions and screenshots.
 * After the new build is installed over the same data without clearing anything,
 * [verifyAfterUpgrade] requires the same evidence back, with Advanced still off.
 *
 * Run only on a disposable emulator, against the real application id (no `.dbtest` suffix):
 * `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.quire.reader.ui.UpgradeGateTest`
 * with the method selected by `#captureBaselineEvidence` or `#verifyAfterUpgrade`.
 */
class UpgradeGateTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var bookPaged: Book
  private lateinit var bookScroll: Book

  private val evidenceDir = File("/sdcard/quire-gate")
  private val evidenceFile get() = File(evidenceDir, "evidence.json")

  /**
   * Non-factory globals every book without its own settings inherits, and the paged book's
   * own override. Built as JSON and decoded by the installed build's own decoder: this class
   * must run against both the pre-advanced and the new build, whose `ReaderPrefs`
   * constructors differ.
   */
  private val globalsJson = """{"theme":"Night","font":0,"fontSize":19,"lineHeight":1.7,"margin":26,"align":"Justify","mode":"Scroll"}"""
  private val pagedOverrideJson = """{"theme":"Paper","font":0,"fontSize":22,"lineHeight":1.7,"margin":26,"align":"Left","mode":"Paged"}"""

  /** Decodes prefs JSON with the installed build's own `ReaderPrefs.fromJson`. */
  private fun prefs(json: String): ReaderPrefs {
    val companion = Class.forName("com.quire.reader.data.ReaderPrefs").declaredFields.first { it.name == "Companion" }.get(null)
    val fromJson = companion.javaClass.declaredMethods.first { it.name == "fromJson" && it.parameterCount == 1 }
    return fromJson.invoke(companion, json) as ReaderPrefs
  }

  @Before fun setUp(): Unit = runBlocking {
    app.settings.setOnboardingDone(true)
    val dir = File("/sdcard/Books/quire-gate").apply { mkdirs() }
    EpubFixtures.writeScrollBook(File(dir, "gate-paged.epub"))
    EpubFixtures.writeScrollBook(File(dir, "gate-scroll.epub"))
    app.library.folders.first().filter { File(it.path).canonicalPath != dir.canonicalPath }.forEach { app.library.removeFolder(it.id) }
    app.library.addFolder(dir.path)
    app.library.rescan()
    val books = app.library.books.first().sortedBy { it.path }
    bookPaged = books.first { it.path.endsWith("gate-paged.epub") }
    bookScroll = books.first { it.path.endsWith("gate-scroll.epub") }
    scenario = ActivityScenario.launch(MainActivity::class.java)
    vm = awaitViewModel()
    evidenceDir.mkdirs()
  }

  @After fun tearDown() {
    if (::scenario.isInitialized) scenario.onActivity { vm.closeReader() }
    if (::scenario.isInitialized) scenario.close()
  }

  @Test fun captureBaselineEvidence() = runBlocking {
    app.library.setReaderDefaults(prefs(globalsJson))
    app.library.setBookPrefs(bookPaged.id, prefs(pagedOverrideJson))
    app.library.clearBookPrefs(bookScroll.id)

    open(bookPaged)
    navigate(0.35f)
    val paged = readingEvidence("paged")
    closeReader()
    open(bookScroll)
    navigate(0.6f)
    val scroll = readingEvidence("scroll")
    closeReader()

    val evidence = JSONObject()
      .put("defaults", app.settings.readerDefaults.first().toJson())
      .put("pagedPrefs", app.library.readerPrefs(bookPaged.id).first().toJson())
      .put("scrollPrefs", app.library.readerPrefs(bookScroll.id).first().toJson())
      .put("pagedOverride", app.library.hasBookOverride(bookPaged.id).first())
      .put("scrollOverride", app.library.hasBookOverride(bookScroll.id).first())
      .put("paged", paged)
      .put("scroll", scroll)
    evidenceFile.writeText(evidence.toString(2))
    println("UPGRADE-GATE evidence: ${evidenceFile.readText()}")
  }

  @Test fun verifyAfterUpgrade() = runBlocking {
    val stored = JSONObject(evidenceFile.readText())

    // The stored values survive untouched, and Advanced is still off. The JSON comparison
    // drops the advanced group: the new build serializes it (factory) where the baseline
    // wrote none; the mapped-preferences comparison below proves the effective values.
    fun storedBasic(key: String): String = JSONObject(stored.getString(key)).apply { remove("advanced") }.toString()
    suspend fun currentDefaults(): String = JSONObject(app.settings.readerDefaults.first().toJson()).apply { remove("advanced") }.toString()
    suspend fun currentBookPrefs(book: Book): String = JSONObject(app.library.readerPrefs(book.id).first().toJson()).apply { remove("advanced") }.toString()

    assertEquals(storedBasic("defaults"), currentDefaults())
    assertEquals(storedBasic("pagedPrefs"), currentBookPrefs(bookPaged))
    assertEquals(storedBasic("scrollPrefs"), currentBookPrefs(bookScroll))
    assertEquals(stored.getBoolean("pagedOverride"), app.library.hasBookOverride(bookPaged.id).first())
    assertEquals(stored.getBoolean("scrollOverride"), app.library.hasBookOverride(bookScroll.id).first())
    assertTrue("Advanced stays off by default", !app.settings.advancedReadingEnabled.first())

    open(bookPaged)
    awaitSettle()
    compareReading("paged", stored.getJSONObject("paged"))
    closeReader()
    open(bookScroll)
    awaitSettle()
    compareReading("scroll", stored.getJSONObject("scroll"))
    closeReader()

    File(evidenceDir, "gate-result.txt").writeText("verified")
    println("UPGRADE-GATE verified: mapped preferences, typography, mode, passage and locator are unchanged")
  }

  // ── evidence ────────────────────────────────────────────────────────────────

  private fun open(book: Book) {
    scenario.onActivity { vm.read(book.id) }
    awaitCondition("reader ready") { vm.reader.value is ReaderLoad.Ready }
    awaitCondition("book readiness", 60_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun navigate(progress: Float) {
    scenario.onActivity { session().goToProgress(progress) }
    awaitSettle()
  }

  private fun awaitSettle() = Thread.sleep(2_000)

  private fun closeReader() {
    scenario.onActivity { vm.closeReader() }
    awaitCondition("reader closed") { vm.reader.value !is ReaderLoad.Ready }
    Thread.sleep(500)
  }

  /** The reading evidence for the open book: locator, geometry and a screenshot. */
  private fun readingEvidence(mode: String): JSONObject = runBlocking {
    val session = session()
    val locator = session.current.value
    val nav = session.navigator
    val anchor = JSONObject()
    if (mode.startsWith("scroll")) {
      val a = nav?.continuousBook?.captureAnchor()
      anchor.put("href", a?.href.toString()).put("within", round4(a?.localOffset)).put("progression", round4(a?.progression))
    } else {
      val position = nav?.currentPagerPosition
      anchor.put("pagerPosition", position ?: -1)
    }
    screenshot(File(evidenceDir, "$mode.png"))
    JSONObject()
      .put("href", locator?.href.toString())
      .put("progression", round4(locator?.locations?.progression))
      .put("totalProgression", round4(locator?.locations?.totalProgression))
      .put("position", session.position)
      .put("totalPositions", session.positions.size)
      .put("mapped", mappedToString(app.library.readerPrefs(session.book.id).first(), session.publication.metadata.layout ?: org.readium.r2.shared.publication.Layout.REFLOWABLE))
      .put("anchor", anchor)
  }

  /** Requires the same reading evidence back after the upgrade. */
  private fun compareReading(mode: String, expected: JSONObject) = runBlocking {
    val actual = readingEvidence("$mode-verify")
    assertEquals("$mode: mapped preferences and typography", expected.getString("mapped"), actual.getString("mapped"))
    assertEquals("$mode: the same chapter is open", expected.getString("href"), actual.getString("href"))
    assertEquals("$mode: the same page is open", expected.getInt("position"), actual.getInt("position"))
    assertEquals("$mode: the book has the same page count", expected.getInt("totalPositions"), actual.getInt("totalPositions"))
    val eProg = expected.getDouble("progression")
    val aProg = actual.getDouble("progression")
    assertTrue("$mode: the reading position moved ($eProg → $aProg)", kotlin.math.abs(eProg - aProg) <= 0.01)
    val eTotal = expected.getDouble("totalProgression")
    val aTotal = actual.getDouble("totalProgression")
    assertTrue("$mode: the book position moved ($eTotal → $aTotal)", kotlin.math.abs(eTotal - aTotal) <= 0.01)
    val expectedAnchor = expected.getJSONObject("anchor")
    val actualAnchor = actual.getJSONObject("anchor")
    if (expectedAnchor.has("pagerPosition")) {
      assertEquals("$mode: the same page column", expectedAnchor.getInt("pagerPosition"), actualAnchor.getInt("pagerPosition"))
    } else {
      assertEquals("$mode: the same viewport anchor", expectedAnchor.getString("href"), actualAnchor.getString("href"))
      val eWithin = expectedAnchor.optDouble("within", -1.0)
      val aWithin = actualAnchor.optDouble("within", -2.0)
      assertTrue("$mode: the viewport anchor moved ($eWithin → $aWithin)", kotlin.math.abs(eWithin - aWithin) <= 8.0)
    }
  }

  /**
   * The mapped navigator preferences, as the installed build maps them. Reflection keeps this
   * class runnable against both builds: the pre-advanced mapper takes no layout, the new one
   * takes the publication's.
   */
  private fun mappedToString(prefs: ReaderPrefs, layout: org.readium.r2.shared.publication.Layout): String {
    val mapper = Class.forName("com.quire.reader.reader.PrefsMapperKt")
    val fn = mapper.declaredMethods.first { it.name == "toEpubPreferences" }
    fn.isAccessible = true
    return if (fn.parameterCount == 2) fn.invoke(null, prefs, layout).toString() else fn.invoke(null, prefs).toString()
  }

  private fun screenshot(target: File) {
    var window: Window? = null
    scenario.onActivity { window = it.window }
    val w = window ?: error("no window to capture")
    val bitmap = Bitmap.createBitmap(w.decorView.width.coerceAtLeast(1), w.decorView.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val latch = CountDownLatch(1)
    PixelCopy.request(w, bitmap, { latch.countDown() }, Handler(Looper.getMainLooper()))
    assertTrue("screenshot timed out", latch.await(5, TimeUnit.SECONDS))
    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun round4(value: Double?): Double = if (value == null) -1.0 else Math.round(value * 10_000.0) / 10_000.0

  private fun session(): ReaderSession = (vm.reader.value as ReaderLoad.Ready).session

  private fun awaitViewModel(): QuireViewModel {
    var found: QuireViewModel? = null
    awaitCondition("view model") {
      scenario.onActivity { activity -> found = runCatching { ViewModelProvider(activity)[QuireViewModel::class.java] }.getOrNull() }
      found != null
    }
    return found!!
  }

  private fun awaitCondition(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }
}
