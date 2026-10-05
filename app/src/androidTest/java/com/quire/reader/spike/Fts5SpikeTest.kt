package com.quire.reader.spike

import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.data.db.IndexDriver
import com.quire.reader.data.index.IndexContent
import com.quire.reader.data.index.Tokenizer
import com.quire.reader.data.index.foldedTerm
import com.quire.reader.reader.PublicationLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.services.content.Content
import java.io.File

/** Phase 0 spike (throwaway): what the bundled SQLite build offers the search index. Results are logged under `Fts5Spike`. */
@OptIn(ExperimentalReadiumApi::class)
class Fts5SpikeTest {
  private val target = InstrumentationRegistry.getInstrumentation().targetContext
  private val file = File(target.cacheDir, "spike/spike.db").apply { parentFile!!.mkdirs(); delete() }
  private val conn: SQLiteConnection = IndexDriver().open(file.path)

  @After fun close() {
    conn.close()
    file.parentFile!!.deleteRecursively()
  }

  private fun rows(sql: String, vararg args: Any): List<List<String?>> = conn.prepare(sql).use { st ->
    args.forEachIndexed { i, a -> when (a) { is Long -> st.bindLong(i + 1, a); is Int -> st.bindLong(i + 1, a.toLong()); else -> st.bindText(i + 1, a.toString()) } }
    buildList { while (st.step()) add((0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) }) }
  }

  private fun one(sql: String, vararg args: Any): String? = rows(sql, *args).single().single()

  private fun log(msg: String) = Log.i(TAG, msg)

  @Test fun versionAndFeatures() {
    log("sqlite_version=${one("SELECT sqlite_version()")}")
    log("compile_options=${rows("PRAGMA compile_options").joinToString(",") { it[0]!! }}")
    log("auto_vacuum=${one("PRAGMA auto_vacuum")} synchronous=${one("PRAGMA synchronous")} journal=${one("PRAGMA journal_mode")}")
    assertEquals("2", one("PRAGMA auto_vacuum")) // INCREMENTAL, set before any table exists
    assertEquals("1", one("PRAGMA synchronous")) // NORMAL

    conn.execSQL("CREATE TABLE chunk (id INTEGER PRIMARY KEY, text TEXT NOT NULL)")
    conn.execSQL("CREATE VIRTUAL TABLE chunk_fts USING fts5(text, content='chunk', content_rowid='id', tokenize='unicode61 remove_diacritics 2', detail=full)")
    conn.execSQL("CREATE VIRTUAL TABLE cjk_fts USING fts5(grams, content='', contentless_delete=1, tokenize='unicode61', detail=full)")
    conn.execSQL("CREATE VIRTUAL TABLE chunk_vocab USING fts5vocab(chunk_fts, 'row')")
    conn.execSQL("CREATE VIRTUAL TABLE chunk_inst USING fts5vocab(chunk_fts, 'instance')")
    conn.execSQL("CREATE VIRTUAL TABLE cjk_vocab USING fts5vocab(cjk_fts, 'row')")
    log("fts5 + detail=full + contentless_delete + fts5vocab row/instance: created")

    val texts = listOf("The quick brown fox jumps over the lazy dog.", "A brown quick fox; café crème.", "Nothing here at all, only quick.")
    texts.forEachIndexed { i, t ->
      conn.execSQL("INSERT INTO chunk(id, text) VALUES (${i + 1}, '${t.replace("'", "''")}')")
      conn.execSQL("INSERT INTO chunk_fts(rowid, text) VALUES (${i + 1}, '${t.replace("'", "''")}')")
    }
    // highlight(): a phrase instance is one marked region.
    val hl = one("SELECT highlight(chunk_fts, 0, char(57344), char(57345)) FROM chunk_fts WHERE chunk_fts MATCH ? AND rowid = 1", "\"quick brown\"")!!
    log("highlight phrase: ${hl.replace('', '[').replace('', ']')}")
    assertEquals(1, hl.count { it == '' })
    val hlAnd = one("SELECT highlight(chunk_fts, 0, char(57344), char(57345)) FROM chunk_fts WHERE chunk_fts MATCH ? AND rowid = 2", "\"quick\" \"brown\"")!!
    log("highlight AND: ${hlAnd.replace('', '[').replace('', ']')}")
    // Diacritics folded (remove_diacritics 2).
    assertEquals("2", one("SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH ?", "\"cafe\""))
    // Prefix syntax: "pre"* in FTS5.
    log("prefix \"qui\"* -> ${rows("SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH ? ORDER BY rowid", "\"qui\"*").map { it[0] }}")
    assertEquals(3, rows("SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH ?", "\"qui\"*").size)
    // Ranking.
    log("rank order for quick: ${rows("SELECT rowid, rank FROM chunk_fts WHERE chunk_fts MATCH ? ORDER BY rank", "\"quick\"")}")
    // Vocab.
    log("vocab quick: ${rows("SELECT term, doc, cnt FROM chunk_vocab WHERE term >= 'qu' AND term < 'qv'")}")
    // Query plans for rowid constraints.
    for (sql in listOf(
      "SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH 'quick' AND rowid BETWEEN 2 AND 3",
      "SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH 'quick' AND rowid IN (1, 3)",
      "SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH 'quick' ORDER BY rowid",
      "SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH 'quick' ORDER BY rank LIMIT 2",
    )) log("plan: $sql -> ${rows("EXPLAIN QUERY PLAN $sql").joinToString(" | ") { it.last()!! }}")
    assertEquals(listOf("2", "3"), rows("SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH 'quick' AND rowid BETWEEN 2 AND 3").map { it[0] })
    assertEquals(listOf("1", "3"), rows("SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH 'quick' AND rowid IN (1, 3) ORDER BY rowid").map { it[0] })

    // External content delete, then integrity.
    conn.execSQL("INSERT INTO chunk_fts(chunk_fts, rowid, text) SELECT 'delete', id, text FROM chunk WHERE id = 3")
    conn.execSQL("DELETE FROM chunk WHERE id = 3")
    conn.execSQL("INSERT INTO chunk_fts(chunk_fts, rank) VALUES('integrity-check', 1)")
    log("external-content delete + integrity-check: ok")

    // Contentless delete by rowid range, and CJK bigram phrases.
    conn.execSQL("INSERT INTO cjk_fts(rowid, grams) VALUES (10, '東京 京都 都に に住 住む む')")
    conn.execSQL("INSERT INTO cjk_fts(rowid, grams) VALUES (11, '京都 都 ')")
    assertEquals(listOf("10"), rows("SELECT rowid FROM cjk_fts WHERE cjk_fts MATCH ?", "\"東京 京都\"").map { it[0] })
    assertEquals(listOf("10", "11"), rows("SELECT rowid FROM cjk_fts WHERE cjk_fts MATCH ? ORDER BY rowid", "\"京\"*").map { it[0] })
    assertEquals(listOf("10"), rows("SELECT rowid FROM cjk_fts WHERE cjk_fts MATCH ?", "\"む\"*").map { it[0] })
    conn.execSQL("DELETE FROM cjk_fts WHERE rowid BETWEEN 11 AND 20")
    assertEquals(listOf("10"), rows("SELECT rowid FROM cjk_fts WHERE cjk_fts MATCH ?", "\"京\"*").map { it[0] })
    log("cjk vocab: ${rows("SELECT term, doc FROM cjk_vocab")}")
    log("contentless_delete by rowid range: ok")

    // dbstat
    val dbstat = runCatching { rows("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name ORDER BY 2 DESC") }
    log("dbstat: ${dbstat.getOrNull() ?: "unavailable: ${dbstat.exceptionOrNull()?.message}"}")

    // 'optimize', 'delete-all', incremental vacuum
    conn.execSQL("INSERT INTO chunk_fts(chunk_fts) VALUES('optimize')")
    conn.execSQL("INSERT INTO chunk_fts(chunk_fts) VALUES('delete-all')")
    conn.execSQL("PRAGMA incremental_vacuum")
    assertEquals(0, rows("SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH 'quick'").size)
    log("optimize, delete-all, incremental_vacuum: ok")
  }

  /** Tokenizer and folding parity: every element of the reference books, tokenised by Kotlin and by FTS5 unicode61 remove_diacritics 2. */
  @Test fun tokenizerParity() = runBlocking {
    conn.execSQL("CREATE TABLE chunk (id INTEGER PRIMARY KEY, text TEXT NOT NULL)")
    conn.execSQL("CREATE VIRTUAL TABLE chunk_fts USING fts5(text, content='chunk', content_rowid='id', tokenize='unicode61 remove_diacritics 2', detail=full)")
    conn.execSQL("CREATE VIRTUAL TABLE chunk_inst USING fts5vocab(chunk_fts, 'instance')")
    val books = listOf("ORV.epub", "TASH.epub", "MWM.epub").map { File("/sdcard/Download/quire-spike", it) }.filter { it.isFile }
    assertTrue("reference EPUBs missing under /sdcard/Download/quire-spike", books.isNotEmpty())
    for (book in books) {
      val publication = PublicationLoader(target).open(book).getOrThrow()
      val texts = ArrayList<String>()
      try {
        val iterator = IndexContent(publication).iterator
        while (true) {
          val e = iterator.nextOrNull() ?: break
          val t = (e as? Content.TextElement)?.segments?.joinToString("") { it.text } ?: continue
          if (t.isNotBlank()) texts += t
        }
      } finally { publication.close() }
      conn.execSQL("DELETE FROM chunk")
      conn.execSQL("INSERT INTO chunk_fts(chunk_fts) VALUES('delete-all')")
      conn.execSQL("BEGIN")
      conn.prepare("INSERT INTO chunk(id, text) VALUES (?, ?)").use { st ->
        texts.forEachIndexed { i, t -> st.bindLong(1, i + 1L); st.bindText(2, t); st.step(); st.reset() }
      }
      conn.execSQL("INSERT INTO chunk_fts(chunk_fts) VALUES('rebuild')")
      conn.execSQL("COMMIT")
      val sqlite = HashMap<Long, ArrayList<String>>()
      conn.prepare("SELECT doc, term FROM chunk_inst ORDER BY doc, offset").use { st ->
        while (st.step()) sqlite.getOrPut(st.getLong(0)) { ArrayList() } += st.getText(1)
      }
      var tokens = 0
      var boundaryDiffs = 0
      var foldDiffs = 0
      val examples = ArrayList<String>()
      texts.forEachIndexed { i, t ->
        val kotlin = Tokenizer.tokenize(t).map { t.substring(it.startChar, it.endChar) }
        val theirs = sqlite[i + 1L].orEmpty()
        tokens += theirs.size
        if (kotlin.size != theirs.size) {
          boundaryDiffs++
          if (examples.size < 5) examples += "count ${kotlin.size} vs ${theirs.size}: ${kotlin.zip(theirs).firstOrNull { (a, b) -> foldedTerm(a) != b }} in «${t.take(120)}»"
        } else {
          kotlin.zip(theirs).forEach { (k, s) ->
            if (foldedTerm(k) != s) { foldDiffs++; if (examples.size < 5) examples += "fold '$k' -> '${foldedTerm(k)}' vs '$s'" }
          }
        }
      }
      log("parity ${book.name}: ${texts.size} elements, $tokens tokens, $boundaryDiffs elements with a different token count, $foldDiffs folding differences; ${examples.joinToString(" || ")}")
    }
  }

  private companion object { const val TAG = "Fts5Spike" }
}
