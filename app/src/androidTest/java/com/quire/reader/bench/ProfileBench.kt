package com.quire.reader.bench

import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.BookChunks
import com.quire.reader.data.index.ChunkRanges
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexStore
import com.quire.reader.data.index.RoomIndexSql
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** Step 6: where one search spends its time, phase by phase (`-e variant`, `-e q` the typed query, `-e tag`/`-e pagesize` as elsewhere). */
class ProfileBench : BenchStep() {
  private inline fun <T> timed(label: String, runs: Int, block: () -> T): T {
    var last = block()
    val times = ArrayList<Double>()
    repeat(runs) { val t = System.nanoTime(); last = block(); times += (System.nanoTime() - t) / 1e6 }
    times.sort()
    report("profile.tsv", "$label\tp50=%.1f\tp95=%.1f".format(times[times.size / 2], times[(times.size * 95 / 100).coerceAtMost(times.size - 1)]))
    return last
  }

  @Test fun profile() = runBlocking<Unit> {
    requireBench()
    val v = Variant.valueOf(arg("variant"))
    val input = arg("q")
    val runs = arg("runs", "15").toInt()
    val user = QuireDatabase.create(ctx, File(dir, "user.db").absolutePath)
    val index = IndexDatabase.create(ctx, dbFile(v).absolutePath, pageSize)
    val store = IndexStore(RoomIndexSql(index))
    val q = FtsQuery.parse(input) as FtsQuery.Result.Query
    val tag = "$v ${args.getString("tag").orEmpty()} [$input]"

    val books = timed("$tag searchableBooks", runs) { runBlocking { user.search().searchableBooks(null, null, null, null, 0) } }
    val states = timed("$tag all index_state", runs) { runBlocking { index.states().all() } }
    val ranges = ChunkRanges(states.mapNotNull { s -> s.firstChunkId?.let { BookChunks(s.bookId, it, s.lastChunkId!!) } })
    val span = ranges.span!!
    val ids = ArrayList<Long>()
    val per = timed("$tag stream first 5000", runs) {
      ids.clear()
      runBlocking { store.matchingIds(q.match!!, span.first, span.last) { id -> ranges.indexOf(id); ids += id; ids.size < 5000 } }
      ids.size
    }
    log("profile $tag streamed $per rows, books=${books.size}")
    val perBook = ids.groupBy { ranges.bookOf(it) }
    val first = perBook.values.map { it.take(5) }.take(40).flatten()
    timed("$tag highlight ${first.size} chunks", runs) { runBlocking { store.highlights(q.match!!, first) } }
    timed("$tag load ${first.size} chunks", runs) { runBlocking { store.chunks(first) } }
    timed("$tag bm25 top 300", runs) { runBlocking { store.ranked(q.match!!, span.first, span.last, 300) } }
    timed("$tag full count (no cap)", 3) {
      var n = 0
      runBlocking { store.matchingIds(q.match!!, span.first, span.last) { n++; true } }
      n
    }
    index.close(); user.close()
  }
}
