package com.quire.reader.bench

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.quire.reader.bench.legacy.ByteCharMap
import com.quire.reader.bench.legacy.FtsQuery as LegacyQuery
import com.quire.reader.bench.legacy.MappingCodec as LegacyCodec
import com.quire.reader.bench.legacy.buildExcerpt as legacyExcerpt
import com.quire.reader.bench.legacy.firstMatchByte
import com.quire.reader.bench.legacy.fullLocatorJson
import com.quire.reader.data.index.BookRank
import com.quire.reader.data.index.MAX_PREFIX_DOCUMENTS
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.foldedTerm
import com.quire.reader.data.index.highlightRanges
import com.quire.reader.data.index.prefixRangeEnd
import com.quire.reader.data.index.rankBooks

/** What one search came to, the same for every variant, for comparing them. */
data class Summary(val shown: List<Long>, val matchingBooks: Int, val capped: Boolean, val snippets: Int, val incomplete: Boolean = false, val downgraded: String? = null)

/**
 * Today's library search (the TextSearcher and SearchDao of commit 575da52) over the legacy layout: FTS4 with `offsets()`
 * ownership (variant A), or the same layout on FTS5 (variant B), where ownership comes from the first `highlight()` marker
 * instead, since FTS5 has no `offsets()`.
 */
class LegacyEngine(private val c: SQLiteConnection, private val fts5: Boolean, private val cap: Int = 5000) {
  private val docid = if (fts5) "text_chunk_fts.rowid" else "text_chunk_fts.docid"
  private val owner = if (fts5) "highlight(text_chunk_fts, 0, char(57344), char(57345))" else "offsets(text_chunk_fts)"

  private class Row(val id: Long, val bookId: Long, val seq: Int, val lastOpenedAt: Long, val owned: Boolean, val marks: String)

  fun parse(input: String): LegacyQuery.Result.Query? = (LegacyQuery.parse(input) as? LegacyQuery.Result.Query)?.let { q ->
    if (fts5) q.copy(match = q.match.replace(Regex("\\*\"$"), "\"*")) else q
  }

  private fun withoutPrefix(q: LegacyQuery.Result.Query) = if (q.prefix == null) q else q.copy(match = q.match.removeSuffix(if (fts5) "\"*" else "*\"") + (if (fts5) "\"" else "\""), prefix = null)

  private fun filterSql(f: TextSearchFilters): Pair<String, List<Any>> = when {
    f.author != null -> " AND b.primaryAuthor = ?" to listOf(f.author!!)
    f.tag != null -> " AND EXISTS (SELECT 1 FROM book_tag t WHERE t.bookId = b.id AND t.tag = ?)" to listOf(f.tag!!)
    f.status != null -> " AND st.status = ?" to listOf(f.status!!.name.lowercase())
    else -> "" to emptyList()
  }

  private fun <T> rows(sql: String, args: List<Any>, read: (SQLiteStatement) -> T): List<T> = c.prepare(sql).use { st ->
    args.forEachIndexed { i, a -> when (a) { is Long -> st.bindLong(i + 1, a); is Int -> st.bindLong(i + 1, a.toLong()); else -> st.bindText(i + 1, a.toString()) } }
    buildList { while (st.step()) add(read(st)) }
  }

  private fun matches(match: String, cap: Int, min: Long, max: Long, f: TextSearchFilters): List<Row> {
    val (fs, fa) = filterSql(f)
    return rows(
      """SELECT c.id, c.bookId, c.seq, COALESCE(st.lastOpenedAt, 0), c.primaryEndByte, c.primaryEndChar, $owner
      FROM text_chunk_fts
      CROSS JOIN text_chunk c ON c.id = $docid
      CROSS JOIN index_state s ON s.bookId = c.bookId AND s.status = 'done'
      CROSS JOIN book b ON b.id = c.bookId AND b.mtime = s.mtime AND b.sizeBytes = s.sizeBytes AND b.readable = 1 AND b.missingSince IS NULL
      LEFT JOIN book_state st ON st.bookId = b.id
      WHERE text_chunk_fts MATCH ? AND $docid >= ? AND $docid <= ?$fs
      ORDER BY $docid LIMIT ?""",
      listOf(match, min, max) + fa + listOf(cap),
    ) { st ->
      val marks = st.getText(6)
      val owned = if (fts5) marks.indexOf('\uE000').let { it >= 0 && it < st.getLong(5) } else (firstMatchByte(marks)?.let { it < st.getLong(4) } == true)
      Row(st.getLong(0), st.getLong(1), st.getLong(2).toInt(), st.getLong(3), owned, marks)
    }
  }

  private fun isCommonPrefix(prefix: String): Boolean {
    val from = foldedTerm(prefix)
    val until = prefixRangeEnd(from)
    if (fts5) {
      var docs = 0L
      return c.prepare("SELECT doc FROM text_chunk_fts_terms WHERE term >= ? AND term < ?").use { st ->
        st.bindText(1, from); st.bindText(2, until)
        while (st.step()) { docs += st.getLong(0); if (docs > MAX_PREFIX_DOCUMENTS) return@use true }
        false
      }
    }
    var after: String? = null
    var documents = 0L
    while (true) {
      val page = rows("SELECT term, documents FROM text_chunk_fts_terms WHERE col = '*' AND term ${if (after == null) ">=" else ">"} ? AND term < ? ORDER BY term LIMIT 16", listOf(after ?: from, until)) { it.getText(0) to it.getLong(1) }
      for ((_, d) in page) { documents += d; if (documents > MAX_PREFIX_DOCUMENTS) return true }
      if (page.size < 16) return false
      after = page.last().first
    }
  }

  private class Sample(val rows: List<Row>, val capped: Boolean, val incomplete: Boolean = false)

  private fun sample(match: String, f: TextSearchFilters): Sample {
    if (f == TextSearchFilters.None) return matches(match, cap, 0, Long.MAX_VALUE, f).let { Sample(it, it.size >= cap) }
    val (fs, fa) = filterSql(f)
    val allowed = rows(
      "SELECT b.id FROM book b CROSS JOIN index_state s ON s.bookId = b.id AND s.status = 'done' AND s.mtime = b.mtime AND s.sizeBytes = b.sizeBytes LEFT JOIN book_state st ON st.bookId = b.id WHERE b.readable = 1 AND b.missingSince IS NULL$fs ORDER BY s.completedAt, b.id",
      fa,
    ) { it.getLong(0) }
    if (allowed.isEmpty()) return Sample(emptyList(), false)
    fun inBooks(ids: List<Long>): List<Row> {
      val ranges = ids.mapNotNull { id -> rows("SELECT MIN(id), MAX(id) FROM text_chunk WHERE bookId = ?", listOf(id)) { if (it.isNull(0)) null else it.getLong(0) to it.getLong(1) }.single() }.sortedBy { it.first }
      val out = ArrayList<Row>()
      for ((a, b) in ranges) { if (out.size >= cap) break; out += matches(match, cap - out.size, a, b, f) }
      return out
    }
    if (allowed.size <= 40) return inBooks(allowed).let { Sample(it, it.size >= cap) }
    val scanLimit = cap * 8
    val (count, last) = rows("SELECT COUNT(*), MAX($docid) FROM (SELECT $docid FROM text_chunk_fts WHERE text_chunk_fts MATCH ? ORDER BY $docid LIMIT ?)".replace("$docid", if (fts5) "rowid" else "docid"), listOf(match, scanLimit)) { it.getLong(0) to (if (it.isNull(1)) null else it.getLong(1)) }.single()
    if (last == null) return Sample(emptyList(), false)
    if (count < scanLimit) return matches(match, cap, 0, last, f).let { Sample(it, it.size >= cap) }
    val probed = allowed.take(8)
    val r = inBooks(probed)
    val capped = r.size >= cap
    return Sample(r, capped, !capped && allowed.size > probed.size)
  }

  fun search(input: String, f: TextSearchFilters): Summary {
    var q = parse(input) ?: return Summary(emptyList(), 0, false, 0)
    val common = q.prefix?.takeIf { isCommonPrefix(it) }
    if (common != null) q = withoutPrefix(q)
    val s = sample(q.match, f)
    val owned = s.rows.filter { it.owned }
    val perBook = owned.groupBy { it.bookId }
    val ranked = perBook.map { (id, r) -> BookRank(id, r.size, r.first().lastOpenedAt) }.sortedWith(rankBooks).take(40)
    val snippetRows = ranked.flatMap { r -> perBook.getValue(r.bookId).sortedBy { it.seq }.take(5) }
    val ids = ranked.map { it.bookId }
    if (ids.isNotEmpty()) {
      rows("SELECT id, title, path FROM book WHERE id IN (${ids.joinToString(",")})", emptyList()) { it.getText(1) }
      rows("SELECT bookId, mtime, sizeBytes, truncated, unreadableResources FROM index_state WHERE status = 'done' AND bookId IN (${ids.joinToString(",")})", emptyList()) { it.getLong(0) }
    }
    val snippets = snippetCount(snippetRows)
    return Summary(ids, perBook.size, s.capped, snippets, s.incomplete, common)
  }

  private fun snippetCount(snippetRows: List<Row>): Int {
    if (snippetRows.isEmpty()) return 0
    val byId = snippetRows.associateBy { it.id }
    val chunks = rows("SELECT id, text, mapping FROM text_chunk WHERE id IN (${byId.keys.joinToString(",")})", emptyList()) { Triple(it.getLong(0), it.getText(1), it.getText(2)) }
    return chunks.count { (id, text, mapping) ->
      val offsets = if (fts5) {
        val map = ByteCharMap(text)
        highlightRanges(byId.getValue(id).marks).withIndex().joinToString(" ") { (i, r) -> "0 $i ${map.byteOf(r.first)} ${map.byteOf(r.last + 1) - map.byteOf(r.first)}" }
      } else byId.getValue(id).marks
      val segments = runCatching { LegacyCodec.decode(mapping) }.getOrNull() ?: return@count false
      legacyExcerpt(text, segments, offsets)?.target?.fullLocatorJson() != null
    }
  }

  /** "Show all in this book", first page of 20 (the prefix is kept, as today). */
  fun page(input: String, bookId: Long): Int {
    val q = parse(input) ?: return 0
    val ownedSql = if (fts5) "" else """
      AND CAST(substr(substr(offsets(text_chunk_fts), instr(offsets(text_chunk_fts), ' ') + 1),
                       instr(substr(offsets(text_chunk_fts), instr(offsets(text_chunk_fts), ' ') + 1), ' ') + 1) AS INTEGER) < c.primaryEndByte"""
    val r = rows(
      """SELECT c.id, c.bookId, c.seq, 0, c.primaryEndByte, c.primaryEndChar, $owner
      FROM text_chunk_fts
      CROSS JOIN text_chunk c ON c.id = $docid
      CROSS JOIN index_state s ON s.bookId = c.bookId AND s.status = 'done'
      CROSS JOIN book b ON b.id = c.bookId AND b.mtime = s.mtime AND b.sizeBytes = s.sizeBytes AND b.readable = 1 AND b.missingSince IS NULL
      WHERE text_chunk_fts MATCH ?
        AND $docid >= (SELECT MIN(id) FROM text_chunk WHERE bookId = ? AND seq > -1)
        AND $docid <= (SELECT MAX(id) FROM text_chunk WHERE bookId = ?)
        AND c.bookId = ?$ownedSql
      ORDER BY $docid LIMIT ?""",
      listOf(q.match, bookId, bookId, bookId, if (fts5) 200 else 21),
    ) { st ->
      val marks = st.getText(6)
      Row(st.getLong(0), st.getLong(1), st.getLong(2).toInt(), 0, if (fts5) marks.indexOf('\uE000').let { it >= 0 && it < st.getLong(5) } else true, marks)
    }.filter { it.owned }.take(21)
    return snippetCount(r.take(20))
  }

  /** Every book with a match for [match] as written (no guard), for comparing result sets. */
  fun bookSet(input: String): Set<Long> {
    val q = parse(input) ?: return emptySet()
    return rows("SELECT DISTINCT c.bookId FROM text_chunk_fts JOIN text_chunk c ON c.id = $docid WHERE text_chunk_fts MATCH ?", listOf(q.match)) { it.getLong(0) }.toSet()
  }
}
