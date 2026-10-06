package com.quire.reader.data.db

import androidx.room.Dao
import androidx.room.Query

/** A book search may show: its file signature (which must match its index state) and when it was last opened (0 for never). */
data class SearchableBook(val id: Long, val mtime: Long, val sizeBytes: Long, val lastOpenedAt: Long)

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
 * The user-data side of library text search. The index itself lives in [IndexDatabase], a separate SQLite build, so the
 * two are joined in Kotlin: these are the books that may appear, and the index says which of them match.
 */
@Dao
abstract class SearchDao {
  /** Readable, present books that the filters allow. */
  @Query(
    """
    SELECT b.id AS id, b.mtime AS mtime, b.sizeBytes AS sizeBytes, COALESCE(st.lastOpenedAt, 0) AS lastOpenedAt
    FROM book b LEFT JOIN book_state st ON st.bookId = b.id
    WHERE b.readable = 1 AND b.missingSince IS NULL""" + FILTERS_SQL,
  )
  abstract suspend fun searchableBooks(author: String?, series: String?, tag: String?, status: String?, newSince: Long): List<SearchableBook>

  /** One book if it is readable and present, whatever the filters. */
  @Query(
    """
    SELECT b.id AS id, b.mtime AS mtime, b.sizeBytes AS sizeBytes, COALESCE(st.lastOpenedAt, 0) AS lastOpenedAt
    FROM book b LEFT JOIN book_state st ON st.bookId = b.id
    WHERE b.id = :bookId AND b.readable = 1 AND b.missingSince IS NULL
    """,
  )
  abstract suspend fun searchableBook(bookId: Long): SearchableBook?
}
