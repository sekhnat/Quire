package com.quire.reader.bench

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.execSQL
import com.quire.reader.data.db.IndexDriver
import com.quire.reader.data.index.FtsQuery
import org.junit.Test
import java.io.File

/**
 * Step 4: compares a new-layout variant's full result sets with A's (`-e variant`). A book only A finds is expected when
 * the query matches the text of two adjacent chunks of that book in the variant but no single chunk: the documented
 * cross-chunk case. Anything else is reported as unexpected.
 */
class DiffBench : BenchStep() {
  private fun sets(v: Variant): Map<String, Set<Long>> = File(dir, "sets-$v.tsv").readLines().filter { it.isNotBlank() }.associate { line ->
    val parts = line.split('\t')
    parts[0] to (parts.getOrNull(2)?.split(',')?.filter { it.isNotBlank() }?.map { it.toLong() }?.toSet() ?: emptySet())
  }

  @Test fun diff() {
    requireBench()
    val v = Variant.valueOf(arg("variant"))
    val a = sets(Variant.A)
    val x = sets(v)
    val db = SQLiteDatabase.openDatabase(File(dir, "$v.db").path, null, SQLiteDatabase.OPEN_READONLY)
    val mem = IndexDriver().open(":memory:")
    mem.execSQL("CREATE VIRTUAL TABLE t USING fts5(text, tokenize='unicode61 remove_diacritics 2')")
    for (q in BENCH_QUERIES.filter { !it.page && it.filters.author == null && it.filters.tag == null && it.filters.status == null }) {
      val inA = a[q.name] ?: continue
      val inX = x[q.name] ?: continue
      val onlyA = inA - inX
      val onlyX = inX - inA
      var expected = 0
      val unexpected = ArrayList<Long>()
      val match = (FtsQuery.parse(q.input) as? FtsQuery.Result.Query)?.match
      if (!q.cjk && match != null) {
        for (book in onlyA) {
          val texts = db.rawQuery("SELECT text FROM chunk WHERE book_id = ? ORDER BY seq", arrayOf(book.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
          mem.execSQL("DELETE FROM t")
          mem.prepare("INSERT INTO t(rowid, text) VALUES (?, ?)").use { st ->
            texts.forEachIndexed { i, s -> st.bindLong(1, i.toLong()); st.bindText(2, s); st.step(); st.reset() }
            texts.zipWithNext().forEachIndexed { i, (p, n) -> st.bindLong(1, 1_000_000L + i); st.bindText(2, "$p $n"); st.step(); st.reset() }
          }
          val hits = mem.prepare("SELECT rowid FROM t WHERE t MATCH ?").use { st -> st.bindText(1, match); buildList { while (st.step()) add(st.getLong(0)) } }
          if (hits.isNotEmpty() && hits.all { it >= 1_000_000L }) expected++ else unexpected += book
        }
      }
      report("diff.tsv", listOf(v, q.name, inA.size, inX.size, onlyA.size, onlyX.size, expected, unexpected.size, unexpected.take(10).joinToString(","), onlyX.take(10).joinToString(",")).joinToString("\t"))
    }
    mem.close()
    db.close()
  }
}

/**
 * Step 5: database sizes per table (`dbstat` through the platform SQLite, which can read the bundled build's files as long
 * as no FTS5 table is queried) and in all (`-e variant`).
 */
class SizeBench : BenchStep() {
  @Test fun sizes() {
    requireBench()
    val v = Variant.valueOf(arg("variant"))
    val file = File(dir, "$v.db")
    val total = listOf("", "-wal").sumOf { File(file.path + it).length() }
    val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    val tables = runCatching {
      db.rawQuery("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name ORDER BY 2 DESC", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
    }
    db.close()
    tables.onSuccess { list ->
      val index = list.filter { (name, _) -> !name.startsWith("book") && !name.startsWith("sqlite_autoindex_book") && name != "index_book_tag_tag" && !name.startsWith("room_") && !name.startsWith("android_") && name != "sqlite_schema" && name != "sqlite_master" }
      report("size.tsv", "$v\tfile=$total\tindex=${index.sumOf { it.second }}\t" + list.joinToString(" ") { "${it.first}=${it.second}" })
    }.onFailure { report("size.tsv", "$v\tfile=$total\tdbstat unavailable: ${it.message}") }
  }
}
