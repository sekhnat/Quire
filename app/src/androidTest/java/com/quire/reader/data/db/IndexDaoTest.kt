package com.quire.reader.data.db

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexDaoTest : DbTestCase() {
  private class Fixture(val db: QuireDatabase, val folderId: Long)

  private fun fixture(): Fixture = open().let { Fixture(it, folder(it)) }

  private suspend fun Fixture.add(name: String, mtime: Long = 10, size: Long = 100, addedAt: Long = 0, readable: Boolean = true): BookEntity {
    val entity = bookEntity(folderId, name, mtime, size, addedAt, readable)
    return entity.copy(id = db.books().save(entity, emptyList()))
  }

  private suspend fun Fixture.index(book: BookEntity, vararg texts: String, truncated: Boolean = false): Boolean =
    db.index().replaceBook(
      book.id, book.mtime, book.sizeBytes, texts.mapIndexed { i, t -> chunk(book.id, i, t) },
      doneState(book, book.id, texts.size, truncated, textBytes = texts.sumOf { it.toByteArray().size }.toLong()),
    )

  @Test fun `replacing a book swaps its chunks and full-text rows for the new ones and records the state`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma")
    assertTrue(f.index(book, "Highbury was quiet", "Mr Knightley called"))
    assertEquals(2, f.db.hits("highbury").size + f.db.hits("knightley").size)

    assertTrue(f.index(book, "Hartfield in the evening", truncated = true))

    assertEquals(listOf("Hartfield in the evening"), f.db.chunkTexts(book.id))
    assertEquals(emptyList<Long>(), f.db.hits("highbury"))
    assertEquals(emptyList<Long>(), f.db.hits("knightley"))
    assertEquals(1, f.db.hits("hartfield").size)
    val state = f.db.stateOf(book.id)!!
    assertEquals(IndexStateEntity.STATUS_DONE, state.status)
    assertEquals(1, state.chunkCount)
    assertTrue(state.truncated)
    assertEquals(emptyList<EligibleBook>(), f.db.index().eligibleBooks())
  }

  @Test fun `a replacement that fails part-way leaves the previous committed index fully intact`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma")
    val other = f.add("Persuasion")
    f.index(book, "Highbury was quiet", "Mr Knightley called")
    f.index(other, "Anne walked to Lyme")
    val chunksBefore = f.db.chunkTexts(book.id)
    val stateBefore = f.db.stateOf(book.id)
    val highburyBefore = f.db.hits("highbury")

    // The second chunk repeats a (bookId, seq) pair, so the insert fails after the first chunk was written.
    val failing = listOf(chunk(book.id, 0, "Brandnewword appears first"), chunk(book.id, 1, "Another passage"), chunk(book.id, 1, "Duplicate sequence"))
    assertThrows(SQLiteConstraintException::class.java) {
      runBlocking { f.db.index().replaceBook(book.id, book.mtime, book.sizeBytes, failing, doneState(book, book.id, 3)) }
    }

    assertEquals(chunksBefore, f.db.chunkTexts(book.id))
    assertEquals(stateBefore, f.db.stateOf(book.id))
    assertEquals(highburyBefore, f.db.hits("highbury"))
    assertEquals(emptyList<Long>(), f.db.hits("brandnewword"))
    assertEquals(emptyList<Long>(), f.db.hits("another"))
    assertEquals(listOf("Anne walked to Lyme"), f.db.chunkTexts(other.id))
    f.db.openHelper.writableDatabase.execSQL("INSERT INTO text_chunk_fts(text_chunk_fts) VALUES('integrity-check')")
  }

  @Test fun `a failed first-time replacement leaves no chunks and no state behind`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma")
    val failing = listOf(chunk(book.id, 0, "Alpha"), chunk(book.id, 0, "Beta"))

    assertThrows(SQLiteConstraintException::class.java) {
      runBlocking { f.db.index().replaceBook(book.id, book.mtime, book.sizeBytes, failing, doneState(book, book.id, 2)) }
    }

    assertEquals(0, f.db.chunkCount())
    assertNull(f.db.stateOf(book.id))
    assertEquals(listOf(book.id), f.db.index().eligibleBooks().map { it.id })
  }

  @Test fun `eligible books are readable ones with no state or a signature that differs from the file and come newest added first`() = runBlocking {
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
    assertTrue(f.db.index().markTerminal(failedCurrent.id, failedCurrent.mtime, failedCurrent.sizeBytes, IndexStateEntity.STATUS_FAILED))
    // The files of two indexed books change on disk and a rescan records the new signature.
    f.db.books().update(touched.copy(mtime = touched.mtime + 1))
    f.db.books().update(resized.copy(sizeBytes = resized.sizeBytes + 1))
    f.db.books().update(unreadableStale.copy(mtime = unreadableStale.mtime + 1))

    val eligible = f.db.index().eligibleBooks()

    assertEquals(listOf(fresh.id, touched.id, resized.id, older.id), eligible.map { it.id })
    assertEquals(EligibleBook(touched.id, touched.path, touched.mtime + 1, touched.sizeBytes), eligible[1])
    assertEquals(EligibleBook(resized.id, resized.path, resized.mtime, resized.sizeBytes + 1), eligible[2])
    assertFalse(unreadable.id in eligible.map { it.id })
  }

  @Test fun `a book is eligible again once its stale state is replaced for the new signature`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma")
    f.index(book, "Highbury")
    val changed = book.copy(mtime = 99)
    f.db.books().update(changed)
    assertEquals(listOf(book.id), f.db.index().eligibleBooks().map { it.id })

    assertTrue(f.index(changed, "Hartfield"))

    assertEquals(emptyList<EligibleBook>(), f.db.index().eligibleBooks())
  }

  @Test fun `replacing refuses a book that was removed and writes nothing or recreates it`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma")
    f.db.books().delete(listOf(book.id))

    assertFalse(f.index(book, "Highbury was quiet"))

    assertNull(f.db.books().byId(book.id))
    assertEquals(0, f.db.chunkCount())
    assertNull(f.db.stateOf(book.id))
    assertEquals(emptyList<Long>(), f.db.hits("highbury"))
  }

  @Test fun `replacing refuses a book whose file changed and keeps the older index`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma")
    f.index(book, "Highbury was quiet")
    val stateBefore = f.db.stateOf(book.id)
    f.db.books().update(book.copy(sizeBytes = book.sizeBytes + 1))

    // Text extracted from the old file must not be published against the changed book.
    assertFalse(f.index(book, "Hartfield in the evening"))

    assertEquals(listOf("Highbury was quiet"), f.db.chunkTexts(book.id))
    assertEquals(stateBefore, f.db.stateOf(book.id))
    assertEquals(emptyList<Long>(), f.db.hits("hartfield"))
  }

  @Test fun `marking a terminal outcome deletes obsolete chunks and records the signature`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma")
    f.index(book, "Highbury was quiet", "Mr Knightley called")

    assertTrue(f.db.index().markTerminal(book.id, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_SKIPPED, completedAt = 777))

    assertEquals(0, f.db.chunkCount(book.id))
    assertEquals(emptyList<Long>(), f.db.hits("highbury"))
    assertEquals(IndexStateEntity(book.id, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_SKIPPED, 777), f.db.stateOf(book.id))
    assertEquals(emptyList<EligibleBook>(), f.db.index().eligibleBooks())
  }

  @Test fun `marking a terminal outcome refuses a removed or changed book and leaves existing rows alone`() = runBlocking {
    val f = fixture()
    val removed = f.add("Removed")
    val changed = f.add("Changed")
    f.index(changed, "Highbury was quiet")
    val stateBefore = f.db.stateOf(changed.id)
    f.db.books().delete(listOf(removed.id))
    f.db.books().update(changed.copy(mtime = changed.mtime + 1))

    assertFalse(f.db.index().markTerminal(removed.id, removed.mtime, removed.sizeBytes, IndexStateEntity.STATUS_FAILED))
    assertFalse(f.db.index().markTerminal(changed.id, changed.mtime, changed.sizeBytes, IndexStateEntity.STATUS_FAILED))

    assertNull(f.db.books().byId(removed.id))
    assertNull(f.db.stateOf(removed.id))
    assertEquals(listOf("Highbury was quiet"), f.db.chunkTexts(changed.id))
    assertEquals(stateBefore, f.db.stateOf(changed.id))
  }

  @Test fun `only failed and skipped can be recorded without chunks`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma")

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { f.db.index().markTerminal(book.id, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_DONE) }
    }

    assertNull(f.db.stateOf(book.id))
  }

  @Test fun `coverage counts only states that match the current file signature of readable books`() = runBlocking {
    val f = fixture()
    val done = f.add("Done")
    val truncated = f.add("Truncated")
    val failed = f.add("Failed")
    val skipped = f.add("Skipped")
    val pending = f.add("Pending")
    val stale = f.add("Stale")
    val unreadable = f.add("Unreadable")
    f.index(done, "Alpha")
    f.index(truncated, "Bravo", truncated = true)
    f.db.index().markTerminal(failed.id, failed.mtime, failed.sizeBytes, IndexStateEntity.STATUS_FAILED)
    f.db.index().markTerminal(skipped.id, skipped.mtime, skipped.sizeBytes, IndexStateEntity.STATUS_SKIPPED)
    f.index(stale, "Charlie", truncated = true)
    f.db.books().update(stale.copy(mtime = stale.mtime + 5))
    f.index(unreadable, "Delta")
    f.db.books().setReadable(unreadable.id, false)

    val coverage = f.db.index().observeCoverage().first()

    // Of 6 readable books: done and truncated are searchable, the stale one waits for re-indexing like the pending one.
    assertEquals(IndexCoverage(eligible = 6, searchable = 2, failed = 1, skipped = 1, truncated = 1), coverage)
    assertTrue(pending.id in f.db.index().eligibleBooks().map { it.id })
  }

  @Test fun `coverage on an empty library is all zero and persisted text bytes are summed over every state`() = runBlocking {
    val f = fixture()
    assertEquals(IndexCoverage(0, 0, 0, 0, 0), f.db.index().observeCoverage().first())
    assertEquals(0L, f.db.index().observeTextBytes().first())

    val a = f.add("A")
    val b = f.add("B")
    f.index(a, "twelve bytes", "x")
    f.index(b, "longer passage here")
    val c = f.add("C")
    f.db.index().markTerminal(c.id, c.mtime, c.sizeBytes, IndexStateEntity.STATUS_FAILED)

    assertEquals(("twelve bytes".length + "x".length + "longer passage here".length).toLong(), f.db.index().observeTextBytes().first())
  }

  @Test fun `clearing the index removes every chunk and full-text row and state but keeps the books`() = runBlocking {
    val f = fixture()
    val a = f.add("A")
    val b = f.add("B")
    f.index(a, "Alpha passage")
    f.db.index().markTerminal(b.id, b.mtime, b.sizeBytes, IndexStateEntity.STATUS_FAILED)

    f.db.index().clearAll()

    assertEquals(0, f.db.chunkCount())
    assertEquals(emptyList<Long>(), f.db.hits("alpha"))
    assertNull(f.db.stateOf(a.id))
    assertNull(f.db.stateOf(b.id))
    assertEquals(setOf(a.id, b.id), f.db.books().knownFiles().map { it.id }.toSet())
    assertEquals(listOf(a.id, b.id).sortedDescending(), f.db.index().eligibleBooks().map { it.id }.sortedDescending())
  }

  @Test fun `after clearing the full-text index is empty and consistent and still follows new chunks`() = runBlocking {
    val f = fixture()
    val a = f.add("A")
    f.index(a, "Alpha passage", "Beta passage")
    f.db.index().clearAll()
    assertEquals(0, f.db.openHelper.writableDatabase.count("SELECT COUNT(*) FROM text_chunk_fts_docsize"))

    f.index(a, "Gamma passage")
    assertEquals(1, f.db.hits("gamma").size)
    assertEquals(emptyList<Long>(), f.db.hits("alpha"))
    // The delete trigger was restored: removing the book's chunks removes the full-text rows too.
    f.db.index().markTerminal(a.id, a.mtime, a.sizeBytes, IndexStateEntity.STATUS_SKIPPED)
    assertEquals(emptyList<Long>(), f.db.hits("gamma"))
    assertEquals(1, f.db.openHelper.writableDatabase.count("SELECT COUNT(*) FROM sqlite_master WHERE name = 'room_fts_content_sync_text_chunk_fts_BEFORE_DELETE'"))
    f.db.openHelper.writableDatabase.execSQL("INSERT INTO text_chunk_fts(text_chunk_fts) VALUES('integrity-check')")
  }
}
