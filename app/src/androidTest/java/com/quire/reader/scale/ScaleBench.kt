package com.quire.reader.scale // SCALE-PROBE: scratch benchmark, deleted after the scale run

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextSearcher
import com.quire.reader.data.index.TextStatusFilter
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Times TextSearcher against the app's real quire.db. Args: q, runs, author, series, tag, status, page (book id), cap, probe, prefixdocs. */
class ScaleBench {
  @Test fun run() = runBlocking {
    val a = InstrumentationRegistry.getArguments()
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val db = QuireDatabase.create(target, target.getDatabasePath("quire.db").absolutePath)
    try {
      val searcher = TextSearcher(
        db,
        maxExamined = a.getString("cap")?.toInt() ?: com.quire.reader.data.index.MAX_COUNTED_PASSAGES,
        probeBooks = a.getString("probe")?.toInt() ?: TextSearcher.BROAD_FILTER_PROBE_BOOKS,
        maxPrefixDocuments = a.getString("prefixdocs")?.toInt() ?: com.quire.reader.data.index.MAX_PREFIX_DOCUMENTS,
      )
      val filters = TextSearchFilters(a.getString("author"), a.getString("series"), a.getString("tag"), a.getString("status")?.let { TextStatusFilter.valueOf(it) })
      val runs = a.getString("runs")?.toInt() ?: 11
      val page = a.getString("page")?.toLong()
      val label = a.getString("label") ?: a.getString("q")!!
      // Open the database and warm the JIT with a query that matches nothing, so the first timed call is the query's own cost.
      val warm = FtsQuery.parse("zzqxjwv") as FtsQuery.Result.Query
      runCatching { searcher.search(warm, TextSearchFilters.None) }
      val parsed = FtsQuery.parse(a.getString("q")!!)
      val q = parsed as? FtsQuery.Result.Query ?: run { Log.i("ScaleBench", "[$label] not searchable: $parsed"); return@runBlocking }
      val times = ArrayList<Double>()
      var summary = ""
      repeat(runs) {
        val t0 = System.nanoTime()
        if (page != null) {
          val p = searcher.page(q, page)
          summary = "page snippets=${p.snippets.size} next=${p.nextAfterSeq}"
        } else {
          val r = searcher.search(q, filters)
          summary = "books=${r.books.size} matchingBooks=${r.matchingBooks} capped=${r.capped} incomplete=${r.incomplete} downgraded=${r.prefixDowngraded} top=${r.books.firstOrNull()?.passages?.label}"
        }
        times += (System.nanoTime() - t0) / 1e6
      }
      val rest = times.drop(1).sorted()
      val median = if (rest.isEmpty()) Double.NaN else rest[rest.size / 2]
      Log.i("ScaleBench", "[$label] first=%.1fms warmMedian=%.1fms min=%.1f max=%.1f n=%d | %s | match=%s".format(times[0], median, rest.firstOrNull() ?: 0.0, rest.lastOrNull() ?: 0.0, times.size, summary, q.match))
    } finally {
      db.close()
    }
  }
}
