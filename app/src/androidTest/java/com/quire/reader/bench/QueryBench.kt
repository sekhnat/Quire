package com.quire.reader.bench

import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.BookChunks
import com.quire.reader.data.index.ChunkRanges
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexStore
import com.quire.reader.data.index.RoomIndexSql
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextSearcher
import com.quire.reader.data.index.TextStatusFilter
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** One benchmark query: library search with [filters], or "show all in this book" when [page] is set. */
data class BenchQuery(val name: String, val input: String, val filters: TextSearchFilters = TextSearchFilters.None, val page: Boolean = false, val cjk: Boolean = false)

val BENCH_QUERIES = listOf(
  BenchQuery("common typed", "the"),
  BenchQuery("common exact", "\"the\""),
  BenchQuery("mid word", "whale"),
  BenchQuery("mid-common word", "king"),
  BenchQuery("rare word", "quillfeather"),
  BenchQuery("unique word", "zorvak777"),
  BenchQuery("common prefix", "th"),
  BenchQuery("rare prefix", "quillf"),
  BenchQuery("mid prefix", "hous"),
  BenchQuery("prefix wh", "wh"),
  BenchQuery("prefix sta", "sta"),
  BenchQuery("prefix com", "com"),
  BenchQuery("prefix gre", "gre"),
  BenchQuery("2 words + prefix", "king sta"),
  BenchQuery("2-word phrase common", "\"of the\""),
  BenchQuery("2-word phrase rare", "\"van helsing\""),
  BenchQuery("5-word phrase rare", "\"the crimson heron sang softly\""),
  BenchQuery("5-word phrase common", "\"at the end of the\""),
  BenchQuery("AND common tail", "king the"),
  BenchQuery("AND mid", "whale ship "),
  BenchQuery("filter narrow author", "whale", TextSearchFilters(author = BenchMeta.NARROW_AUTHOR)),
  BenchQuery("filter broad tag", "whale", TextSearchFilters(tag = BenchMeta.BROAD_TAG)),
  BenchQuery("filter broad tag common", "the ", TextSearchFilters(tag = BenchMeta.BROAD_TAG)),
  BenchQuery("filter status reading", "whale", TextSearchFilters(status = TextStatusFilter.Reading)),
  BenchQuery("page common prefix", "the", page = true),
  BenchQuery("page common exact", "\"the\"", page = true),
  BenchQuery("CJK 1 char common", "的", cjk = true),
  BenchQuery("CJK 1 char", "猫", cjk = true),
  BenchQuery("CJK 2 chars", "天下", cjk = true),
  BenchQuery("CJK 3 chars", "孫悟空", cjk = true),
  BenchQuery("CJK mixed", "chapter 天下", cjk = true),
  BenchQuery("Korean 2 chars", "아내", cjk = true),
)

/** The production search over a new-layout variant. */
class NewEngine(user: QuireDatabase, index: IndexDatabase, cap: Int) {
  private val searcher = TextSearcher(user, index, IndexStore(RoomIndexSql(index)), maxExamined = cap)
  private val store = IndexStore(RoomIndexSql(index))
  private val indexDb = index

  suspend fun search(input: String, f: TextSearchFilters, order: SearchOrder = SearchOrder.Relevance): Summary {
    val q = FtsQuery.parse(input) as? FtsQuery.Result.Query ?: return Summary(emptyList(), 0, false, 0)
    val r = searcher.search(q, f, order)
    return Summary(r.books.map { it.book.id }, r.matchingBooks, r.capped, r.books.sumOf { it.snippets.size }, r.incomplete, r.prefixDowngraded)
  }

  suspend fun page(input: String, bookId: Long): Int {
    val q = FtsQuery.parse(input) as? FtsQuery.Result.Query ?: return 0
    return searcher.page(q, bookId).snippets.size
  }

  /** Every book with a match for the query as written (no guard), seams included, for comparing result sets. */
  suspend fun bookSet(input: String): Set<Long> {
    val q = FtsQuery.parse(input) as? FtsQuery.Result.Query ?: return emptySet()
    val ranges = ChunkRanges(indexDb.states().all().mapNotNull { s -> s.firstChunkId?.let { BookChunks(s.bookId, it, s.lastChunkId!!) } })
    val span = ranges.span ?: return emptySet()
    val books = HashSet<Long>()
    val onRow: (Long) -> Boolean = { id -> ranges.bookOf(id)?.let(books::add); true }
    when {
      q.match != null -> store.matchingIds(q.match!!, span.first, span.last, alsoCjk = q.cjk, onRow = onRow)
      q.cjk != null -> store.matchingIds(q.cjk!!, span.first, span.last, cjk = true, onRow = onRow)
    }
    if (q.phrase) store.seamHits(q.match!!, span.first, span.last).filter { it.crossesSplit }.forEach { h -> ranges.bookOf(h.chunkId)?.let(books::add) }
    return books
  }
}

/**
 * Step 3: the query matrix for one variant (`-e variant`), `-e runs` timed runs after one warm-up, with the examine cap
 * `-e cap` (default 5,000, today's). Writes timings to `query.tsv` and result sets to `sets-<variant>.tsv`.
 */
class QueryBench : BenchStep() {
  private fun percentile(sorted: List<Double>, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

  @Test fun query() = runBlocking<Unit> {
    requireBench()
    val v = Variant.valueOf(arg("variant"))
    val runs = arg("runs", "20").toInt()
    val cap = arg("cap", "5000").toInt()
    val only = args.getString("only")?.split(',')?.toSet()
    val bigBook = cache.books().filter { it.ok }.maxBy { it.elements }.id
    val legacy = if (v.legacy) LegacyEngine(LegacySchema.open(dbFile(v), v.fts5), v.fts5, cap) else null
    val user = if (v.legacy) null else QuireDatabase.create(ctx, File(dir, "user.db").absolutePath)
    val index = if (v.legacy) null else IndexDatabase.create(ctx, dbFile(v).absolutePath, pageSize)
    val engine = if (v.legacy) null else NewEngine(user!!, index!!, cap)
    val queries = BENCH_QUERIES.filter { only == null || it.name in only }
    for (q in queries) {
      suspend fun once(): Summary = when {
        q.page && legacy != null -> Summary(listOf(bigBook), 1, false, legacy.page(q.input, bigBook))
        q.page -> Summary(listOf(bigBook), 1, false, engine!!.page(q.input, bigBook))
        legacy != null -> legacy.search(q.input, q.filters)
        else -> engine!!.search(q.input, q.filters)
      }
      var t = System.nanoTime()
      val first = once()
      val firstMs = (System.nanoTime() - t) / 1e6
      val times = ArrayList<Double>()
      var last = first
      repeat(runs) {
        t = System.nanoTime()
        last = once()
        times += (System.nanoTime() - t) / 1e6
      }
      times.sort()
      report(
        "query.tsv",
        listOf(v, cap, q.name, q.input.replace("\t", " "), "%.1f".format(firstMs), "%.1f".format(percentile(times, 0.5)), "%.1f".format(percentile(times, 0.95)),
          last.matchingBooks, last.shown.size, last.capped, last.incomplete, last.snippets, last.downgraded ?: "-", last.shown.take(10).joinToString(",")).joinToString("\t"),
      )
    }
    index?.close(); user?.close()
  }

  /** Full matching book sets (no caps, no guard) for every unfiltered library query, for the diff against A. */
  @Test fun sets() = runBlocking<Unit> {
    requireBench()
    val v = Variant.valueOf(arg("variant"))
    val legacy = if (v.legacy) LegacyEngine(LegacySchema.open(dbFile(v), v.fts5), v.fts5) else null
    val user = if (v.legacy) null else QuireDatabase.create(ctx, File(dir, "user.db").absolutePath)
    val index = if (v.legacy) null else IndexDatabase.create(ctx, dbFile(v).absolutePath, pageSize)
    val engine = if (v.legacy) null else NewEngine(user!!, index!!, 5000)
    val out = File(dir, "sets-$v.tsv").apply { delete() }
    for (q in BENCH_QUERIES.filter { !it.page && it.filters == TextSearchFilters.None }) {
      val t = System.nanoTime()
      val set = legacy?.bookSet(q.input) ?: engine!!.bookSet(q.input)
      out.appendText("${q.name}\t${set.size}\t${set.sorted().joinToString(",")}\n")
      log("sets $v ${q.name}: ${set.size} books in ${(System.nanoTime() - t) / 1_000_000} ms")
    }
    index?.close(); user?.close()
  }
}
