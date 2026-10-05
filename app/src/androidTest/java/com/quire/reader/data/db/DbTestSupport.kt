package com.quire.reader.data.db

import android.content.Context
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import java.io.File
import java.util.UUID

/**
 * Opens databases as throwaway files in a scratch folder of the app's cache directory (never `databases/`), so
 * a test run never touches the app's `quire.db`. Everything opened through [open] and [openV1] is closed and deleted after each test.
 */
abstract class DbTestCase {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  /** The app context: what [QuireDatabase.create] and the v1 helper are given. */
  protected val target: Context = instrumentation.targetContext
  private val files = mutableListOf<File>()
  private val databases = mutableListOf<QuireDatabase>()
  private val indexes = mutableListOf<IndexDatabase>()

  protected fun tempDbFile(): File =
    File(File(target.cacheDir, "db-tests").apply { mkdirs() }, "text-index-test-${UUID.randomUUID()}.db").also { files += it }

  /** Opens [file] through Room with the production migrations; a missing file is created at the current version. */
  protected fun open(file: File = tempDbFile()): QuireDatabase =
    QuireDatabase.create(target, file.absolutePath).also { databases += it }

  /** Opens a throwaway index database (the bundled-SQLite `quire-index.db` of the app). */
  protected fun openIndex(file: File = tempDbFile()): IndexDatabase = IndexDatabase.create(target, file.absolutePath).also { indexes += it }

  @After fun deleteDatabases() {
    databases.forEach { runCatching { it.close() } }
    indexes.forEach { runCatching { it.close() } }
    files.forEach { f -> listOf("", "-wal", "-shm", "-journal").forEach { File(f.path + it).delete() } }
  }

  protected fun SupportSQLiteDatabase.rows(sql: String, vararg args: Any): List<List<String?>> =
    query(sql, args).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) } }

  protected fun SupportSQLiteDatabase.count(sql: String, vararg args: Any): Int = rows(sql, *args).single().single()!!.toInt()

  /** Rows of [sql] on the index database, every column as text. */
  protected fun IndexDatabase.rows(sql: String, vararg args: Any): List<List<String?>> = runBlocking {
    useReaderConnection { c ->
      c.usePrepared(sql) { st ->
        args.forEachIndexed { i, a -> when (a) { is Long -> st.bindLong(i + 1, a); is Int -> st.bindLong(i + 1, a.toLong()); else -> st.bindText(i + 1, a.toString()) } }
        buildList { while (st.step()) add((0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) }) }
      }
    }
  }

  protected fun IndexDatabase.count(sql: String, vararg args: Any): Int = rows(sql, *args).single().single()!!.toInt()

  /** Ids of the chunks whose text matches [match] in the main full-text table. */
  protected fun IndexDatabase.hits(match: String): List<Long> = rows("SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH ? ORDER BY rowid", match).map { it[0]!!.toLong() }

  protected fun IndexDatabase.chunkCount(bookId: Long? = null): Int =
    count(if (bookId == null) "SELECT COUNT(*) FROM chunk" else "SELECT COUNT(*) FROM chunk WHERE book_id = $bookId")

  protected fun IndexDatabase.chunkTexts(bookId: Long): List<String> = rows("SELECT text FROM chunk WHERE book_id = ? ORDER BY seq", bookId).map { it[0]!! }

  protected fun IndexDatabase.stateOf(bookId: Long): IndexStateEntity? = runBlocking { states().of(bookId) }

  /** Rows in every full-text and side table, to show that nothing is left behind. */
  protected fun IndexDatabase.footprint(): Map<String, Int> = listOf("chunk", "seam", "book_string", "index_state").associateWith { count("SELECT COUNT(*) FROM $it") } +
    mapOf(
      "chunk_fts" to count("SELECT COUNT(*) FROM chunk_fts_docsize"),
      "seam_fts" to count("SELECT COUNT(*) FROM seam_fts_docsize"),
      "cjk_fts" to count("SELECT COUNT(*) FROM cjk_fts_docsize"),
    )

  /** Runs FTS5's own consistency check on the external-content tables; throws if they disagree with their content. */
  protected fun IndexDatabase.checkIntegrity() = runBlocking {
    useWriterConnection { c ->
      c.usePrepared("INSERT INTO chunk_fts(chunk_fts, rank) VALUES('integrity-check', 1)") { it.step() }
      c.usePrepared("INSERT INTO seam_fts(seam_fts, rank) VALUES('integrity-check', 1)") { it.step() }
      c.usePrepared("INSERT INTO cjk_fts(cjk_fts) VALUES('integrity-check')") { it.step() }
    }
  }

  protected fun folder(db: QuireDatabase): Long = runBlocking { db.folders().insert(FolderEntity(path = "/sdcard/Books")) }

  protected fun bookEntity(folderId: Long, name: String, mtime: Long = 10, size: Long = 100, addedAt: Long = 0, readable: Boolean = true) =
    BookEntity(
      path = "/sdcard/Books/$name.epub", folderId = folderId, sizeBytes = size, mtime = mtime, title = name, sortTitle = name,
      author = "Author", primaryAuthor = "Author", authorSort = "Author", addedAt = addedAt, readable = readable,
    )
}
