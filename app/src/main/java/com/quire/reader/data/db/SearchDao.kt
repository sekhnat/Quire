package com.quire.reader.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * A matching passage found by a library search. [offsets] is the raw FTS `offsets()` string; the passage is owned by
 * its chunk only if the first match in it starts before [primaryEndByte].
 */
data class MatchRow(val id: Long, val bookId: Long, val seq: Int, val lastOpenedAt: Long, val primaryEndByte: Int, val offsets: String)

/** How far a bounded scan of a query's matches got: [matchCount] matches were read (at most the limit), the last with docid [lastDocid]. */
data class ScanBound(val matchCount: Int, val lastDocid: Long?)

/** The ids of a book's first and last chunk, both null when it has none. */
data class DocidRange(val firstId: Long?, val lastId: Long?)

/** A term in the full-text index and the number of chunks it appears in. */
data class TermFrequency(val term: String, val documents: Int)

/** A chunk loaded to build a snippet from. */
data class ChunkRow(val id: Long, val bookId: Long, val seq: Int, val chapter: String, val progression: Double, val text: String, val mapping: String)

/** One matching passage of a single book, with what the snippet needs and the raw FTS `offsets()` string. */
data class PageRow(val seq: Int, val chapter: String, val progression: Double, val text: String, val mapping: String, val offsets: String)

/** A book's index as search sees it: the signature the text was indexed from, whether the index stops early, and how many resources could not be read. */
data class IndexedBook(val bookId: Long, val mtime: Long, val sizeBytes: Long, val truncated: Boolean, val unreadableResources: Int)

/**
 * The library filters as a clause on a `book b` joined with `book_state st`: author, series and tag are exact matches and
 * the status is a [com.quire.reader.data.index.TextStatusFilter] name in lower case (`reading`, `unread`, `finished`,
 * `recent`). Any of them may be null for "no restriction"; an unread book is any that is not reading or finished, and a
 * recent one is unread and added after `:newSince`.
 */
private const val FILTERS_SQL = """
      AND (:author IS NULL OR b.primaryAuthor = :author)
      AND (:series IS NULL OR b.series = :series)
      AND (:tag IS NULL OR EXISTS (SELECT 1 FROM book_tag t WHERE t.bookId = b.id AND t.tag = :tag))
      AND (:status IS NULL
        OR (:status = 'reading' AND st.status = 'reading')
        OR (:status = 'finished' AND st.status = 'finished')
        OR (:status IN ('unread', 'recent') AND COALESCE(st.status, 'unread') NOT IN ('reading', 'finished')
            AND (:status = 'unread' OR b.addedAt > :newSince)))"""

/**
 * The full-text queries. A passage counts only while its book is readable and its index state is `done` for the
 * book's current file signature, so stale or unreadable books never match.
 *
 * Ownership: each chunk also stores the first tokens of the text after it, so a match near a chunk's end appears in two
 * rows. The first match offset in a row (the third number of `offsets()`) is the lowest, and the row owns the match only
 * if that offset is before `primaryEndByte`. The paged query below checks that in SQL; the library query leaves it to
 * [com.quire.reader.data.index.firstMatchByte].
 */
@Dao
abstract class SearchDao {
  /**
   * The library's first [cap] matching passages in index order with docid from [minDocid] to [maxDocid], in books that are
   * valid and that the filters allow, each with its raw `offsets()`. A caller keeps the owned ones and counts the rows to
   * know how many passages were examined.
   *
   * The cap counts passages that pass the validity and filter joins, so filters do not shrink the sample, and it is applied
   * before the caller does any per-row work (`offsets()` costs about 5 µs a row). `CROSS JOIN` pins the join order: the
   * match runs first and every other table is a primary-key lookup per hit. A restrictive filter makes the scan walk many
   * matches that are then rejected (about 6 µs each), so callers confine it to docid ranges ([bookRange]) or bound it
   * ([scanBound]). A copy of a match always comes after its owner in the same book, so the sample holds the owner whenever
   * it holds a copy.
   */
  @Query(
    """
    SELECT c.id AS id, c.bookId AS bookId, c.seq AS seq, COALESCE(st.lastOpenedAt, 0) AS lastOpenedAt,
           c.primaryEndByte AS primaryEndByte, offsets(text_chunk_fts) AS offsets
    FROM text_chunk_fts
    CROSS JOIN text_chunk c ON c.id = text_chunk_fts.docid
    CROSS JOIN index_state s ON s.bookId = c.bookId AND s.status = 'done'
    CROSS JOIN book b ON b.id = c.bookId AND b.mtime = s.mtime AND b.sizeBytes = s.sizeBytes AND b.readable = 1 AND b.missingSince IS NULL
    LEFT JOIN book_state st ON st.bookId = b.id
    WHERE text_chunk_fts MATCH :match
      AND text_chunk_fts.docid >= :minDocid AND text_chunk_fts.docid <= :maxDocid""" + FILTERS_SQL + """
    ORDER BY text_chunk_fts.docid
    LIMIT :cap
    """,
  )
  abstract suspend fun libraryMatches(
    match: String, cap: Int, minDocid: Long, maxDocid: Long, author: String?, series: String?, tag: String?, status: String?, newSince: Long,
  ): List<MatchRow>

  /** The valid books the filters allow, oldest-indexed first (their chunks come first in the index), so a caller can tell a narrow filter from a broad one. */
  @Query(
    """
    SELECT b.id FROM book b
    CROSS JOIN index_state s ON s.bookId = b.id AND s.status = 'done' AND s.mtime = b.mtime AND s.sizeBytes = b.sizeBytes
    LEFT JOIN book_state st ON st.bookId = b.id
    WHERE b.readable = 1 AND b.missingSince IS NULL""" + FILTERS_SQL + """
    ORDER BY s.completedAt, b.id
    """,
  )
  abstract suspend fun allowedBookIds(author: String?, series: String?, tag: String?, status: String?, newSince: Long): List<Long>

  @RawQuery
  protected abstract suspend fun termRows(query: SupportSQLiteQuery): List<TermFrequency>

  /**
   * Up to [limit] indexed terms from [from] (inclusive) to [until] (exclusive), in term order, with their document counts;
   * with [after] set, those sorting after it instead of from [from]. Reading a term's count walks its whole doclist, so
   * callers page through and stop early. A raw query because the terms table is created at open time, which Room cannot
   * check at build time. It uses exactly one lower bound: `fts4aux` pushes only one down and scans from the first term
   * when it picks the wrong one.
   */
  suspend fun terms(from: String, until: String, after: String?, limit: Int): List<TermFrequency> =
    termRows(
      SimpleSQLiteQuery(
        "SELECT term, documents FROM ${QuireDatabase.FTS_TERMS_TABLE} WHERE col = '*' AND term ${if (after == null) ">=" else ">"} ? AND term < ? ORDER BY term LIMIT ?",
        arrayOf<Any>(after ?: from, until, limit),
      ),
    )

  /** The docid range of a book's chunks, which are inserted together and so are contiguous; null ends when it has none. */
  @Query("SELECT MIN(id) AS firstId, MAX(id) AS lastId FROM text_chunk WHERE bookId = :bookId")
  abstract suspend fun bookRange(bookId: Long): DocidRange

  /** Reads at most [limit] of the matches of [match] without looking at the books they are in: cheap, and the way to bound a filtered scan. */
  @Query("SELECT COUNT(*) AS matchCount, MAX(docid) AS lastDocid FROM (SELECT docid FROM text_chunk_fts WHERE text_chunk_fts MATCH :match ORDER BY docid LIMIT :limit)")
  abstract suspend fun scanBound(match: String, limit: Int): ScanBound

  /**
   * Up to [limit] owned matching passages of [bookId] with a `seq` after [afterSeq] (-1 for the first page), in reading
   * order. The full-text search is confined to the book's docid range: a book's chunks are inserted together so their ids
   * ascend with `seq`, and without the range a common word would be walked through the whole library first.
   */
  @Query(
    """
    SELECT c.seq AS seq, c.chapter AS chapter, c.progression AS progression, c.text AS text, c.mapping AS mapping,
           offsets(text_chunk_fts) AS offsets
    FROM text_chunk_fts
    CROSS JOIN text_chunk c ON c.id = text_chunk_fts.docid
    CROSS JOIN index_state s ON s.bookId = c.bookId AND s.status = 'done'
    CROSS JOIN book b ON b.id = c.bookId AND b.mtime = s.mtime AND b.sizeBytes = s.sizeBytes AND b.readable = 1 AND b.missingSince IS NULL
    WHERE text_chunk_fts MATCH :match
      AND text_chunk_fts.docid >= (SELECT MIN(id) FROM text_chunk WHERE bookId = :bookId AND seq > :afterSeq)
      AND text_chunk_fts.docid <= (SELECT MAX(id) FROM text_chunk WHERE bookId = :bookId)
      AND c.bookId = :bookId
      AND CAST(substr(substr(offsets(text_chunk_fts), instr(offsets(text_chunk_fts), ' ') + 1),
                       instr(substr(offsets(text_chunk_fts), instr(offsets(text_chunk_fts), ' ') + 1), ' ') + 1) AS INTEGER) < c.primaryEndByte
    ORDER BY text_chunk_fts.docid
    LIMIT :limit
    """,
  )
  abstract suspend fun bookMatches(match: String, bookId: Long, afterSeq: Int, limit: Int): List<PageRow>

  @Query("SELECT id, bookId, seq, chapter, progression, text, mapping FROM text_chunk WHERE id IN (:ids)")
  abstract suspend fun chunks(ids: List<Long>): List<ChunkRow>

  @Query("SELECT bookId, mtime, sizeBytes, truncated, unreadableResources FROM index_state WHERE status = 'done' AND bookId IN (:bookIds)")
  abstract suspend fun indexedBooks(bookIds: List<Long>): List<IndexedBook>
}
