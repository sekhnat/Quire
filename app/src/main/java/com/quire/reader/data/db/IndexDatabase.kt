package com.quire.reader.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * One searchable passage of a book. A book's chunks are written in one transaction with consecutive ids, so its
 * `index_state` range assigns any chunk id to its book without a lookup. [hrefIdx] and [chapterIdx] point into the
 * book's `book_string` rows; [mapping] is a [com.quire.reader.data.index.MappingCodec] blob.
 */
@Entity(tableName = "chunk", indices = [Index(value = ["book_id", "seq"], unique = true)])
data class ChunkEntity(
  @PrimaryKey val id: Long,
  @ColumnInfo(name = "book_id") val bookId: Long,
  val seq: Int,
  @ColumnInfo(name = "href_idx") val hrefIdx: Int,
  @ColumnInfo(name = "chapter_idx") val chapterIdx: Int,
  /** Nearest known progression through the publication, 0..1. */
  val progression: Double,
  val text: String,
  val mapping: ByteArray,
)

/** The text around the split of one long element (see [com.quire.reader.data.index.Seam]); its id is the id of the chunk after the split. */
@Entity(tableName = "seam")
data class SeamEntity(
  @PrimaryKey val id: Long,
  val text: String,
  @ColumnInfo(name = "split_char") val splitChar: Int,
)

/**
 * The outcome of indexing a book, tied to the file signature (`mtime`, `sizeBytes`) it was attempted against. A `done`
 * book with text owns the chunk ids [firstChunkId]..[lastChunkId]; both are null when it has none.
 */
@Entity(tableName = "index_state")
data class IndexStateEntity(
  @PrimaryKey val bookId: Long,
  val mtime: Long,
  val sizeBytes: Long,
  val status: String,
  val completedAt: Long,
  val chunkCount: Int = 0,
  /** UTF-8 bytes of chunk text persisted for the book. */
  val textBytes: Long = 0,
  /** True when the per-book size cap stopped indexing before the end of the book. */
  val truncated: Boolean = false,
  /** HTML resources of the book that could not be read (damaged entries); their text is missing from the index. */
  val unreadableResources: Int = 0,
  val firstChunkId: Long? = null,
  val lastChunkId: Long? = null,
) {
  companion object {
    const val STATUS_DONE = "done"
    /** The publication could not be opened or is DRM-protected. */
    const val STATUS_FAILED = "failed"
    /** The publication opened but has no extractable text. */
    const val STATUS_SKIPPED = "skipped"
  }
}

@Dao
interface IndexStateDao {
  @Query("SELECT * FROM index_state") suspend fun all(): List<IndexStateEntity>

  @Query("SELECT * FROM index_state") fun observeAll(): Flow<List<IndexStateEntity>>

  @Query("SELECT * FROM index_state WHERE bookId = :bookId") suspend fun of(bookId: Long): IndexStateEntity?

  /** Chunk text bytes persisted across all books, including state kept for books whose file has since changed. */
  @Query("SELECT COALESCE(SUM(textBytes), 0) FROM index_state") fun observeTextBytes(): Flow<Long>
}

/**
 * The library text index, in its own file (`quire-index.db`) on the bundled SQLite, which has FTS5; the user's data stays in
 * [QuireDatabase] on the platform SQLite. Everything here can be rebuilt from the books, so a schema change simply starts
 * over. The full-text tables are not Room entities: they are created with the database, and [com.quire.reader.data.index.IndexStore]
 * keeps them in step with `chunk` and `seam` by hand, in the same transactions.
 */
@Database(entities = [ChunkEntity::class, SeamEntity::class, IndexStateEntity::class], version = 1, exportSchema = false)
abstract class IndexDatabase : RoomDatabase() {
  abstract fun states(): IndexStateDao

  companion object {
    const val FILE_NAME = "quire-index.db"

    /** Statements that create everything Room does not: per-book strings and the full-text tables. */
    val CREATE_EXTRA = listOf(
      // The hrefs and chapter labels of a book, which chunks point to by index; `media_type` is set on href rows.
      "CREATE TABLE IF NOT EXISTS book_string (book_id INTEGER NOT NULL, idx INTEGER NOT NULL, value TEXT NOT NULL, media_type TEXT, PRIMARY KEY (book_id, idx)) WITHOUT ROWID",
      "CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts5(text, content='chunk', content_rowid='id', tokenize='unicode61 remove_diacritics 2', detail=full)",
      "CREATE VIRTUAL TABLE IF NOT EXISTS seam_fts USING fts5(text, content='seam', content_rowid='id', tokenize='unicode61 remove_diacritics 2', detail=full)",
      // rowid = chunk id, for chunks that contain CJK text only; see CjkGrams.
      "CREATE VIRTUAL TABLE IF NOT EXISTS cjk_fts USING fts5(grams, content='', contentless_delete=1, tokenize='unicode61', detail=full)",
      // Terms with their document counts, for judging how common a prefix is before searching for it.
      "CREATE VIRTUAL TABLE IF NOT EXISTS chunk_terms USING fts5vocab(chunk_fts, 'row')",
      "CREATE VIRTUAL TABLE IF NOT EXISTS cjk_terms USING fts5vocab(cjk_fts, 'row')",
    )

    /** [name] exists so tests and benchmarks can open throwaway files; the app uses the default. */
    fun create(context: Context, name: String = FILE_NAME): IndexDatabase =
      Room.databaseBuilder(context.applicationContext, IndexDatabase::class.java, name)
        .setDriver(IndexDriver())
        .fallbackToDestructiveMigration(dropAllTables = true)
        .addCallback(object : Callback() {
          override fun onCreate(connection: SQLiteConnection) = CREATE_EXTRA.forEach(connection::execSQL)
          // Also on open, so a file whose extra tables went missing (or an older build's) is usable.
          override fun onOpen(connection: SQLiteConnection) = CREATE_EXTRA.forEach(connection::execSQL)
        })
        .build()
  }
}
