package com.quire.reader.data.index

import com.quire.reader.data.db.IndexStateEntity

/** A chunk as a snippet needs it: its text, mapping and place in the book. */
class StoredChunk(
  val id: Long,
  val seq: Int,
  val chapter: String,
  val href: String,
  val mediaType: String?,
  val progression: Double,
  val text: String,
  val mapping: ByteArray,
)

/** A seam whose text matches a query, with the match ranges in its text. */
class SeamHit(val chunkId: Long, val text: String, val splitChar: Int, val ranges: List<IntRange>) {
  /** True when a match covers the split, i.e. runs from the chunk before it into this one. */
  val crossesSplit: Boolean get() = ranges.any { it.first < splitChar && it.last >= splitChar }

  /** The part of the first crossing match that falls in the chunk after the split, in that chunk's chars. */
  val rangeInChunk: IntRange? get() = ranges.firstOrNull { it.first < splitChar && it.last >= splitChar }?.let { 0..(it.last - splitChar) }
}

/**
 * Reads and writes the index database (see [com.quire.reader.data.db.IndexDatabase]). Writes keep the external-content and
 * contentless full-text tables in step with `chunk` and `seam` in the same transaction, since there are no triggers.
 */
class IndexStore(private val sql: IndexSql) {
  companion object {
    /** Pages of merging per [mergeStep]: a fraction of a second of work. */
    const val MERGE_PAGES = 2_000
  }

  // ── writes ───────────────────────────────────────────────────────────────

  /**
   * Swaps a book's chunks for [chunks] and records it `done` for the signature, all in one transaction, so readers see
   * either the previous index or the new one. The new chunks get consecutive ids after every id in use.
   */
  suspend fun replaceBook(
    bookId: Long, mtime: Long, sizeBytes: Long, chunks: List<IndexChunk>, truncated: Boolean, unreadableResources: Int,
    completedAt: Long = System.currentTimeMillis(),
  ) = sql.write { c ->
    c.deleteBook(bookId)
    val base = (c.long("SELECT MAX(id) FROM chunk") ?: 0L) + 1
    val strings = BookStrings()
    for (chunk in chunks) { strings.href(chunk.href, chunk.mediaType); strings.chapter(chunk.chapter) }
    c.statement("INSERT INTO book_string (book_id, idx, value, media_type) VALUES (?, ?, ?, ?)") { st ->
      strings.rows.forEachIndexed { i, (value, mediaType) ->
        st.bindLong(1, bookId); st.bindLong(2, i.toLong()); st.bindText(3, value)
        if (mediaType == null) st.bindNull(4) else st.bindText(4, mediaType)
        st.step(); st.reset()
      }
    }
    c.statement("INSERT INTO chunk (id, book_id, seq, href_idx, chapter_idx, progression, text, mapping) VALUES (?, ?, ?, ?, ?, ?, ?, ?)") { st ->
      chunks.forEachIndexed { i, chunk ->
        st.bindLong(1, base + i); st.bindLong(2, bookId); st.bindLong(3, i.toLong())
        st.bindLong(4, strings.href(chunk.href, chunk.mediaType).toLong()); st.bindLong(5, strings.chapter(chunk.chapter).toLong())
        st.bindDouble(6, chunk.progression); st.bindText(7, chunk.text); st.bindBlob(8, chunk.mapping)
        st.step(); st.reset()
      }
    }
    c.statement("INSERT INTO chunk_fts (rowid, text) VALUES (?, ?)") { st ->
      chunks.forEachIndexed { i, chunk -> st.bindLong(1, base + i); st.bindText(2, chunk.text); st.step(); st.reset() }
    }
    c.statement("INSERT INTO seam (id, text, split_char) VALUES (?, ?, ?)") { st ->
      chunks.forEachIndexed { i, chunk -> chunk.seam?.let { s -> st.bindLong(1, base + i); st.bindText(2, s.text); st.bindLong(3, s.splitChar.toLong()); st.step(); st.reset() } }
    }
    c.statement("INSERT INTO seam_fts (rowid, text) VALUES (?, ?)") { st ->
      chunks.forEachIndexed { i, chunk -> chunk.seam?.let { s -> st.bindLong(1, base + i); st.bindText(2, s.text); st.step(); st.reset() } }
    }
    c.statement("INSERT INTO cjk_fts (rowid, grams) VALUES (?, ?)") { st ->
      chunks.forEachIndexed { i, chunk -> CjkGrams.grams(chunk.text)?.let { g -> st.bindLong(1, base + i); st.bindText(2, g); st.step(); st.reset() } }
    }
    c.putState(
      IndexStateEntity(
        bookId, mtime, sizeBytes, IndexStateEntity.STATUS_DONE, completedAt, chunkCount = chunks.size,
        textBytes = chunks.sumOf { it.text.utf8Length().toLong() }, truncated = truncated, unreadableResources = unreadableResources,
        firstChunkId = if (chunks.isEmpty()) null else base, lastChunkId = if (chunks.isEmpty()) null else base + chunks.size - 1,
      ),
    )
  }

  /** Records a `failed` or `skipped` outcome for the signature and drops the book's obsolete chunks. */
  suspend fun markTerminal(bookId: Long, mtime: Long, sizeBytes: Long, status: String, completedAt: Long = System.currentTimeMillis()) {
    require(status == IndexStateEntity.STATUS_FAILED || status == IndexStateEntity.STATUS_SKIPPED) { "not a chunk-less terminal status: $status" }
    sql.write { c ->
      c.deleteBook(bookId)
      c.putState(IndexStateEntity(bookId, mtime, sizeBytes, status, completedAt))
    }
  }

  /** Forgets the books [bookIds]: their chunks and their index state. */
  suspend fun removeBooks(bookIds: Collection<Long>) {
    if (bookIds.isEmpty()) return
    sql.write { c ->
      for (id in bookIds) {
        c.deleteBook(id)
        c.exec("DELETE FROM index_state WHERE bookId = ?", id)
      }
    }
  }

  /** Forgets every book not in [bookIds] (removed from the library, so nothing cascades to the index any more); returns how many. */
  suspend fun retainOnly(bookIds: Collection<Long>): Int {
    val keep = bookIds.toHashSet()
    val known = sql.read { c ->
      c.statement("SELECT bookId FROM index_state UNION SELECT DISTINCT book_id FROM chunk") { st -> buildList { while (st.step()) add(st.getLong(0)) } }
    }
    val gone = known.filter { it !in keep }
    removeBooks(gone)
    return gone.size
  }

  /**
   * Forgets every indexing decision and all indexed text. The full-text tables are emptied with `delete-all`, which costs
   * nothing per row, so this is quick at any size.
   */
  suspend fun clearAll() = sql.write { c ->
    for (table in listOf("chunk_fts", "seam_fts", "cjk_fts")) c.exec("INSERT INTO $table($table) VALUES('delete-all')")
    for (table in listOf("chunk", "seam", "book_string", "index_state")) c.exec("DELETE FROM $table")
  }

  /**
   * Returns free pages to the file system (the file uses incremental auto-vacuum) and shrinks the write-ahead log, which
   * otherwise stays as large as the biggest transaction since the last truncating checkpoint.
   */
  suspend fun incrementalVacuum() {
    sql.exec("PRAGMA incremental_vacuum")
    sql.exec("PRAGMA wal_checkpoint(TRUNCATE)")
  }

  /**
   * Does a bounded amount of the merging that makes the full-text tables fastest to query (the work of `optimize`, in steps
   * of about [pages] pages, so a caller can stop between them). Returns true once nothing is left to merge.
   */
  suspend fun mergeStep(pages: Int = MERGE_PAGES): Boolean {
    var idle = true
    for (table in listOf("chunk_fts", "seam_fts", "cjk_fts")) {
      // FTS5 documents repeating 'merge' until it changes fewer than two rows as the sign that the b-tree is fully merged.
      val changes = sql.write { c ->
        val before = c.long("SELECT total_changes()") ?: 0L
        c.exec("INSERT INTO $table($table, rank) VALUES('merge', ?)", pages)
        (c.long("SELECT total_changes()") ?: 0L) - before
      }
      if (changes >= 2) idle = false
    }
    return idle
  }

  /** Merges every full-text table completely in one go; for tests and tools, since it cannot be interrupted. */
  suspend fun optimize() = sql.write { c ->
    for (table in listOf("chunk_fts", "seam_fts", "cjk_fts")) c.exec("INSERT INTO $table($table) VALUES('optimize')")
  }

  private suspend fun IndexConnection.deleteBook(bookId: Long) {
    val first = long("SELECT MIN(id) FROM chunk WHERE book_id = ?", bookId) ?: run {
      exec("DELETE FROM book_string WHERE book_id = ?", bookId)
      return
    }
    val last = long("SELECT MAX(id) FROM chunk WHERE book_id = ?", bookId)!!
    exec("INSERT INTO chunk_fts (chunk_fts, rowid, text) SELECT 'delete', id, text FROM chunk WHERE book_id = ?", bookId)
    exec("INSERT INTO seam_fts (seam_fts, rowid, text) SELECT 'delete', id, text FROM seam WHERE id BETWEEN ? AND ?", first, last)
    exec("DELETE FROM cjk_fts WHERE rowid BETWEEN ? AND ?", first, last)
    exec("DELETE FROM seam WHERE id BETWEEN ? AND ?", first, last)
    exec("DELETE FROM chunk WHERE book_id = ?", bookId)
    exec("DELETE FROM book_string WHERE book_id = ?", bookId)
  }

  private suspend fun IndexConnection.putState(s: IndexStateEntity) = exec(
    "INSERT OR REPLACE INTO index_state (bookId, mtime, sizeBytes, status, completedAt, chunkCount, textBytes, truncated, unreadableResources, firstChunkId, lastChunkId) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    s.bookId, s.mtime, s.sizeBytes, s.status, s.completedAt, s.chunkCount, s.textBytes, s.truncated, s.unreadableResources, s.firstChunkId, s.lastChunkId,
  )

  /** A book's distinct strings, numbered in first-use order. */
  private class BookStrings {
    val rows = ArrayList<Pair<String, String?>>()
    private val hrefs = HashMap<String, Int>()
    private val chapters = HashMap<String, Int>()

    fun href(value: String, mediaType: String?): Int = hrefs.getOrPut(value) { rows += value to mediaType; rows.size - 1 }
    fun chapter(value: String): Int = chapters.getOrPut(value) { rows += value to null; rows.size - 1 }
  }

  // ── reads ────────────────────────────────────────────────────────────────

  /**
   * Streams the ids of chunks matching [match] in `chunk_fts` (or `cjk_fts` when [cjk]) from [from] to [to], in id order,
   * to [onRow] until it returns false. With [alsoCjk], only chunks that match it in `cjk_fts` too.
   */
  suspend fun matchingIds(match: String, from: Long, to: Long, cjk: Boolean = false, alsoCjk: String? = null, onRow: (Long) -> Boolean) = sql.read { c ->
    val table = if (cjk) "cjk_fts" else "chunk_fts"
    val also = if (alsoCjk != null) " AND rowid IN (SELECT rowid FROM cjk_fts WHERE cjk_fts MATCH ?)" else ""
    c.statement("SELECT rowid FROM $table WHERE $table MATCH ? AND rowid BETWEEN ? AND ?$also ORDER BY rowid") { st ->
      st.bindText(1, match); st.bindLong(2, from); st.bindLong(3, to)
      if (alsoCjk != null) st.bindText(4, alsoCjk)
      while (st.step()) if (!onRow(st.getLong(0))) break
    }
  }

  /** The best [limit] chunk ids for [match] by BM25, from [from] to [to], best first. */
  suspend fun ranked(match: String, from: Long, to: Long, limit: Int, cjk: Boolean = false): List<Long> = sql.read { c ->
    val table = if (cjk) "cjk_fts" else "chunk_fts"
    c.statement("SELECT rowid FROM $table WHERE $table MATCH ? AND rowid BETWEEN ? AND ? ORDER BY rank LIMIT ?") { st ->
      st.bindText(1, match); st.bindLong(2, from); st.bindLong(3, to); st.bindLong(4, limit.toLong())
      buildList { while (st.step()) add(st.getLong(0)) }
    }
  }

  /** The seams from [from] to [to] that match the phrase [match], with the matched ranges of their text. */
  suspend fun seamHits(match: String, from: Long, to: Long): List<SeamHit> = sql.read { c ->
    c.statement(
      "SELECT s.id, highlight(seam_fts, 0, char(57344), char(57345)), s.split_char FROM seam_fts JOIN seam s ON s.id = seam_fts.rowid " +
        "WHERE seam_fts MATCH ? AND seam_fts.rowid BETWEEN ? AND ? ORDER BY seam_fts.rowid",
    ) { st ->
      st.bindText(1, match); st.bindLong(2, from); st.bindLong(3, to)
      buildList {
        while (st.step()) {
          val marked = st.getText(1)
          add(SeamHit(st.getLong(0), unmark(marked), st.getLong(2).toInt(), highlightRanges(marked)))
        }
      }
    }
  }

  /**
   * `highlight()` output for each chunk in [ids] that matches [match], by chunk id. One lookup per chunk by `rowid = ?`,
   * which FTS5 seeks to directly; asking for them with `rowid IN (...)` instead makes it walk every match of the query first
   * (measured: 89 ms for 113 chunks of a mid-common word, 10 s for 50 chunks of a very common one).
   */
  suspend fun highlights(match: String, ids: Collection<Long>): Map<Long, String> = if (ids.isEmpty()) emptyMap() else sql.read { c ->
    c.statement("SELECT highlight(chunk_fts, 0, char(57344), char(57345)) FROM chunk_fts WHERE chunk_fts MATCH ? AND rowid = ?") { st ->
      buildMap {
        for (id in ids) {
          st.bindText(1, match); st.bindLong(2, id)
          if (st.step()) put(id, st.getText(0))
          st.reset()
        }
      }
    }
  }

  suspend fun chunks(ids: Collection<Long>): Map<Long, StoredChunk> = if (ids.isEmpty()) emptyMap() else sql.read { c ->
    c.statement(
      "SELECT c.id, c.seq, ch.value, h.value, h.media_type, c.progression, c.text, c.mapping FROM chunk c " +
        "JOIN book_string h ON h.book_id = c.book_id AND h.idx = c.href_idx " +
        "JOIN book_string ch ON ch.book_id = c.book_id AND ch.idx = c.chapter_idx WHERE c.id IN (${ids.joinToString(",")})",
    ) { st ->
      buildMap {
        while (st.step()) {
          val id = st.getLong(0)
          put(id, StoredChunk(id, st.getLong(1).toInt(), st.getText(2), st.getText(3), if (st.isNull(4)) null else st.getText(4), st.getDouble(5), st.getText(6), st.getBlob(7)))
        }
      }
    }
  }

  /**
   * Whether more than [limit] chunks hold a term from [from] (inclusive) to [until] (exclusive) in the vocabulary of
   * `chunk_fts` (or `cjk_fts`). Terms are read in pages, and counting stops as soon as the limit is passed, so a common
   * prefix costs about the limit's worth of document reads, not the prefix's.
   */
  suspend fun termDocumentsExceed(from: String, until: String, limit: Long, cjk: Boolean = false): Boolean = sql.read { c ->
    val table = if (cjk) "cjk_terms" else "chunk_terms"
    c.statement("SELECT doc FROM $table WHERE term >= ? AND term < ?") { st ->
      st.bindText(1, from); st.bindText(2, until)
      var documents = 0L
      while (st.step()) {
        documents += st.getLong(0)
        if (documents > limit) return@statement true
      }
      false
    }
  }

  private fun unmark(marked: String) = marked.replace(TextChunker.HIGHLIGHT_OPEN.toString(), "").replace(TextChunker.HIGHLIGHT_CLOSE.toString(), "")
}
