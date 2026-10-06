package com.quire.reader.bench

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.quire.reader.QuireApplication
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.IndexStateEntity
import com.quire.reader.data.index.BatchStop
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.index.TextSearcher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Step 7: the production code at scale. Scans the fixture library into the app's own databases, indexes it with the real
 * indexer until it drains, merges, checks integrity, runs the query matrix through the real searcher, and times a clear.
 * Results go to `prod.tsv`. Run with `-e bench 1` and all-files access granted.
 */
class ProdBench : BenchStep() {
  private val app get() = ctx.applicationContext as QuireApplication

  private suspend fun <T> IndexDatabase.rd(sql: String, read: (androidx.sqlite.SQLiteStatement) -> T): T = useReaderConnection { it.usePrepared(sql, read) }

  private suspend fun IndexDatabase.one(sql: String): Long = rd(sql) { it.step(); it.getLong(0) }

  /** Bytes per table and index, read by the platform SQLite (the bundled build has no `dbstat`), which can open the file as long as it only reads shadow tables. */
  private fun dbstat(): Map<String, Long> {
    val db = android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath(IndexDatabase.FILE_NAME).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
    return db.use { d -> d.rawQuery("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } } }
  }

  private fun fileBytes(name: String) = listOf("", "-wal", "-shm").sumOf { File(app.getDatabasePath(name).path + it).length() }

  @Test fun prod() = runBlocking<Unit> {
    requireBench()
    val root = arg("root", "/sdcard/Calibre Library")
    val phase = arg("phase", "all")
    if (phase == "all" || phase == "index") {
      app.settings.setIndexingEnabled(true)
      app.settings.setIndexChargingOnly(false)
      app.indexer.clearIndex()
      app.database.folders().insert(FolderEntity(path = root))
      var t = System.currentTimeMillis()
      val scan = app.library.scanner.scan()
      report("prod.tsv", "scan\t${(System.currentTimeMillis() - t) / 1000} s\t$scan")
      t = System.currentTimeMillis()
      var batches = 0
      while (true) {
        val b = System.currentTimeMillis()
        val r = app.indexer.runBatch(System.currentTimeMillis() + 300_000)
        batches++
        log("batch $batches processed=${r.processed} stop=${r.stop} ${(System.currentTimeMillis() - b) / 1000} s")
        if (r.stop == BatchStop.Drained) break
      }
      val index = app.indexDatabase
      val states = index.states().all()
      report(
        "prod.tsv",
        "indexed\t${(System.currentTimeMillis() - t) / 1000} s\tbatches=$batches\tdone=${states.count { it.status == IndexStateEntity.STATUS_DONE }}\tfailed=${states.count { it.status == IndexStateEntity.STATUS_FAILED }}" +
          "\tskipped=${states.count { it.status == IndexStateEntity.STATUS_SKIPPED }}\ttruncated=${states.count { it.truncated }}\tchunks=${index.one("SELECT COUNT(*) FROM chunk")}" +
          "\ttextBytes=${states.sumOf { it.textBytes }}\tseams=${index.one("SELECT COUNT(*) FROM seam")}\tfile=${fileBytes(IndexDatabase.FILE_NAME)}",
      )
    }
    if (phase == "all" || phase == "check") {
      val index = app.indexDatabase
      report("prod.tsv", "before-merge\tfile=${fileBytes(IndexDatabase.FILE_NAME)}")
      val t = System.currentTimeMillis()
      var steps = 0
      val store = com.quire.reader.data.index.IndexStore(com.quire.reader.data.index.RoomIndexSql(index))
      while (!store.mergeStep()) steps++
      report("prod.tsv", "merge\t${(System.currentTimeMillis() - t) / 1000} s\tsteps=$steps\tfile=${fileBytes(IndexDatabase.FILE_NAME)}")
      app.indexDatabase.useWriterConnection { it.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { s -> s.step() } }
      val after = dbstat().entries.sortedByDescending { it.value }.take(10).joinToString(" ") { "${it.key}=${it.value}" }
      report("prod.tsv", "after-merge\tfile=${fileBytes(IndexDatabase.FILE_NAME)}\t$after")
      index.useWriterConnection { c ->
        c.usePrepared("INSERT INTO chunk_fts(chunk_fts, rank) VALUES('integrity-check', 1)") { it.step() }
        c.usePrepared("INSERT INTO seam_fts(seam_fts, rank) VALUES('integrity-check', 1)") { it.step() }
        c.usePrepared("INSERT INTO cjk_fts(cjk_fts) VALUES('integrity-check')") { it.step() }
      }
      val noncontiguous = index.one("SELECT COUNT(*) FROM index_state s WHERE s.firstChunkId IS NOT NULL AND (s.lastChunkId - s.firstChunkId + 1 != s.chunkCount OR (SELECT COUNT(*) FROM chunk c WHERE c.book_id = s.bookId) != s.chunkCount)")
      val orphans = index.one("SELECT COUNT(*) FROM chunk WHERE book_id NOT IN (SELECT bookId FROM index_state)")
      val ftsRows = index.one("SELECT COUNT(*) FROM chunk_fts_docsize") - index.one("SELECT COUNT(*) FROM chunk")
      val seamRows = index.one("SELECT COUNT(*) FROM seam_fts_docsize") - index.one("SELECT COUNT(*) FROM seam")
      report("prod.tsv", "integrity\tfts5 integrity-check ok\tnoncontiguous=$noncontiguous\torphans=$orphans\tfts-rows-minus-chunks=$ftsRows\tseam-fts-minus-seams=$seamRows")
    }
    if (phase == "all" || phase == "query") {
      val searcher = TextSearcher(app.database, app.indexDatabase)
      val bigBook = app.indexDatabase.rd("SELECT bookId FROM index_state ORDER BY chunkCount DESC LIMIT 1") { it.step(); it.getLong(0) }
      for (q in BENCH_QUERIES) {
        val parsed = com.quire.reader.data.index.FtsQuery.parse(q.input) as? com.quire.reader.data.index.FtsQuery.Result.Query ?: continue
        suspend fun once(): String = if (q.page) searcher.page(parsed, bigBook).snippets.size.toString() else searcher.search(parsed, q.filters, SearchOrder.Relevance).let { "${it.matchingBooks}b/${it.books.sumOf { b -> b.snippets.size }}s${if (it.capped) "*" else ""}" }
        var t = System.nanoTime()
        val first = once()
        val firstMs = (System.nanoTime() - t) / 1e6
        val times = ArrayList<Double>()
        repeat(20) { t = System.nanoTime(); once(); times += (System.nanoTime() - t) / 1e6 }
        times.sort()
        report("prod.tsv", "query\t${q.name}\tfirst=%.1f\tp50=%.1f\tp95=%.1f\t$first".format(firstMs, times[10], times[18]))
      }
    }
    if (phase == "all" || phase == "clear") {
      var t = System.currentTimeMillis()
      app.indexer.clearIndex()
      report("prod.tsv", "clear\t${System.currentTimeMillis() - t} ms\tfile=${fileBytes(IndexDatabase.FILE_NAME)}")
    }
  }
}
