package com.quire.reader.bench

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Shared set-up for the index-layout benchmark (throwaway; see the search-index-v2 benchmark doc). Every step runs only
 * when the instrumentation is given `-e bench 1`, so the normal instrumented suite skips it.
 */
abstract class BenchStep {
  protected val ctx = InstrumentationRegistry.getInstrumentation().targetContext
  protected val args = InstrumentationRegistry.getArguments()
  protected val dir = File(ctx.filesDir, "bench").apply { mkdirs() }
  protected val cache = BenchCache(File(dir, "cache").apply { mkdirs() })

  /** Where variant [v]'s database lives; `-e tag x` builds and reads a differently configured copy next to it. */
  protected fun dbFile(v: Variant) = File(dir, "$v${args.getString("tag").orEmpty()}.db")

  protected val pageSize get() = args.getString("pagesize")?.toInt() ?: com.quire.reader.data.db.IndexDriver.PAGE_SIZE

  protected fun requireBench() = assumeTrue("benchmark step: pass -e bench 1", args.getString("bench") != null)

  protected fun arg(name: String, default: String? = null): String = args.getString(name) ?: default ?: error("missing -e $name")

  protected fun log(msg: String) { Log.i(TAG, msg) }

  /** Appends [line] to the result file [name] under the bench folder, which the host pulls. */
  protected fun report(name: String, line: String) { File(dir, name).appendText(line + "\n"); log("$name: $line") }

  companion object { const val TAG = "IndexBench" }
}

enum class Variant(val legacy: Boolean, val fts5: Boolean, val minChars: Int, val maxChars: Int) {
  A(legacy = true, fts5 = false, 600, 900),
  B(legacy = true, fts5 = true, 600, 900),
  C(legacy = false, fts5 = true, 600, 900),
  D(legacy = false, fts5 = true, 1_000, 1_500),
  E(legacy = false, fts5 = true, 1_333, 2_000),
}
