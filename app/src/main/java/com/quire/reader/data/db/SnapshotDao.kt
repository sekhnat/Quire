package com.quire.reader.data.db

import androidx.room.Dao
import androidx.room.Query

/** One book's user-authored data as the snapshot capture reads it; see `SnapshotWriter`. */
data class SnapshotBookRow(
  val id: Long,
  val path: String,
  val title: String,
  val author: String,
  val addedAt: Long,
  val missingSince: Long?,
  val calibreUuid: String?,
  val epubUid: String?,
  val fingerprint: String?,
  val locatorJson: String?,
  val progress: Float?,
  val status: String?,
  val lastOpenedAt: Long?,
  val finishedAt: Long?,
  val userRating: Int?,
  val prefsJson: String?,
)

/** A tag the user added to one book, as the snapshot capture reads it. */
data class SnapshotTagRow(val bookId: Long, val tag: String)

/**
 * Batched reads of everything the user authored, for the snapshot and its exports. Only books with
 * user data come back — books the user never touched are derived from the files on a rescan — and
 * missing books are included, since their history is all they have. The capture runs the row query
 * once and then the annotation queries in id batches, all inside one read transaction.
 */
@Dao
interface SnapshotDao {
  /** The books with reading history, annotations or user tags, live and missing alike. */
  @Query(
    """
    SELECT b.id AS id, b.path AS path, b.title AS title, b.author AS author, b.addedAt AS addedAt,
           b.missingSince AS missingSince, b.calibreUuid AS calibreUuid, b.epubUid AS epubUid, b.fingerprint AS fingerprint,
           s.locatorJson AS locatorJson, s.progress AS progress, s.status AS status, s.lastOpenedAt AS lastOpenedAt,
           s.finishedAt AS finishedAt, s.userRating AS userRating, s.prefsJson AS prefsJson
    FROM book b LEFT JOIN book_state s ON s.bookId = b.id
    WHERE EXISTS (SELECT 1 FROM book_state h WHERE h.bookId = b.id AND (h.lastOpenedAt > 0 OR h.progress > 0
            OR h.status != 'unread' OR h.userRating IS NOT NULL OR h.prefsJson IS NOT NULL OR h.locatorJson IS NOT NULL))
       OR EXISTS (SELECT 1 FROM bookmark h WHERE h.bookId = b.id)
       OR EXISTS (SELECT 1 FROM highlight h WHERE h.bookId = b.id)
       OR EXISTS (SELECT 1 FROM book_tag h WHERE h.bookId = b.id AND h.origin = 'user')
    ORDER BY b.id
    """,
  )
  suspend fun snapshotRows(): List<SnapshotBookRow>

  @Query("SELECT bookId, tag FROM book_tag WHERE origin = 'user' AND bookId IN (:ids)")
  suspend fun userTags(ids: List<Long>): List<SnapshotTagRow>

  @Query("SELECT * FROM bookmark WHERE bookId IN (:ids) ORDER BY bookId, progress, id")
  suspend fun bookmarks(ids: List<Long>): List<BookmarkEntity>

  @Query("SELECT * FROM highlight WHERE bookId IN (:ids) ORDER BY bookId, progress, id")
  suspend fun highlights(ids: List<Long>): List<HighlightEntity>
}
