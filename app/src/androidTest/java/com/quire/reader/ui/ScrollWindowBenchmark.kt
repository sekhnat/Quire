package com.quire.reader.ui

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.BuildConfig
import com.quire.reader.MainActivity
import com.quire.reader.QuireApplication
import com.quire.reader.data.Book
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.navigator.epub.PressureTiers
import com.quire.reader.navigator.epub.ScrollTelemetry
import com.quire.reader.reader.ReaderSession
import com.quire.reader.ui.reader.ReaderLoad
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * The scroll-window benchmark on the large reference book. Benchmark builds only
 * (`-PtestBuildType=benchmark`, application id `com.quire.reader.bench`); it skips itself in
 * every other build and when the book is missing.
 *
 * The book is ORV.epub, pushed by hand to [BOOK_PATH] (`adb push ORV.epub /data/local/tmp/quire-bench/`)
 * and copied into the app's own files: nothing on shared storage is read or written. Reports go to [RESULTS_DIR]
 * (`adb pull /data/local/tmp/quire-bench/results`).
 */
class ScrollWindowBenchmark {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private lateinit var scenario: ActivityScenario<MainActivity>
  private lateinit var vm: QuireViewModel
  private lateinit var book: Book
  private val args = InstrumentationRegistry.getArguments()

  companion object {
    private const val TAG = "ScrollBenchmark"
    private const val BOOK_PATH = "/data/local/tmp/quire-bench/ORV.epub"
    private const val RESULTS_DIR = "/data/local/tmp/quire-bench/results"
    /** A blank viewport this long is noticeable: several frames. */
    private const val NOTICEABLE_BLANK_MS = 100L
    /** Past the cover and front matter. */
    private const val START_CHAPTER = 2
  }

  @Before fun setUp() = runBlocking {
    assumeTrue("scroll telemetry is compiled into benchmark builds only", BuildConfig.SCROLL_TELEMETRY)
    val dir = File(app.filesDir, "bench-books").apply { mkdirs() }
    val copy = File(dir, "ORV.epub")
    if (!copy.isFile || copy.length() == 0L) {
      copy.outputStream().use { out -> shellStream("cat $BOOK_PATH").use { it.copyTo(out) } }
    }
    assumeTrue("$BOOK_PATH is missing; push ORV.epub there first", copy.length() > 0)
    app.settings.setOnboardingDone(true)
    app.library.folders.first().filter { File(it.path).canonicalPath != dir.canonicalPath }.forEach { app.library.removeFolder(it.id) }
    if (app.library.folders.first().none { File(it.path).canonicalPath == dir.canonicalPath }) app.library.addFolder(dir.path)
    app.library.rescan()
    // Index the book now: the indexer must not compete with the reader while it is measured.
    while (app.indexer.runBatch(System.currentTimeMillis() + 120_000).stop != com.quire.reader.data.index.BatchStop.Drained) Unit
    book = app.library.books.first().single()
    scenario = ActivityScenario.launch(MainActivity::class.java)
    vm = awaitViewModel()
  }

  @After fun tearDown() {
    if (!::scenario.isInitialized) return
    runBlocking { app.library.clearBookPrefs(book.id) }
    runCatching { scenario.onActivity { vm.closeReader() } }
    scenario.close()
  }

  /**
   * The same deterministic fling script under each window policy, interleaved, from a freshly opened book
   * each time: how long the viewport shows chapters that are not loaded yet.
   */
  @Test fun flingBlankTime() {
    val policies = (args.getString("policies") ?: "static,adaptive").split(',')
    val repetitions = args.getString("repetitions")?.toInt() ?: 3
    val arms = mutableListOf<ArmResult>()
    repeat(repetitions) { rep ->
      for (policy in policies) {
        arms += runArm("fling", policy, rep) { flingScript() }
        Log.i(TAG, arms.last().summary())
      }
    }
    writeReport("fling", arms)

    // Acceptance, when both policies ran: the adaptive window shows at least 30% less blank viewport than the
    // fixed one over the same flings, and no more of it in blanks long enough to notice. (Not a percentile of
    // the episodes: the adaptive window leaves too few of them for one to mean anything.)
    val static = arms.filter { it.policy == "static" }
    val adaptive = arms.filter { it.policy == "adaptive" }
    if (static.isNotEmpty() && adaptive.isNotEmpty()) {
      val staticBlank = median(static.map { it.blankMs })
      val adaptiveBlank = median(adaptive.map { it.blankMs })
      assertTrue("adaptive blank $adaptiveBlank ms is not 30% under static $staticBlank ms", adaptiveBlank <= staticBlank * 0.7)
      val staticNoticeable = median(static.map { it.noticeableBlankMs })
      val adaptiveNoticeable = median(adaptive.map { it.noticeableBlankMs })
      assertTrue(
        "adaptive spends $adaptiveNoticeable ms in noticeable blanks, static $staticNoticeable ms",
        adaptiveNoticeable <= staticNoticeable,
      )
    }
  }

  /**
   * A long reading session under each policy, to show that renderer memory stays bounded. Renderer RSS climbs
   * slowly over a session under either policy (the WebView's own caches; the documents held do not grow), so the
   * bar is the fixed window's own session on the same book, not an absolute number.
   */
  @Test fun longSession() {
    val policies = (args.getString("policies") ?: "static,adaptive").split(',')
    val minutes = args.getString("minutes")?.toInt() ?: 15
    val arms = policies.map { policy -> runArm("long", policy, 0) { longScript(minutes) }.also { Log.i(TAG, it.summary()) } }
    writeReport("long", arms)

    val adaptive = arms.firstOrNull { it.policy == "adaptive" } ?: return
    val rss = adaptive.memory.map { it.rssKb / 1024.0 }
    assertTrue("no renderer samples", rss.size > 60)
    // No runaway growth: the last quarter of the session averages within 10% of the second quarter.
    val quarter = rss.size / 4
    val second = rss.subList(quarter, 2 * quarter).average()
    val last = rss.subList(rss.size - quarter, rss.size).average()
    assertTrue("renderer grew from $second MB to $last MB", last <= second * 1.1)
    assertTrue("held ${adaptive.peakDocuments} documents", adaptive.peakDocuments <= 10)
    // Within the budget, except for documents in the viewport or pinned, which are never refused. A document's
    // weight is only known once it loaded, so allow a sample in a hundred to catch one that came in heavy.
    val over = adaptive.telemetry.samples.count { it.weight > maxOf(it.budget, it.required) + 1 }
    assertTrue("$over of ${adaptive.telemetry.samples.size} samples over the weight budget", over <= adaptive.telemetry.samples.size / 100)
    // No higher than the fixed window over the same session.
    val static = arms.firstOrNull { it.policy == "static" } ?: return
    assertTrue("adaptive peaked at ${adaptive.peakRssMb} MB, static at ${static.peakRssMb} MB", adaptive.peakRssMb <= static.peakRssMb)
  }

  /** Memory pressure shrinks the window at once and releasing it restores the normal window. */
  @Test fun memoryPressure() {
    open()
    val nav = session().navigator!!
    session().go(nav.readingOrder[START_CHAPTER])
    repeat(10) { fling(forward = true); Thread.sleep(1_500) }
    Thread.sleep(3_000)
    val sampler = RendererSampler().also { it.start() }
    Thread.sleep(5_000)
    val before = sampler.samples.toList()
    scenario.onActivity { nav.simulateMemoryPressure(PressureTiers.Tier.Minimal) }
    var documents = -1
    awaitCondition("the window shrinks", 1_000) {
      documents = runBlocking { nav.continuousBook!!.liveFrameCount() }
      documents in 0..3
    }
    Thread.sleep(5_000)
    val during = sampler.samples.toList().drop(before.size)
    scenario.onActivity { nav.simulateMemoryPressure(null) }
    Thread.sleep(3_000)
    sampler.stop()
    val mb = { list: List<MemorySample> -> list.map { it.rssKb / 1024 }.average().toInt() }
    val summary = "pressure: $documents documents within 1 s; renderer ${mb(before)} MB before, ${mb(during)} MB under minimal"
    Log.i(TAG, summary)
    writeAsShell("$RESULTS_DIR/pressure.txt", summary + "\n")
  }

  private fun median(values: List<Long>) = values.sorted().let { if (it.isEmpty()) 0.0 else (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }

  // ── scripts ───────────────────────────────────────────────────────────────

  /**
   * Skimming, then runaway flinging. Chromium boosts a fling that starts while one in the same direction is still
   * running, so flings 1.5 s apart pick up speed:
   * - 6 runs of 6 hard flings forward, each run stopped by a finger and followed by a 3 s rest;
   * - 12 hard flings forward without a stop, which reach the fastest a fling goes (about 45,000 CSS px/s);
   * - 3 runs of 6 back, each stopped;
   * - 10 bursts of three forward flings back to back, each stopped.
   * About a third of the large reference book: every fling moves, none runs into its end.
   */
  private fun flingScript() {
    repeat(6) { repeat(6) { fling(forward = true); Thread.sleep(1_500) }; stopScrolling(); Thread.sleep(3_000) }
    repeat(12) { fling(forward = true); Thread.sleep(1_500) }
    stopScrolling(); Thread.sleep(3_000)
    repeat(3) { repeat(6) { fling(forward = false); Thread.sleep(1_500) }; stopScrolling(); Thread.sleep(3_000) }
    repeat(10) {
      repeat(3) { fling(forward = true); Thread.sleep(250) }
      stopScrolling()
      Thread.sleep(2_000)
    }
  }

  /**
   * Reading across the whole book for [minutes]: runs of flings in both directions, table-of-contents jumps
   * to seeded chapters, a font-size reflow and back. Deterministic for a given book.
   */
  private fun longScript(minutes: Int) {
    val random = java.util.Random(42)
    val until = System.currentTimeMillis() + minutes * 60_000L
    var round = 0
    while (System.currentTimeMillis() < until) {
      val forward = round % 4 != 3
      repeat(8) { fling(forward); Thread.sleep(700L + random.nextInt(900)) }
      stopScrolling()
      when (round % 5) {
        1 -> {
          val toc = session().toc
          session().go(toc[random.nextInt(toc.size)].link)
          Thread.sleep(3_000)
        }
        3 -> {
          val size = if (round % 2 == 1) 22 else null
          scenario.onActivity { vm.reader.updatePrefs { it.copy(fontSize = size ?: ReaderPrefs().fontSize) } }
          Thread.sleep(4_000)
        }
        4 -> if (round == 4) {
          // Once: the reader goes to the background and comes back.
          scenario.moveToState(Lifecycle.State.CREATED)
          Thread.sleep(5_000)
          scenario.moveToState(Lifecycle.State.RESUMED)
          Thread.sleep(3_000)
        }
      }
      round++
    }
    scenario.onActivity { vm.reader.updatePrefs { it.copy(fontSize = ReaderPrefs().fontSize) } }
  }

  // ── arms ──────────────────────────────────────────────────────────────────

  private data class MemorySample(val t: Long, val rssKb: Long, val swapKb: Long, val pssKb: Long?)

  private data class ArmResult(
    val kind: String,
    val policy: String,
    val repetition: Int,
    val durationMs: Long,
    val telemetry: ScrollTelemetry.Snapshot,
    val memory: List<MemorySample>,
  ) {
    val blankMs get() = telemetry.episodes.sumOf { it.ms }
    /** Time in blank episodes of [NOTICEABLE_BLANK_MS] or longer. */
    val noticeableBlankMs get() = telemetry.episodes.filter { it.ms >= NOTICEABLE_BLANK_MS }.sumOf { it.ms }
    val p95EpisodeMs get() = telemetry.episodes.map { it.ms }.sorted().let { if (it.isEmpty()) 0L else it[((it.size - 1) * 0.95).toInt()] }
    val peakDocuments get() = telemetry.samples.maxOfOrNull { it.live + it.loading } ?: 0
    val peakRssMb get() = (memory.maxOfOrNull { it.rssKb } ?: 0) / 1024
    val peakRssSwapMb get() = (memory.maxOfOrNull { it.rssKb + it.swapKb } ?: 0) / 1024
    val wasted get() = telemetry.samples.lastOrNull()?.wasted ?: 0
    val cancelled get() = telemetry.samples.lastOrNull()?.cancelled ?: 0
    val loads get() = telemetry.samples.lastOrNull()?.mounts ?: 0

    fun summary() = "$kind/$policy#$repetition: blank ${blankMs} ms in ${telemetry.episodes.size} episodes " +
      "($noticeableBlankMs ms in episodes of ${NOTICEABLE_BLANK_MS} ms or more) " +
      "(p95 $p95EpisodeMs ms), $loads loads, $wasted wasted, $cancelled cancelled, peak $peakDocuments documents, " +
      "renderer peak ${peakRssMb} MB RSS (${peakRssSwapMb} MB with swap) over ${durationMs / 1000} s"
  }

  /** Opens the book fresh under [policy], runs [script] while sampling the renderer, and closes it again. */
  private fun runArm(kind: String, policy: String, repetition: Int, script: () -> Unit): ArmResult {
    open()
    val nav = session().navigator!!
    scenario.onActivity { nav.continuousBook!!.setPolicy(policy) }
    // Every arm starts at the same place: reopening restores wherever the previous arm stopped.
    session().go(nav.readingOrder[START_CHAPTER])
    Thread.sleep(5_000)
    val telemetry = nav.scrollTelemetry!!
    telemetry.reset()
    val sampler = RendererSampler().also { it.start() }
    val started = System.currentTimeMillis()
    try {
      script()
      // Let the last fling come to rest and the window settle, so open episodes close.
      Thread.sleep(4_000)
    } finally {
      sampler.stop()
    }
    val result = ArmResult(kind, policy, repetition, System.currentTimeMillis() - started, telemetry.snapshot(), sampler.samples)
    scenario.onActivity { vm.closeReader() }
    awaitCondition("reader closed") { vm.readerLoad !is ReaderLoad.Ready }
    Thread.sleep(2_000)
    return result
  }

  private fun writeReport(name: String, arms: List<ArmResult>) {
    val json = JSONObject().put("book", BOOK_PATH).put("arms", JSONArray().apply {
      arms.forEach { arm ->
        put(JSONObject()
          .put("kind", arm.kind).put("policy", arm.policy).put("repetition", arm.repetition)
          .put("durationMs", arm.durationMs).put("blankMs", arm.blankMs).put("episodes", arm.telemetry.episodes.size)
          .put("p95EpisodeMs", arm.p95EpisodeMs).put("noticeableBlankMs", arm.noticeableBlankMs).put("loads", arm.loads).put("wasted", arm.wasted).put("cancelled", arm.cancelled)
          .put("peakDocuments", arm.peakDocuments).put("peakRssMb", arm.peakRssMb).put("peakRssSwapMb", arm.peakRssSwapMb)
          .put("episodeMs", JSONArray(arm.telemetry.episodes.map { it.ms }))
          .put("loadMs", JSONArray(arm.telemetry.loadMs))
          .put("flings", JSONArray(arm.telemetry.flings.map { JSONArray(listOf(it.t, it.start, it.predicted, it.actual, it.velocity, it.observed, it.ms, it.end, it.corrected)) }))
          .put("samples", JSONArray(arm.telemetry.samples.map { JSONArray(listOf(it.t, it.live, it.loading, it.weight, it.jsHeap, it.tier, it.y, it.required, it.budget)) }))
          .put("memory", JSONArray(arm.memory.map { JSONArray(listOf(it.t, it.rssKb, it.swapKb, it.pssKb ?: -1)) })))
      }
    })
    // Written next to the book, as the shell user: the connected test run uninstalls the app, and its own
    // directories with it, as soon as the test finishes.
    writeAsShell("$RESULTS_DIR/$name.json", json.toString())
    writeAsShell("$RESULTS_DIR/$name.txt", arms.joinToString("\n") { it.summary() } + "\n")
    Log.i(TAG, "report written to $RESULTS_DIR/$name.json")
  }

  private fun writeAsShell(path: String, text: String) {
    shell("mkdir -p ${path.substringBeforeLast('/')}")
    // The command is split on spaces with no shell in between, so no redirection: tee writes the file.
    val (stdout, stdin) = instrumentation.uiAutomation.executeShellCommandRw("tee $path")
    ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(text.toByteArray()) }
    ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
  }

  // ── input ─────────────────────────────────────────────────────────────────

  /** One hard fling: the finger travels half the screen in 40 ms and lifts. */
  private fun fling(forward: Boolean) {
    val metrics = app.resources.displayMetrics
    val x = metrics.widthPixels / 2f
    val low = metrics.heightPixels * 0.75f
    val high = metrics.heightPixels * 0.3f
    val from = if (forward) low else high
    val to = if (forward) high else low
    val down = SystemClock.uptimeMillis()
    val duration = 40L
    val steps = 6
    inject(down, down, MotionEvent.ACTION_DOWN, x, from)
    for (i in 1..steps) {
      val at = down + duration * i / steps
      SystemClock.sleep((at - SystemClock.uptimeMillis()).coerceAtLeast(0))
      inject(down, at, MotionEvent.ACTION_MOVE, x, from + (to - from) * i / steps)
    }
    inject(down, down + duration, MotionEvent.ACTION_UP, x, to)
  }

  /**
   * A finger landing on a running fling stops it. It then moves slowly, so it is neither a tap (which toggles the
   * reader's controls) nor a long press (which selects text), and lifts too slowly to fling again.
   */
  private fun stopScrolling() {
    val metrics = app.resources.displayMetrics
    val x = metrics.widthPixels / 2f
    val from = metrics.heightPixels * 0.5f
    val down = SystemClock.uptimeMillis()
    inject(down, down, MotionEvent.ACTION_DOWN, x, from)
    val steps = 12
    for (i in 1..steps) {
      val at = down + 50L * i
      SystemClock.sleep((at - SystemClock.uptimeMillis()).coerceAtLeast(0))
      inject(down, at, MotionEvent.ACTION_MOVE, x, from - 5f * i)
    }
    SystemClock.sleep(100)
    inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, from - 5f * steps)
  }

  private fun inject(down: Long, at: Long, action: Int, x: Float, y: Float) {
    val event = MotionEvent.obtain(down, at, action, x, y, 0)
    event.source = InputDevice.SOURCE_TOUCHSCREEN
    instrumentation.uiAutomation.injectInputEvent(event, true)
    event.recycle()
  }

  // ── renderer memory ───────────────────────────────────────────────────────

  /**
   * Samples the reader's WebView renderer, an isolated process the app cannot inspect itself: its RSS and swap
   * every second from `/proc`, its PSS every tenth sample from `dumpsys meminfo` (slow). The process is found
   * through the app's sandboxed renderer service and looked up again when it changes (a renderer loss).
   */
  private inner class RendererSampler {
    val samples = java.util.Collections.synchronizedList(mutableListOf<MemorySample>())
    @Volatile private var running = true
    private val thread = Thread {
      var pid: Int? = null
      var n = 0
      while (running) {
        if (pid == null || !shell("ls /proc/$pid/status").contains("status")) pid = rendererPid()
        pid?.let { p ->
          val status = shell("cat /proc/$p/status")
          val rss = Regex("VmRSS:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toLong()
          val swap = Regex("VmSwap:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toLong() ?: 0
          val pss = if (n++ % 10 == 0) {
            Regex("TOTAL PSS:\\s+(\\d+)").find(shell("dumpsys meminfo $p"))?.groupValues?.get(1)?.toLong()
          } else null
          if (rss != null) samples += MemorySample(System.currentTimeMillis(), rss, swap, pss)
        }
        Thread.sleep(1_000)
      }
    }
    fun start() = thread.start()
    fun stop() { running = false; thread.join(5_000) }
  }

  private fun rendererPid(): Int? =
    Regex("app=ProcessRecord\\{\\w+ (\\d+):[^ }]*sandboxed_process").find(shell("dumpsys activity services ${app.packageName}"))
      ?.groupValues?.get(1)?.toInt()

  // ── helpers ───────────────────────────────────────────────────────────────

  private fun open() {
    runBlocking {
      app.library.clearBookPrefs(book.id)
      app.library.setBookPrefs(book.id, ReaderPrefs(mode = ReadMode.Scroll))
    }
    scenario.onActivity { vm.read(book.id) }
    awaitCondition("reader ready") { vm.readerLoad is ReaderLoad.Ready }
    awaitCondition("book readiness", 120_000) { runBlocking { session().navigator?.awaitWholeBookReadiness() == true } }
  }

  private fun session(): ReaderSession = (vm.readerLoad as ReaderLoad.Ready).session

  private fun awaitViewModel(): QuireViewModel {
    var found: QuireViewModel? = null
    awaitCondition("view model") {
      scenario.onActivity { activity -> found = runCatching { ViewModelProvider(activity)[QuireViewModel::class.java] }.getOrNull() }
      found != null
    }
    return found!!
  }

  private fun shellStream(command: String) =
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))

  /** Runs [command] as the shell user; reads its output to EOF, or the command never finishes. */
  private fun shell(command: String): String = shellStream(command).use { String(it.readBytes()) }

  private fun awaitCondition(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
    throw AssertionError("Timed out waiting for $what")
  }
}
