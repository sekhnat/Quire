package com.quire.reader.bench

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.quire.reader.bench.legacy.TextChunker as LegacyChunker
import com.quire.reader.bench.legacy.SourceElement as LegacyElement
import com.quire.reader.data.db.BookEntity
import com.quire.reader.data.db.BookStateEntity
import com.quire.reader.data.db.BookTagEntity
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.IndexStore
import com.quire.reader.data.index.RoomIndexSql
import com.quire.reader.data.index.SourceElement
import com.quire.reader.data.index.TextChunker
import com.quire.reader.data.index.slimLocatorJson
import com.quire.reader.data.db.IndexDriver
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Synthetic library metadata for the filter queries, the same for every variant. */
object BenchMeta {
  const val NARROW_AUTHOR = "Narrow Author"
  const val BROAD_TAG = "broad"
  fun author(b: BenchBook) = if (b.idx % 44 == 0) NARROW_AUTHOR else "Author ${b.idx % 300}"
  fun broad(books: List<BenchBook>): Set<Long> = books.map { it.id }.shuffled(Random(42)).take(387).toSet()
  fun reading(books: List<BenchBook>): Set<Long> = books.map { it.id }.filter { it == 11L || it == 21L }.toSet()
  fun lastOpened(books: List<BenchBook>): Map<Long, Long> = books.map { it.id }.shuffled(Random(7)).take(30).withIndex().associate { (i, id) -> id to 1_000_000L + i }
}

/** The legacy layout (variants A and B): today's chunker, JSON mapping, overlap and ownership, and the book tables it joins. */
object LegacySchema {
  fun create(c: SQLiteConnection, fts5: Boolean) {
    listOf(
      "CREATE TABLE IF NOT EXISTS book (id INTEGER PRIMARY KEY, path TEXT NOT NULL, title TEXT NOT NULL, mtime INTEGER NOT NULL, sizeBytes INTEGER NOT NULL, readable INTEGER NOT NULL, missingSince INTEGER, primaryAuthor TEXT NOT NULL, series TEXT, addedAt INTEGER NOT NULL)",
      "CREATE TABLE IF NOT EXISTS book_tag (bookId INTEGER NOT NULL, tag TEXT NOT NULL, PRIMARY KEY (bookId, tag))",
      "CREATE INDEX IF NOT EXISTS index_book_tag_tag ON book_tag (tag)",
      "CREATE TABLE IF NOT EXISTS book_state (bookId INTEGER PRIMARY KEY, status TEXT NOT NULL, lastOpenedAt INTEGER NOT NULL)",
      "CREATE TABLE IF NOT EXISTS `text_chunk` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `chapter` TEXT NOT NULL, `href` TEXT NOT NULL, `tokenStart` INTEGER NOT NULL, `tokenEnd` INTEGER NOT NULL, `primaryEndByte` INTEGER NOT NULL, `primaryEndChar` INTEGER NOT NULL, `text` TEXT NOT NULL, `mapping` TEXT NOT NULL, `progression` REAL NOT NULL)",
      "CREATE UNIQUE INDEX IF NOT EXISTS `index_text_chunk_bookId_seq` ON `text_chunk` (`bookId`, `seq`)",
      "CREATE TABLE IF NOT EXISTS `index_state` (`bookId` INTEGER NOT NULL, `mtime` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, `chunkCount` INTEGER NOT NULL, `textBytes` INTEGER NOT NULL, `truncated` INTEGER NOT NULL, `unreadableResources` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`bookId`))",
    ).forEach(c::execSQL)
    if (fts5) {
      c.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS text_chunk_fts USING fts5(text, content='text_chunk', content_rowid='id', tokenize='unicode61', detail=full)")
      c.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS text_chunk_fts_terms USING fts5vocab(text_chunk_fts, 'row')")
    } else {
      c.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `text_chunk_fts` USING FTS4(`text` TEXT NOT NULL, tokenize=unicode61, content=`text_chunk`)")
      c.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_text_chunk_fts_AFTER_INSERT AFTER INSERT ON `text_chunk` BEGIN INSERT INTO `text_chunk_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END")
      c.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `text_chunk_fts_terms` USING fts4aux(`text_chunk_fts`)")
    }
  }

  fun element(e: SourceElement) = LegacyElement(e.href, e.text, e.headingStart, slimLocatorJson(e.href, e.mediaType, e.resourceProgression), e.progression, e.chapter, e.chapterStart)

  fun open(file: File, fts5: Boolean): SQLiteConnection =
    (if (fts5) IndexDriver().open(file.path) else AndroidSQLiteDriver().open(file.path)).also { it.execSQL("PRAGMA journal_mode = WAL") }
}

/** Step 2: builds one variant's index from the element cache (`-e variant A..E`), timing the build alone. */
class BuildBench : BenchStep() {
  @Test fun build() = runBlocking {
    requireBench()
    val v = Variant.valueOf(arg("variant"))
    val books = cache.books().filter { it.ok }
    dbFile(v).let { f -> listOf("", "-wal", "-shm").forEach { File(f.path + it).delete() } }
    val stats = if (v.legacy) buildLegacy(v, books) else buildNew(v, books)
    report("build.tsv", "$v\t$stats")
  }

  private class Stats(var chunks: Long = 0, var textBytes: Long = 0, var extraBytes: Long = 0, var seams: Long = 0, var truncated: Int = 0, var millis: Long = 0) {
    override fun toString() = "seconds=${millis / 1000}\tchunks=$chunks\ttextBytes=$textBytes\tseamOrContextBytes=$extraBytes\tseams=$seams\ttruncated=$truncated"
  }

  private fun buildLegacy(v: Variant, books: List<BenchBook>): Stats {
    val c = LegacySchema.open(dbFile(v), v.fts5)
    LegacySchema.create(c, v.fts5)
    insertLegacyBooks(c, books)
    val s = Stats()
    var timed = 0L
    for (b in books) {
      val elements = cache.read(b.idx).map(LegacySchema::element)
      val t0 = System.nanoTime()
      val r = LegacyChunker.chunk(elements)
      c.execSQL("BEGIN IMMEDIATE")
      val base = c.prepare("SELECT COALESCE(MAX(id), 0) FROM text_chunk").use { it.step(); it.getLong(0) } + 1
      c.prepare("INSERT INTO text_chunk (id, bookId, seq, chapter, href, tokenStart, tokenEnd, primaryEndByte, primaryEndChar, text, mapping, progression) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)").use { st ->
        r.chunks.forEachIndexed { i, ch ->
          st.bindLong(1, base + i); st.bindLong(2, b.id); st.bindLong(3, ch.seq.toLong()); st.bindText(4, ch.chapter); st.bindText(5, ch.href)
          st.bindLong(6, ch.tokenStart.toLong()); st.bindLong(7, ch.tokenEnd.toLong()); st.bindLong(8, ch.primaryEndByte.toLong())
          st.bindLong(9, ch.text.toByteArray(Charsets.UTF_8).copyOfRange(0, ch.primaryEndByte).toString(Charsets.UTF_8).length.toLong())
          st.bindText(10, ch.text); st.bindText(11, ch.mappingJson); st.bindDouble(12, ch.progression)
          st.step(); st.reset()
        }
      }
      if (v.fts5) c.prepare("INSERT INTO text_chunk_fts (rowid, text) VALUES (?, ?)").use { st ->
        r.chunks.forEachIndexed { i, ch -> st.bindLong(1, base + i); st.bindText(2, ch.text); st.step(); st.reset() }
      }
      c.prepare("INSERT OR REPLACE INTO index_state VALUES (?, ?, ?, 'done', 1000, ?, ?, ?, ?)").use { st ->
        st.bindLong(1, b.id); st.bindLong(2, b.mtime); st.bindLong(3, b.sizeBytes); st.bindLong(4, r.chunks.size.toLong())
        st.bindLong(5, r.chunks.sumOf { it.text.toByteArray().size.toLong() }); st.bindLong(6, if (r.truncated) 1 else 0); st.bindLong(7, b.unreadable.toLong()); st.step()
      }
      c.execSQL("COMMIT")
      timed += System.nanoTime() - t0
      s.chunks += r.chunks.size
      s.textBytes += r.chunks.sumOf { it.text.toByteArray().size.toLong() }
      s.extraBytes += r.chunks.sumOf { (it.text.toByteArray().size - it.primaryEndByte).toLong() }
      if (r.truncated) s.truncated++
    }
    c.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
    c.close()
    s.millis = timed / 1_000_000
    return s
  }

  private fun insertLegacyBooks(c: SQLiteConnection, books: List<BenchBook>) {
    val broad = BenchMeta.broad(books)
    val reading = BenchMeta.reading(books)
    val opened = BenchMeta.lastOpened(books)
    c.execSQL("BEGIN")
    c.prepare("INSERT INTO book VALUES (?, ?, ?, ?, ?, 1, NULL, ?, NULL, ?)").use { st ->
      for (b in books) {
        st.bindLong(1, b.id); st.bindText(2, b.path); st.bindText(3, b.title); st.bindLong(4, b.mtime); st.bindLong(5, b.sizeBytes)
        st.bindText(6, BenchMeta.author(b)); st.bindLong(7, b.idx.toLong()); st.step(); st.reset()
      }
    }
    c.prepare("INSERT INTO book_tag VALUES (?, ?)").use { st -> for (id in broad) { st.bindLong(1, id); st.bindText(2, BenchMeta.BROAD_TAG); st.step(); st.reset() } }
    c.prepare("INSERT INTO book_state VALUES (?, ?, ?)").use { st ->
      for (id in reading + opened.keys) { st.bindLong(1, id); st.bindText(2, if (id in reading) "reading" else "unread"); st.bindLong(3, opened[id] ?: 2_000_000L); st.step(); st.reset() }
    }
    c.execSQL("COMMIT")
  }

  private suspend fun buildNew(v: Variant, books: List<BenchBook>): Stats {
    ensureUserDb(books)
    val db = IndexDatabase.create(ctx, dbFile(v).absolutePath, pageSize)
    val store = IndexStore(RoomIndexSql(db))
    val s = Stats()
    var timed = 0L
    for (b in books) {
      val elements = cache.read(b.idx)
      val t0 = System.nanoTime()
      val r = TextChunker.chunk(elements, minChars = v.minChars, maxChars = v.maxChars)
      store.replaceBook(b.id, b.mtime, b.sizeBytes, r.chunks, r.truncated, b.unreadable, completedAt = 1000)
      timed += System.nanoTime() - t0
      s.chunks += r.chunks.size
      s.textBytes += r.chunks.sumOf { it.text.toByteArray().size.toLong() }
      s.extraBytes += r.chunks.sumOf { (it.seam?.text?.toByteArray()?.size ?: 0).toLong() }
      s.seams += r.chunks.count { it.seam != null }
      if (r.truncated) s.truncated++
    }
    db.close()
    s.millis = timed / 1_000_000
    return s
  }

  /** The user database the new variants search against: the same books and synthetic metadata as the legacy tables. */
  private suspend fun ensureUserDb(books: List<BenchBook>) {
    val file = File(dir, "user.db")
    if (file.isFile) return
    val db = QuireDatabase.create(ctx, file.absolutePath)
    val folder = db.folders().insert(FolderEntity(path = "/sdcard/Calibre Library"))
    val broad = BenchMeta.broad(books)
    val reading = BenchMeta.reading(books)
    val opened = BenchMeta.lastOpened(books)
    for (b in books) {
      val author = BenchMeta.author(b)
      db.books().upsert(
        BookEntity(
          id = b.id, path = b.path, folderId = folder, sizeBytes = b.sizeBytes, mtime = b.mtime, title = b.title, sortTitle = b.title,
          author = author, primaryAuthor = author, authorSort = author, addedAt = b.idx.toLong(),
        ),
      )
      if (b.id in broad) db.books().insertTags(listOf(BookTagEntity(b.id, BenchMeta.BROAD_TAG, BookTagEntity.ORIGIN_USER)))
    }
    for (id in reading + opened.keys) {
      db.states().put(BookStateEntity(id, status = if (id in reading) BookStateEntity.STATUS_READING else BookStateEntity.STATUS_UNREAD, lastOpenedAt = opened[id] ?: 2_000_000L))
    }
    db.close()
  }
}
