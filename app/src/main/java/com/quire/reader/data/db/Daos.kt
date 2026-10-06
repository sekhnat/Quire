package com.quire.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A book joined with its reading state and tags, as the library screens consume it. */
data class BookRow(
  val id: Long,
  val path: String,
  val folderId: Long,
  val title: String,
  val sortTitle: String,
  val author: String,
  val primaryAuthor: String,
  val authorSort: String,
  val series: String?,
  val seriesIndex: Double?,
  val pubYear: Int?,
  val language: String?,
  val description: String?,
  val calibreRating: Int,
  val userRating: Int?,
  val sizeBytes: Long,
  val addedAt: Long,
  val pageEstimate: Int,
  val coverPath: String?,
  val source: String,
  val readable: Boolean,
  val progress: Float?,
  val status: String?,
  val lastOpenedAt: Long?,
  /** Tags separated by the ASCII unit separator (U+001F), each prefixed `c` (from the book) or `u` (added in Quire). */
  val tags: String?,
) {
  private val tagEntries: List<String> get() = tags?.split('\u001F')?.filter { it.length > 1 }.orEmpty()
  val tagList: List<String> get() = tagEntries.map { it.substring(1) }
  val userTagList: List<String> get() = tagEntries.filter { it[0] == 'u' }.map { it.substring(1) }
}

/** Identity of a file already in the database, used to skip unchanged files on rescan. */
data class KnownFile(
  val id: Long, val path: String, val folderId: Long, val sizeBytes: Long, val mtime: Long, val addedAt: Long, val coverPath: String?,
  /** The book is missing (its file vanished earlier, or its folder was removed); finding the file again brings it back. */
  val missing: Boolean = false,
  /** False for books stored before identity keys existed; a scan reads their keys without re-reading the book. */
  val hasIdentity: Boolean = true,
)

/** A book's identity keys, for matching books whose file vanished to files found elsewhere (see `matchMoves`). */
data class IdentityRow(
  val id: Long,
  val folderId: Long,
  /** Needed to trust a shared EPUB identifier, which alone is too weak. */
  val title: String,
  val calibreUuid: String?,
  val epubUid: String?,
  val fingerprint: String?,
  /** When the book was last opened, 0 if never: the most recently read of several missing books wins a contested file. */
  val lastOpenedAt: Long = 0,
  /** The book has reading history of its own (see [HAS_HISTORY_SQL]), so another book's history must not be moved onto it. */
  val hasHistory: Boolean = false,
)

/** A missing book as Settings lists it. */
data class MissingBookRow(
  val id: Long,
  val title: String,
  val author: String,
  val coverPath: String?,
  val missingSince: Long,
  val progress: Float?,
  val bookmarks: Int,
  val highlights: Int,
)

data class BookCover(val id: Long, val coverPath: String?)

/** The result of [BookDao.removeFolder]: cover files to delete, and how many books stay behind as missing for their history. */
data class FolderRemoval(val staleCovers: List<String>, val keptWithHistory: Int)

/** The result of [BookDao.adopt]: the missing book's former cover file, when the book no longer uses it. */
data class Adoption(val staleCover: String?)

/**
 * True when book `b` has reading history: a position, status, rating or reader settings, a bookmark, a highlight or a
 * tag added in Quire. A book without history can be forgotten without losing anything the user did.
 */
private const val HAS_HISTORY_SQL = """(
      EXISTS (SELECT 1 FROM book_state h WHERE h.bookId = b.id AND (h.lastOpenedAt > 0 OR h.progress > 0 OR h.status != 'unread'
              OR h.userRating IS NOT NULL OR h.prefsJson IS NOT NULL OR h.locatorJson IS NOT NULL))
      OR EXISTS (SELECT 1 FROM bookmark h WHERE h.bookId = b.id)
      OR EXISTS (SELECT 1 FROM highlight h WHERE h.bookId = b.id)
      OR EXISTS (SELECT 1 FROM book_tag h WHERE h.bookId = b.id AND h.origin = 'user'))"""

/** The identity columns of a book `b` with its state `s`, as an [IdentityRow]. */
private const val IDENTITY_ROWS_SQL = """
    SELECT b.id, b.folderId, b.title, b.calibreUuid, b.epubUid, b.fingerprint, COALESCE(s.lastOpenedAt, 0) AS lastOpenedAt, $HAS_HISTORY_SQL AS hasHistory
    FROM book b LEFT JOIN book_state s ON s.bookId = b.id
    WHERE (b.calibreUuid IS NOT NULL OR b.epubUid IS NOT NULL OR b.fingerprint IS NOT NULL)"""

/** SQLite's limit on bound arguments is 999 on older Android versions; longer id lists are split into chunks of this size. */
const val MAX_SQL_ARGS = 500

@Dao
interface FolderDao {
  /** The folders in the library, not the removed ones kept for their missing books. */
  @Query("SELECT * FROM folder WHERE watched = 1 ORDER BY path") fun observeAll(): Flow<List<FolderEntity>>
  @Query("SELECT * FROM folder ORDER BY path") suspend fun all(): List<FolderEntity>
  @Query("SELECT * FROM folder WHERE watched = 1 ORDER BY path") suspend fun watched(): List<FolderEntity>
  @Query("SELECT * FROM folder WHERE path = :path") suspend fun byPath(path: String): FolderEntity?
  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(folder: FolderEntity): Long
  @Update suspend fun update(folder: FolderEntity)
  /** Puts a removed folder back in the library; the next scan finds its missing books at their paths. */
  @Query("UPDATE folder SET watched = 1 WHERE id = :id") suspend fun rewatch(id: Long)
  @Query("DELETE FROM folder WHERE id = :id") suspend fun delete(id: Long)
  @Query("UPDATE folder SET lastScanAt = :at WHERE id = :id") suspend fun markScanned(id: Long, at: Long)
}

/** The columns and joins of a [BookRow] for the books in the library (not missing ones); append an AND clause to narrow it. */
private const val BOOK_ROWS_SQL = """
    SELECT b.id, b.path, b.folderId, b.title, b.sortTitle, b.author, b.primaryAuthor, b.authorSort, b.series, b.seriesIndex,
           b.pubYear, b.language, b.description, b.calibreRating, s.userRating AS userRating, b.sizeBytes, b.addedAt,
           b.pageEstimate, b.coverPath, b.source, b.readable, s.progress AS progress, s.status AS status,
           s.lastOpenedAt AS lastOpenedAt,
           (SELECT GROUP_CONCAT((CASE t.origin WHEN 'user' THEN 'u' ELSE 'c' END) || t.tag, char(31)) FROM book_tag t WHERE t.bookId = b.id) AS tags
    FROM book b LEFT JOIN book_state s ON s.bookId = b.id
    WHERE b.missingSince IS NULL
    """

@Dao
abstract class BookDao {
  @Query(BOOK_ROWS_SQL)
  abstract fun observeAll(): Flow<List<BookRow>>

  @Query("$BOOK_ROWS_SQL AND b.id IN (:ids)") abstract suspend fun rowsByIds(ids: List<Long>): List<BookRow>

  /** Every book with a path, missing ones included, so a file that comes back finds its row. */
  @Query("SELECT id, path, folderId, sizeBytes, mtime, addedAt, coverPath, missingSince IS NOT NULL AS missing, fingerprint IS NOT NULL AS hasIdentity FROM book")
  abstract suspend fun knownFiles(): List<KnownFile>
  @Query("SELECT * FROM book WHERE id = :id") abstract suspend fun byId(id: Long): BookEntity?

  /** Ids of every book in the library (missing ones are not); the index keeps text for these only. */
  @Query("SELECT id FROM book WHERE missingSince IS NULL") abstract suspend fun presentIds(): List<Long>

  /** Readable, present books with the file signature to index them against, newest first. */
  @Query("SELECT id, path, mtime, sizeBytes FROM book WHERE readable = 1 AND missingSince IS NULL ORDER BY addedAt DESC, id DESC")
  abstract suspend fun indexable(): List<EligibleBook>

  @Query("SELECT id, path, mtime, sizeBytes FROM book WHERE readable = 1 AND missingSince IS NULL")
  abstract fun observeIndexable(): Flow<List<EligibleBook>>
  @Query("SELECT COUNT(*) FROM book WHERE missingSince IS NULL") abstract fun observeCount(): Flow<Int>
  @Query("DELETE FROM book WHERE id IN (:ids)") abstract suspend fun delete(ids: List<Long>)
  @Query("UPDATE book SET pageEstimate = :pages WHERE id = :id") abstract suspend fun setPages(id: Long, pages: Int)
  @Query("UPDATE book SET readable = :readable WHERE id = :id") abstract suspend fun setReadable(id: Long, readable: Boolean)

  @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsert(book: BookEntity): Long
  @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertTags(tags: List<BookTagEntity>)
  @Query("DELETE FROM book_tag WHERE bookId = :bookId AND origin = :origin") abstract suspend fun deleteTags(bookId: Long, origin: String)
  @Query("DELETE FROM book_tag WHERE bookId = :bookId AND tag = :tag AND origin = 'user'") abstract suspend fun deleteUserTag(bookId: Long, tag: String)

  /**
   * Writes a scanned book and replaces its Calibre tags in one transaction. The row id is kept when the
   * file was already known so reading state, bookmarks and highlights stay attached.
   */
  @Transaction
  open suspend fun save(book: BookEntity, tags: List<String>): Long {
    val id = upsertKeepingId(book)
    deleteTags(id, BookTagEntity.ORIGIN_CALIBRE)
    insertTags(tags.distinct().map { BookTagEntity(id, it, BookTagEntity.ORIGIN_CALIBRE) })
    return id
  }

  @Update abstract suspend fun update(book: BookEntity)

  private suspend fun upsertKeepingId(book: BookEntity): Long =
    if (book.id != 0L) { update(book); book.id } else upsert(book)

  // ── identity and missing books ───────────────────────────────────────────

  @Query("UPDATE book SET calibreUuid = :calibreUuid, epubUid = :epubUid, fingerprint = :fingerprint WHERE id = :id")
  abstract suspend fun setIdentity(id: Long, calibreUuid: String?, epubUid: String?, fingerprint: String?)

  /** Marks books whose file vanished as missing; books already missing keep their original date. At most [MAX_SQL_ARGS] ids. */
  @Query("UPDATE book SET missingSince = :at WHERE id IN (:ids) AND missingSince IS NULL") abstract suspend fun markMissing(ids: List<Long>, at: Long)

  /** Brings a missing book back: its unchanged file was found again at its path, in [folderId]. */
  @Query("UPDATE book SET missingSince = NULL, folderId = :folderId WHERE id = :id") abstract suspend fun revive(id: Long, folderId: Long)

  /**
   * One-time backfill for books a cover-broken build stored without a cover: `mtime = -1` never matches a real file, so
   * [com.quire.reader.data.scan.needsRead] sends every cover-less book (missing ones included, for when their file turns
   * up again) through a full re-read exactly once; the re-read stores the real mtime and the row settles.
   */
  @Query("UPDATE book SET mtime = -1 WHERE coverPath IS NULL") abstract suspend fun queueCoverBackfill()

  @Query("$IDENTITY_ROWS_SQL AND b.missingSince IS NOT NULL") abstract suspend fun missingIdentities(): List<IdentityRow>
  @Query("$IDENTITY_ROWS_SQL AND b.missingSince IS NULL") abstract suspend fun liveIdentities(): List<IdentityRow>

  @Query("SELECT tag FROM book_tag WHERE bookId = :bookId AND origin = 'calibre'") protected abstract suspend fun calibreTags(bookId: Long): List<String>

  /**
   * Moves the missing book [missingId] onto the file of the live book [liveId], which must have no history of its own
   * (the caller checks): the missing row takes the live row's file, metadata, identity and Calibre tags and keeps its id,
   * `addedAt` and everything attached to it, and the live row is deleted. The text index stays when the file is the same
   * (the signature still matches); otherwise the book is indexed again. Null, changing nothing, when either side has
   * changed state in the meantime.
   */
  @Transaction
  open suspend fun adopt(missingId: Long, liveId: Long): Adoption? {
    val old = byId(missingId)?.takeIf { it.missingSince != null } ?: return null
    val new = byId(liveId)?.takeIf { it.missingSince == null } ?: return null
    val tags = calibreTags(liveId)
    delete(listOf(liveId))
    // Readium's real page count replaced the estimate on the old row; it still holds for the same file.
    val sameFile = old.fingerprint != null && old.fingerprint == new.fingerprint
    update(new.copy(id = old.id, addedAt = old.addedAt, pageEstimate = if (sameFile) old.pageEstimate else new.pageEstimate))
    deleteTags(old.id, BookTagEntity.ORIGIN_CALIBRE)
    insertTags(tags.map { BookTagEntity(old.id, it, BookTagEntity.ORIGIN_CALIBRE) })
    return Adoption(staleCover = old.coverPath?.takeIf { it != new.coverPath })
  }

  @Query("SELECT b.id, b.coverPath FROM book b WHERE b.missingSince IS NOT NULL AND NOT $HAS_HISTORY_SQL")
  protected abstract suspend fun missingWithoutHistory(): List<BookCover>

  @Query("SELECT id, coverPath FROM book WHERE missingSince IS NOT NULL AND id IN (:ids)")
  protected abstract suspend fun missingCovers(ids: List<Long>): List<BookCover>

  @Query("DELETE FROM folder WHERE watched = 0 AND NOT EXISTS (SELECT 1 FROM book WHERE book.folderId = folder.id)")
  protected abstract suspend fun deleteEmptyRemovedFolders()

  /** Deletes the missing books that have no reading history, and removed folders left empty. Returns the cover files to delete. */
  @Transaction
  open suspend fun purgeMissing(): List<String> {
    val gone = missingWithoutHistory()
    gone.map { it.id }.chunked(MAX_SQL_ARGS).forEach { delete(it) }
    deleteEmptyRemovedFolders()
    return gone.mapNotNull { it.coverPath }
  }

  /** Deletes missing books for good, history included; books that are not missing are left alone. Returns the cover files to delete. */
  @Transaction
  open suspend fun forgetMissing(ids: List<Long>): List<String> {
    val gone = ids.chunked(MAX_SQL_ARGS).flatMap { missingCovers(it) }
    gone.map { it.id }.chunked(MAX_SQL_ARGS).forEach { delete(it) }
    deleteEmptyRemovedFolders()
    return gone.mapNotNull { it.coverPath }
  }

  @Query("SELECT id FROM book WHERE missingSince IS NOT NULL") abstract suspend fun missingIds(): List<Long>

  @Query(
    """
    SELECT b.id, b.title, b.author, b.coverPath, b.missingSince, s.progress AS progress,
           (SELECT COUNT(*) FROM bookmark x WHERE x.bookId = b.id) AS bookmarks,
           (SELECT COUNT(*) FROM highlight x WHERE x.bookId = b.id) AS highlights
    FROM book b LEFT JOIN book_state s ON s.bookId = b.id
    WHERE b.missingSince IS NOT NULL
    ORDER BY b.missingSince DESC, b.sortTitle
    """,
  )
  abstract fun observeMissing(): Flow<List<MissingBookRow>>

  @Query("UPDATE folder SET watched = 0 WHERE id = :folderId") protected abstract suspend fun unwatchFolder(folderId: Long)
  @Query("UPDATE book SET missingSince = :at WHERE folderId = :folderId AND missingSince IS NULL") protected abstract suspend fun markFolderMissing(folderId: Long, at: Long)
  @Query("SELECT COUNT(*) FROM book WHERE folderId = :folderId") protected abstract suspend fun countIn(folderId: Long): Int

  /**
   * Takes a folder out of the library without losing reading history: its books become missing, those without history are
   * deleted, and the folder row stays (unwatched) while books with history still belong to it, so adding the folder again
   * brings them back. The files on disk are not touched.
   */
  @Transaction
  open suspend fun removeFolder(folderId: Long, at: Long): FolderRemoval {
    unwatchFolder(folderId)
    markFolderMissing(folderId, at)
    val covers = purgeMissing()
    return FolderRemoval(covers, keptWithHistory = countIn(folderId))
  }
}

@Dao
abstract class StateDao {
  @Query("SELECT * FROM book_state WHERE bookId = :bookId") abstract suspend fun get(bookId: Long): BookStateEntity?
  @Query("UPDATE book_state SET prefsJson = NULL WHERE prefsJson IS NOT NULL") abstract suspend fun clearAllPrefs(): Int
  @Query("SELECT * FROM book_state WHERE bookId = :bookId") abstract fun observe(bookId: Long): Flow<BookStateEntity?>
  @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun put(state: BookStateEntity)

  @Transaction
  open suspend fun edit(bookId: Long, block: (BookStateEntity) -> BookStateEntity) {
    put(block(get(bookId) ?: BookStateEntity(bookId)))
  }
}

@Dao
interface AnnotationDao {
  @Query("SELECT * FROM bookmark WHERE bookId = :bookId ORDER BY progress") fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>
  @Insert suspend fun addBookmark(b: BookmarkEntity): Long
  @Query("DELETE FROM bookmark WHERE id = :id") suspend fun deleteBookmark(id: Long)

  @Query("SELECT * FROM highlight WHERE bookId = :bookId ORDER BY progress") fun observeHighlights(bookId: Long): Flow<List<HighlightEntity>>
  @Insert suspend fun addHighlight(h: HighlightEntity): Long
  @Query("DELETE FROM highlight WHERE id = :id") suspend fun deleteHighlight(id: Long)
  @Query("UPDATE highlight SET note = :note WHERE id = :id") suspend fun setHighlightNote(id: Long, note: String?)
}

/** A book that may need indexing, with the file signature to index against. */
data class EligibleBook(val id: Long, val path: String, val mtime: Long, val sizeBytes: Long)

/**
 * How much of the library is searchable. Every count covers readable books only, and `searchable`, `failed`
 * and `skipped` count a book only while its index state still matches the book's current signature. `partial`
 * counts searchable books whose index is missing text (size cap or unreadable resources).
 */
data class IndexCoverage(val eligible: Int, val searchable: Int, val failed: Int, val skipped: Int, val partial: Int)
