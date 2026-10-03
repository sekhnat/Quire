package com.quire.reader.data.db

import android.content.Context
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

  protected fun tempDbFile(): File =
    File(File(target.cacheDir, "db-tests").apply { mkdirs() }, "text-index-test-${UUID.randomUUID()}.db").also { files += it }

  /** Opens [file] through Room with the production migrations; a missing file is created at the current version. */
  protected fun open(file: File = tempDbFile()): QuireDatabase =
    QuireDatabase.create(target, file.absolutePath).also { databases += it }

  @After fun deleteDatabases() {
    databases.forEach { runCatching { it.close() } }
    files.forEach { f -> listOf("", "-wal", "-shm", "-journal").forEach { File(f.path + it).delete() } }
  }

  protected fun SupportSQLiteDatabase.rows(sql: String, vararg args: Any): List<List<String?>> =
    query(sql, args).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) } }

  protected fun SupportSQLiteDatabase.count(sql: String, vararg args: Any): Int = rows(sql, *args).single().single()!!.toInt()

  /** Ids of the chunks whose text matches [term] in the full-text table. */
  protected fun QuireDatabase.hits(term: String): List<Long> =
    openHelper.writableDatabase.rows("SELECT docid FROM text_chunk_fts WHERE text_chunk_fts MATCH ? ORDER BY docid", term).map { it[0]!!.toLong() }

  protected fun QuireDatabase.chunkCount(bookId: Long? = null): Int =
    openHelper.writableDatabase.count(if (bookId == null) "SELECT COUNT(*) FROM text_chunk" else "SELECT COUNT(*) FROM text_chunk WHERE bookId = $bookId")

  protected fun QuireDatabase.chunkTexts(bookId: Long): List<String> =
    openHelper.writableDatabase.rows("SELECT text FROM text_chunk WHERE bookId = ? ORDER BY seq", bookId).map { it[0]!! }

  protected fun QuireDatabase.stateOf(bookId: Long): IndexStateEntity? =
    openHelper.writableDatabase.rows("SELECT mtime, sizeBytes, status, completedAt, chunkCount, textBytes, truncated FROM index_state WHERE bookId = ?", bookId)
      .singleOrNull()?.let { IndexStateEntity(bookId, it[0]!!.toLong(), it[1]!!.toLong(), it[2]!!, it[3]!!.toLong(), it[4]!!.toInt(), it[5]!!.toLong(), it[6] == "1") }

  protected fun folder(db: QuireDatabase): Long = runBlocking { db.folders().insert(FolderEntity(path = "/sdcard/Books")) }

  protected fun bookEntity(folderId: Long, name: String, mtime: Long = 10, size: Long = 100, addedAt: Long = 0, readable: Boolean = true) =
    BookEntity(
      path = "/sdcard/Books/$name.epub", folderId = folderId, sizeBytes = size, mtime = mtime, title = name, sortTitle = name,
      author = "Author", primaryAuthor = "Author", authorSort = "Author", addedAt = addedAt, readable = readable,
    )

  protected fun chunk(bookId: Long, seq: Int, text: String) =
    TextChunkEntity(
      bookId = bookId, seq = seq, chapter = "Chapter ${seq + 1}", href = "ch$seq.xhtml", tokenStart = seq * 100, tokenEnd = seq * 100 + 99,
      primaryEndByte = text.toByteArray().size, text = text, mapping = "[]", progression = seq / 10.0,
    )

  protected fun doneState(book: BookEntity, bookId: Long, chunks: Int, truncated: Boolean = false, textBytes: Long = 0) =
    IndexStateEntity(bookId, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_DONE, completedAt = 1_000, chunkCount = chunks, textBytes = textBytes, truncated = truncated)
}
