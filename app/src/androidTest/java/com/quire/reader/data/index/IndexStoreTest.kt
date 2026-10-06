package com.quire.reader.data.index

import androidx.sqlite.SQLiteStatement
import com.quire.reader.data.db.BookEntity
import com.quire.reader.data.db.DbTestCase
import com.quire.reader.data.db.EligibleBook
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.IndexStateEntity
import com.quire.reader.data.db.QuireDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Writes to the index database ([IndexStore]) and what the library sees of it ([IndexCatalog]). */
class IndexStoreTest : DbTestCase() {
  private class Fixture(val db: QuireDatabase, val index: IndexDatabase, val folderId: Long) {
    val store = IndexStore(RoomIndexSql(index))
    val catalog = IndexCatalog(db, index)
  }

  private fun fixture(): Fixture = open().let { Fixture(it, openIndex(), folder(it)) }

  private suspend fun Fixture.add(name: String, mtime: Long = 10, size: Long = 100, addedAt: Long = 0, readable: Boolean = true): BookEntity {
    val entity = bookEntity(folderId, name, mtime, size, addedAt, readable)
    return entity.copy(id = db.books().save(entity, emptyList()))
  }

  private fun chunks(vararg texts: String): List<IndexChunk> = TextChunker.chunk(
    texts.map { SourceElement("ch1.xhtml", it, false, "application/xhtml+xml", 0.5, 0.5, "One", chapterStart = true) },
  ).chunks

  private suspend fun Fixture.index(book: BookEntity, vararg texts: String, truncated: Boolean = false, unreadable: Int = 0) =
    store.replaceBook(book.id, book.mtime, book.sizeBytes, chunks(*texts), truncated, unreadable)

  @Test fun `the source fingerprint follows the books index state`() = runBlocking<Unit> {
    val f = fixture()
    val book = f.add("Emma")
    f.store.replaceBook(book.id, book.mtime, book.sizeBytes, chunks("Highbury was quiet"), false, 0, fingerprint = "100:aaaa")
    assertEquals("100:aaaa", f.store.sourceFingerprint(book.id))

    assertTrue(f.store.resign(book.id, book.mtime + 7, book.sizeBytes))
    assertEquals(book.mtime + 7, f.index.stateOf(book.id)!!.mtime)
    assertEquals(1, f.index.hits("highbury").size)

    // A new outcome without a known fingerprint forgets the old one.
    f.store.markTerminal(book.id, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_FAILED)
    assertNull(f.store.sourceFingerprint(book.id))

    f.store.addSources(mapOf(book.id to "100:aaaa"))
    f.store.addSources(mapOf(book.id to "ignored, a row exists"))
    assertEquals("100:aaaa", f.store.sourceFingerprint(book.id))
    f.store.removeBooks(listOf(book.id))
    assertNull(f.store.sourceFingerprint(book.id))
    assertTrue(!f.store.resign(book.id, 1, 1))

    f.store.addSources(mapOf(book.id to "100:aaaa"))
    f.store.clearAll()
    assertEquals(emptySet<Long>(), f.store.sourcedBooks())
  }

  @Test fun `replacing a book swaps its chunks and full-text rows for the new ones and records the state`() = runBlocking<Unit> {
    val f = fixture()
    val book = f.add("Emma")
    f.index(book, "Highbury was quiet", "Mr Knightley called")
    assertEquals(2, f.index.hits("highbury").size + f.index.hits("knightley").size)

    f.index(book, "Hartfield in the evening", truncated = true)

    assertEquals(listOf("Hartfield in the evening"), f.index.chunkTexts(book.id))
    assertEquals(emptyList<Long>(), f.index.hits("highbury"))
    assertEquals(emptyList<Long>(), f.index.hits("knightley"))
    assertEquals(1, f.index.hits("hartfield").size)
    val state = f.index.stateOf(book.id)!!
    assertEquals(IndexStateEntity.STATUS_DONE, state.status)
    assertEquals(1, state.chunkCount)
    assertTrue(state.truncated)
    assertEquals(state.firstChunkId, state.lastChunkId)
    assertEquals(emptyList<EligibleBook>(), f.catalog.eligibleBooks())
    f.index.checkIntegrity()
  }

  @Test fun `a books chunks have consecutive ids that its state records after every id in use`() = runBlocking<Unit> {
    val f = fixture()
    val a = f.add("A")
    val b = f.add("B")
    f.index(a, "one", "two", "three")
    f.index(b, "four", "five")
    f.index(a, "six", "seven")
    val sa = f.index.stateOf(a.id)!!
    val sb = f.index.stateOf(b.id)!!
    assertEquals(sb.lastChunkId!! + 1, sa.firstChunkId)
    assertEquals(sa.firstChunkId!! + 1, sa.lastChunkId)
    assertEquals(listOf(sa.firstChunkId, sa.lastChunkId), f.index.rows("SELECT id FROM chunk WHERE book_id = ? ORDER BY seq", a.id).map { it[0]!!.toLong() })
  }

  /** Fails the first statement that contains [failOn], to break a write part-way. */
  private class Failing(private val inner: IndexSql, private val failOn: String) : IndexSql by inner {
    override suspend fun <T> write(block: suspend (IndexConnection) -> T): T = inner.write { c ->
      block(object : IndexConnection {
        override suspend fun <R> statement(sql: String, block: (SQLiteStatement) -> R): R {
          if (failOn in sql) error("injected failure")
          return c.statement(sql, block)
        }
      })
    }
  }

  @Test fun `a replacement that fails part-way leaves the previous committed index fully intact`() = runBlocking<Unit> {
    val f = fixture()
    val book = f.add("Emma")
    val other = f.add("Persuasion")
    f.index(book, "Highbury was quiet", "Mr Knightley called 東京")
    f.index(other, "Anne walked to Lyme")
    val chunksBefore = f.index.chunkTexts(book.id)
    val stateBefore = f.index.stateOf(book.id)
    val footprintBefore = f.index.footprint()

    // Fails after the old rows were deleted and the new chunks written, before the CJK rows.
    val failing = IndexStore(Failing(RoomIndexSql(f.index), "INSERT INTO cjk_fts"))
    assertThrows(IllegalStateException::class.java) {
      runBlocking { failing.replaceBook(book.id, book.mtime, book.sizeBytes, chunks("Brandnewword appears first"), false, 0) }
    }

    assertEquals(chunksBefore, f.index.chunkTexts(book.id))
    assertEquals(stateBefore, f.index.stateOf(book.id))
    assertEquals(footprintBefore, f.index.footprint())
    assertEquals(emptyList<Long>(), f.index.hits("brandnewword"))
    assertEquals(listOf("Anne walked to Lyme"), f.index.chunkTexts(other.id))
    f.index.checkIntegrity()
  }

  @Test fun `eligible books are readable ones with no state or a signature that differs from the file and come newest added first`() = runBlocking<Unit> {
    val f = fixture()
    val fresh = f.add("Fresh", addedAt = 50)
    val current = f.add("Current", addedAt = 40)
    val touched = f.add("Touched", addedAt = 30)
    val resized = f.add("Resized", addedAt = 20)
    val failedCurrent = f.add("FailedCurrent", addedAt = 10)
    val unreadable = f.add("Unreadable", addedAt = 60, readable = false)
    val unreadableStale = f.add("UnreadableStale", addedAt = 70, readable = false)
    val older = f.add("Older", addedAt = 5)

    f.index(current, "Alpha")
    f.index(touched, "Bravo")
    f.index(resized, "Charlie")
    f.index(unreadableStale, "Delta")
    f.store.markTerminal(failedCurrent.id, failedCurrent.mtime, failedCurrent.sizeBytes, IndexStateEntity.STATUS_FAILED)
    // The files of two indexed books change on disk and a rescan records the new signature.
    f.db.books().update(touched.copy(mtime = touched.mtime + 1))
    f.db.books().update(resized.copy(sizeBytes = resized.sizeBytes + 1))
    f.db.books().update(unreadableStale.copy(mtime = unreadableStale.mtime + 1))

    val eligible = f.catalog.eligibleBooks()

    assertEquals(listOf(fresh.id, touched.id, resized.id, older.id), eligible.map { it.id })
    assertEquals(EligibleBook(touched.id, touched.path, touched.mtime + 1, touched.sizeBytes), eligible[1])
    assertEquals(EligibleBook(resized.id, resized.path, resized.mtime, resized.sizeBytes + 1), eligible[2])
    assertTrue(unreadable.id !in eligible.map { it.id })
  }

  @Test fun `marking a terminal outcome deletes obsolete chunks and records the signature`() = runBlocking<Unit> {
    val f = fixture()
    val book = f.add("Emma")
    f.index(book, "Highbury was quiet", "Mr Knightley called")

    f.store.markTerminal(book.id, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_SKIPPED, completedAt = 777)

    assertEquals(0, f.index.chunkCount(book.id))
    assertEquals(emptyList<Long>(), f.index.hits("highbury"))
    assertEquals(IndexStateEntity(book.id, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_SKIPPED, 777), f.index.stateOf(book.id))
    assertEquals(emptyList<EligibleBook>(), f.catalog.eligibleBooks())
    f.index.checkIntegrity()
  }

  @Test fun `only failed and skipped can be recorded without chunks`() = runBlocking<Unit> {
    val f = fixture()
    val book = f.add("Emma")
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { f.store.markTerminal(book.id, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_DONE) }
    }
    assertNull(f.index.stateOf(book.id))
  }

  @Test fun `coverage counts only states that match the current file signature of readable books`() = runBlocking<Unit> {
    val f = fixture()
    val done = f.add("Done")
    val truncated = f.add("Truncated")
    val failed = f.add("Failed")
    val skipped = f.add("Skipped")
    val pending = f.add("Pending")
    val stale = f.add("Stale")
    val unreadable = f.add("Unreadable")
    val damaged = f.add("Damaged")
    f.index(done, "Alpha")
    f.index(truncated, "Bravo", truncated = true)
    f.store.markTerminal(failed.id, failed.mtime, failed.sizeBytes, IndexStateEntity.STATUS_FAILED)
    f.store.markTerminal(skipped.id, skipped.mtime, skipped.sizeBytes, IndexStateEntity.STATUS_SKIPPED)
    f.index(stale, "Charlie", truncated = true)
    f.db.books().update(stale.copy(mtime = stale.mtime + 5))
    f.index(unreadable, "Delta")
    f.index(damaged, "Echo", unreadable = 1)
    f.db.books().setReadable(unreadable.id, false)

    val coverage = f.catalog.observeCoverage().first()

    // Of 7 readable books: done, truncated and damaged are searchable; truncated and damaged are partial; the stale one waits like the pending one.
    assertEquals(IndexCoverage(eligible = 7, searchable = 3, failed = 1, skipped = 1, partial = 2), coverage)
    assertTrue(pending.id in f.catalog.eligibleBooks().map { it.id })
  }

  @Test fun `coverage on an empty library is all zero and persisted text bytes are summed over every state`() = runBlocking<Unit> {
    val f = fixture()
    assertEquals(IndexCoverage(0, 0, 0, 0, 0), f.catalog.observeCoverage().first())
    assertEquals(0L, f.catalog.observeTextBytes().first())

    val a = f.add("A")
    val b = f.add("B")
    f.index(a, "twelve bytes", "x")
    f.index(b, "longer passage here")
    val c = f.add("C")
    f.store.markTerminal(c.id, c.mtime, c.sizeBytes, IndexStateEntity.STATUS_FAILED)

    assertEquals(("twelve bytes".length + "x".length + "longer passage here".length).toLong(), f.catalog.observeTextBytes().first())
  }

  @Test fun `books that left the library are swept from the index and the rest are kept`() = runBlocking<Unit> {
    val f = fixture()
    val kept = f.add("Kept")
    val gone = f.add("Gone")
    f.index(kept, "Alpha passage 日本")
    f.index(gone, List(400) { "w$it" }.joinToString(" "), "Beta passage 東京")
    f.db.books().delete(listOf(gone.id))

    assertEquals(1, f.store.retainOnly(f.db.books().presentIds()))

    assertNull(f.index.stateOf(gone.id))
    assertEquals(0, f.index.chunkCount(gone.id))
    assertEquals(emptyList<Long>(), f.index.hits("beta"))
    assertEquals(1, f.index.hits("alpha").size)
    assertEquals(mapOf("chunk" to 1, "seam" to 0, "book_string" to 2, "index_state" to 1, "chunk_fts" to 1, "seam_fts" to 0, "cjk_fts" to 1), f.index.footprint())
    f.index.checkIntegrity()
    assertEquals(0, f.store.retainOnly(f.db.books().presentIds()))
  }

  @Test fun `clearing the index removes every row of every table but keeps the books and the index still works afterwards`() = runBlocking<Unit> {
    val f = fixture()
    val a = f.add("A")
    val b = f.add("B")
    f.index(a, List(400) { "w$it" }.joinToString(" "), "Alpha passage 東京")
    f.store.markTerminal(b.id, b.mtime, b.sizeBytes, IndexStateEntity.STATUS_FAILED)

    f.store.clearAll()

    assertEquals(mapOf("chunk" to 0, "seam" to 0, "book_string" to 0, "index_state" to 0, "chunk_fts" to 0, "seam_fts" to 0, "cjk_fts" to 0), f.index.footprint())
    assertEquals(emptyList<Long>(), f.index.hits("alpha"))
    assertEquals(setOf(a.id, b.id), f.db.books().knownFiles().map { it.id }.toSet())
    assertEquals(listOf(a.id, b.id).sortedDescending(), f.catalog.eligibleBooks().map { it.id }.sortedDescending())
    f.index.checkIntegrity()

    f.index(a, "Gamma passage")
    assertEquals(1, f.index.hits("gamma").size)
    f.store.markTerminal(a.id, a.mtime, a.sizeBytes, IndexStateEntity.STATUS_SKIPPED)
    assertEquals(emptyList<Long>(), f.index.hits("gamma"))
    f.store.incrementalVacuum()
    f.store.optimize()
    f.index.checkIntegrity()
  }

  @Test fun `merging in steps ends when nothing is left and changes no result`() = runBlocking<Unit> {
    val f = fixture()
    repeat(12) { i -> f.index(f.add("Book $i"), "Heron number $i stood in the reeds.", "Another passage about 東京 and the heron") }
    val before = f.index.hits("heron")
    assertEquals(24, before.size)

    var steps = 0
    while (!f.store.mergeStep(pages = 1)) { steps++; assertTrue("the merge never finishes", steps < 1_000) }

    assertEquals(before, f.index.hits("heron"))
    assertEquals(12, f.index.rows("SELECT rowid FROM cjk_fts WHERE cjk_fts MATCH ?", "\"東京\"").size)
    f.index.checkIntegrity()
    assertTrue(f.store.mergeStep())
  }

  @Test fun `removing books and clearing give the space back to the file system`() = runBlocking<Unit> {
    val file = tempDbFile()
    val index = openIndex(file)
    val db = open()
    val store = IndexStore(RoomIndexSql(index))
    val f = Fixture(db, index, folder(db))
    val filler = "padding words repeated to make every passage a real size ".repeat(25)
    val books = List(8) { f.add("Book $it") }
    for (b in books) f.index(b, *Array(300) { "Passage $it of ${b.title}. $filler" })
    store.incrementalVacuum()
    val full = file.length()
    assertTrue("the fixture should be a few MB (was $full)", full > 2_000_000)

    store.retainOnly(books.take(2).map { it.id })
    store.incrementalVacuum()
    val afterRemoving = file.length()
    assertTrue("removing 6 of 8 books should shrink the file ($full -> $afterRemoving)", afterRemoving < full * 0.6)
    assertEquals("the write-ahead log is truncated too", 0L, java.io.File(file.path + "-wal").length())

    store.clearAll()
    store.incrementalVacuum()
    val afterClear = file.length()
    assertTrue("clearing should leave little ($afterRemoving -> $afterClear)", afterClear < full * 0.1)
    index.checkIntegrity()
  }
}
