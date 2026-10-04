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
data class KnownFile(val id: Long, val path: String, val folderId: Long, val sizeBytes: Long, val mtime: Long, val addedAt: Long, val coverPath: String?)

@Dao
interface FolderDao {
  @Query("SELECT * FROM folder ORDER BY path") fun observeAll(): Flow<List<FolderEntity>>
  @Query("SELECT * FROM folder ORDER BY path") suspend fun all(): List<FolderEntity>
  @Query("SELECT * FROM folder WHERE watched = 1 ORDER BY path") suspend fun watched(): List<FolderEntity>
  @Query("SELECT * FROM folder WHERE path = :path") suspend fun byPath(path: String): FolderEntity?
  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(folder: FolderEntity): Long
  @Update suspend fun update(folder: FolderEntity)
  @Query("DELETE FROM folder WHERE id = :id") suspend fun delete(id: Long)
  @Query("UPDATE folder SET lastScanAt = :at WHERE id = :id") suspend fun markScanned(id: Long, at: Long)
}

/** The columns and joins of a [BookRow]; append a WHERE clause to narrow it. */
private const val BOOK_ROWS_SQL = """
    SELECT b.id, b.path, b.folderId, b.title, b.sortTitle, b.author, b.primaryAuthor, b.authorSort, b.series, b.seriesIndex,
           b.pubYear, b.language, b.description, b.calibreRating, s.userRating AS userRating, b.sizeBytes, b.addedAt,
           b.pageEstimate, b.coverPath, b.source, b.readable, s.progress AS progress, s.status AS status,
           s.lastOpenedAt AS lastOpenedAt,
           (SELECT GROUP_CONCAT((CASE t.origin WHEN 'user' THEN 'u' ELSE 'c' END) || t.tag, char(31)) FROM book_tag t WHERE t.bookId = b.id) AS tags
    FROM book b LEFT JOIN book_state s ON s.bookId = b.id
    """

@Dao
abstract class BookDao {
  @Query(BOOK_ROWS_SQL)
  abstract fun observeAll(): Flow<List<BookRow>>

  @Query("$BOOK_ROWS_SQL WHERE b.id IN (:ids)") abstract suspend fun rowsByIds(ids: List<Long>): List<BookRow>

  @Query("SELECT id, path, folderId, sizeBytes, mtime, addedAt, coverPath FROM book") abstract suspend fun knownFiles(): List<KnownFile>
  @Query("SELECT * FROM book WHERE id = :id") abstract suspend fun byId(id: Long): BookEntity?
  @Query("SELECT COUNT(*) FROM book") abstract fun observeCount(): Flow<Int>
  @Query("DELETE FROM book WHERE id IN (:ids)") abstract suspend fun delete(ids: List<Long>)
  @Query("SELECT coverPath FROM book WHERE folderId = :folderId AND coverPath IS NOT NULL") abstract suspend fun coverPathsIn(folderId: Long): List<String>
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

/** A book that still needs indexing, with the file signature to index against. */
data class EligibleBook(val id: Long, val path: String, val mtime: Long, val sizeBytes: Long)

/**
 * How much of the library is searchable. Every count covers readable books only, and `searchable`, `failed`
 * and `skipped` count a book only while its index state still matches the book's current signature. `partial`
 * counts searchable books whose index is missing text (size cap or unreadable resources).
 */
data class IndexCoverage(val eligible: Int, val searchable: Int, val failed: Int, val skipped: Int, val partial: Int)

@Dao
abstract class IndexDao(private val database: RoomDatabase) {
  /** Readable books with no index state, or whose file changed since it was indexed; newest first. */
  @Query(
    """
    SELECT b.id AS id, b.path AS path, b.mtime AS mtime, b.sizeBytes AS sizeBytes
    FROM book b LEFT JOIN index_state s ON s.bookId = b.id
    WHERE b.readable = 1 AND (s.bookId IS NULL OR s.mtime != b.mtime OR s.sizeBytes != b.sizeBytes)
    ORDER BY b.addedAt DESC, b.id DESC
    """,
  )
  abstract suspend fun eligibleBooks(): List<EligibleBook>

  @Query(
    """
    SELECT COUNT(*) AS eligible,
           COALESCE(SUM(s.status = 'done'), 0) AS searchable,
           COALESCE(SUM(s.status = 'failed'), 0) AS failed,
           COALESCE(SUM(s.status = 'skipped'), 0) AS skipped,
           COALESCE(SUM(s.status = 'done' AND (s.truncated = 1 OR s.unreadableResources > 0)), 0) AS partial
    FROM book b LEFT JOIN index_state s ON s.bookId = b.id AND s.mtime = b.mtime AND s.sizeBytes = b.sizeBytes
    WHERE b.readable = 1
    """,
  )
  abstract fun observeCoverage(): Flow<IndexCoverage>

  /** Chunk text bytes persisted across all books, including state kept for books whose file has since changed. */
  @Query("SELECT COALESCE(SUM(textBytes), 0) FROM index_state") abstract fun observeTextBytes(): Flow<Long>

  @Query("SELECT COUNT(*) FROM book WHERE id = :bookId AND mtime = :mtime AND sizeBytes = :sizeBytes")
  protected abstract suspend fun matchingBooks(bookId: Long, mtime: Long, sizeBytes: Long): Int
  @Query("DELETE FROM text_chunk WHERE bookId = :bookId") protected abstract suspend fun deleteChunks(bookId: Long)
  @Insert protected abstract suspend fun insertChunks(chunks: List<TextChunkEntity>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun putState(state: IndexStateEntity)
  @Query("DELETE FROM text_chunk") protected abstract suspend fun deleteAllChunks()
  @Query("DELETE FROM index_state") protected abstract suspend fun deleteAllStates()

  /**
   * Swaps a book's chunks for freshly extracted ones and records [state], all in one transaction, so readers
   * see either the previous index or the new one and a failure part-way leaves the previous one untouched.
   * Returns false, writing nothing, when the book was removed or its file no longer has the signature
   * ([mtime], [sizeBytes]) the text was extracted from.
   */
  @Transaction
  open suspend fun replaceBook(bookId: Long, mtime: Long, sizeBytes: Long, chunks: List<TextChunkEntity>, state: IndexStateEntity): Boolean {
    require(state.bookId == bookId && state.mtime == mtime && state.sizeBytes == sizeBytes) { "state does not describe the book signature" }
    require(chunks.all { it.bookId == bookId }) { "chunk belongs to another book" }
    if (matchingBooks(bookId, mtime, sizeBytes) == 0) return false
    deleteChunks(bookId)
    insertChunks(chunks)
    putState(state)
    return true
  }

  /** Records a `failed` or `skipped` outcome for the signature and drops the book's obsolete chunks; false if the book is gone or changed. */
  @Transaction
  open suspend fun markTerminal(bookId: Long, mtime: Long, sizeBytes: Long, status: String, completedAt: Long = System.currentTimeMillis()): Boolean {
    require(status == IndexStateEntity.STATUS_FAILED || status == IndexStateEntity.STATUS_SKIPPED) { "not a chunk-less terminal status: $status" }
    if (matchingBooks(bookId, mtime, sizeBytes) == 0) return false
    deleteChunks(bookId)
    putState(IndexStateEntity(bookId, mtime, sizeBytes, status, completedAt))
    return true
  }

  /**
   * Forgets every indexing decision and all indexed text; the books themselves are untouched.
   *
   * Deleting the chunks row by row would fire the full-text delete trigger for each one, which measured about 130 µs a
   * row on the emulator (19 s for 150,000 chunks, with the write lock held throughout). Instead the trigger is dropped
   * for the bulk delete and the full-text index is then rebuilt from the now empty `text_chunk`, which empties it. All of
   * it is one transaction, so a failure part-way leaves the trigger and the old index in place.
   */
  @Transaction
  open suspend fun clearAll() {
    val sql = database.openHelper.writableDatabase
    sql.execSQL(QuireDatabase.DROP_FTS_DELETE_TRIGGER)
    deleteAllChunks()
    sql.execSQL("INSERT INTO `text_chunk_fts`(`text_chunk_fts`) VALUES('rebuild')")
    sql.execSQL(QuireDatabase.FTS_DELETE_TRIGGER)
    deleteAllStates()
  }
}
