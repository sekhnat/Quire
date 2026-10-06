package com.quire.reader.data.index

import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.BookEntity
import com.quire.reader.data.db.DbTestCase
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.IndexStateEntity
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.reader.PublicationLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryIndexerTest : DbTestCase() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val epubDir = File(target.cacheDir, "db-tests/epubs").apply { mkdirs() }
  private val deadline get() = System.currentTimeMillis() + 60_000

  @After fun cleanUp() {
    scope.cancel()
    epubDir.deleteRecursively()
  }

  private class Fixture(val db: QuireDatabase, val index: IndexDatabase, val folderId: Long, val indexer: LibraryIndexer)

  private fun fixture(): Fixture {
    val db = open()
    val index = openIndex()
    return Fixture(db, index, folder(db), LibraryIndexer(target, db, index, PublicationLoader(target), SettingsStore(target), scope))
  }

  /** The chapter label of each of a book's chunks, in reading order. */
  private fun Fixture.chapters(bookId: Long) =
    index.rows("SELECT s.value FROM chunk c JOIN book_string s ON s.book_id = c.book_id AND s.idx = c.chapter_idx WHERE c.book_id = ? ORDER BY c.seq", bookId).map { it[0] }

  /** Adds a book row describing [file] the way a scan would. */
  private fun Fixture.add(name: String, file: File, addedAt: Long = 0, fingerprint: String? = null): BookEntity = runBlocking {
    val entity = bookEntity(folderId, name, addedAt = addedAt).copy(path = file.absolutePath, mtime = file.lastModified(), sizeBytes = file.length(), fingerprint = fingerprint)
    entity.copy(id = db.books().save(entity, emptyList()))
  }

  /** Rewrites [file] and updates the book row the way a rescan would. */
  private fun Fixture.change(book: BookEntity, file: File, chapters: List<FixtureChapter>): BookEntity = runBlocking {
    EpubFixtures.write(file, chapters)
    file.setLastModified(file.lastModified() + 5_000)
    val updated = book.copy(mtime = file.lastModified(), sizeBytes = file.length())
    db.books().update(updated)
    updated
  }

  private fun epub(name: String, vararg chapters: FixtureChapter) = EpubFixtures.write(File(epubDir, "$name.epub"), chapters.toList())

  private fun bigChapters(word: String) = List(4) { c -> FixtureChapter("Part $c", (0 until 1_500).joinToString("") { "<p>$word number $it of part $c repeated for bulk text padding the paragraph out to length.</p>" }) }

  /** Gives [file] a new modification time without changing a byte, and updates the row as a rescan would. */
  private fun Fixture.touch(book: BookEntity, file: File): BookEntity = runBlocking {
    file.setLastModified(file.lastModified() + 5_000)
    book.copy(mtime = file.lastModified()).also { db.books().update(it) }
  }

  @Test fun `a file whose mtime changed but whose content did not keeps its index`() = runBlocking {
    val f = fixture()
    val file = epub("emma", EpubFixtures.chapter("Volume One", "Highbury was quiet that morning."))
    val book = f.add("Emma", file, fingerprint = "100:aaaa")
    f.indexer.runBatch(deadline)
    val before = f.index.stateOf(book.id)!!

    val touched = f.touch(book, file)
    assertEquals(BatchResult(processed = 1, stop = BatchStop.Drained), f.indexer.runBatch(deadline))

    val after = f.index.stateOf(book.id)!!
    assertEquals(touched.mtime, after.mtime)
    assertEquals("the text was not extracted again", before.copy(mtime = touched.mtime), after)
    assertEquals(1, f.index.hits("highbury").size)
    assertEquals(emptyList<Any>(), f.indexer.catalog.eligibleBooks())
  }

  @Test fun `a file whose fingerprint changed is indexed again`() = runBlocking {
    val f = fixture()
    val file = epub("emma", EpubFixtures.chapter("Volume One", "Highbury was quiet that morning."))
    val book = f.add("Emma", file, fingerprint = "100:aaaa")
    f.indexer.runBatch(deadline)

    val changed = f.change(book, file, listOf(EpubFixtures.chapter("Volume One", "Hartfield in the evening.")))
    f.db.books().setIdentity(book.id, null, null, "120:bbbb")
    f.indexer.runBatch(deadline)

    assertEquals(changed.mtime, f.index.stateOf(book.id)!!.mtime)
    assertEquals(emptyList<Long>(), f.index.hits("highbury"))
    assertEquals(1, f.index.hits("hartfield").size)
  }

  @Test fun `books indexed before fingerprints were kept carry their index forward after the backfill`() = runBlocking {
    val f = fixture()
    val file = epub("emma", EpubFixtures.chapter("Volume One", "Highbury was quiet that morning."))
    val book = f.add("Emma", file)
    f.indexer.runBatch(deadline)
    val before = f.index.stateOf(book.id)!!
    // A later scan reads the file's identity; the index still matches the file.
    f.db.books().setIdentity(book.id, null, null, "100:aaaa")

    // A new process backfills the source fingerprint once.
    val restarted = LibraryIndexer(target, f.db, f.index, PublicationLoader(target), SettingsStore(target), scope)
    restarted.runBatch(deadline)
    val touched = f.touch(book.copy(fingerprint = "100:aaaa"), file)
    restarted.runBatch(deadline)

    assertEquals(before.copy(mtime = touched.mtime), f.index.stateOf(book.id))
  }

  @Test fun `an epub is indexed into searchable chunks labelled with its chapters`() = runBlocking {
    val f = fixture()
    val file = epub("emma", EpubFixtures.chapter("Volume One", "Highbury was quiet that morning."), EpubFixtures.chapter("Volume Two", "Mr Knightley called on Hartfield."))
    val book = f.add("Emma", file)

    val result = f.indexer.runBatch(deadline)

    assertEquals(BatchResult(processed = 1, stop = BatchStop.Drained), result)
    val state = f.index.stateOf(book.id)!!
    assertEquals(IndexStateEntity.STATUS_DONE, state.status)
    assertFalse(state.truncated)
    assertEquals(f.index.chunkCount(book.id), state.chunkCount)
    assertEquals(1, f.index.hits("highbury").size)
    assertEquals(1, f.index.hits("knightley").size)
    assertEquals(listOf("Volume One", "Volume Two"), f.chapters(book.id))
    // Every chunk's binary mapping reads back against its text, and its resource is one of the book's documents.
    val rows = f.index.rows(
      "SELECT c.text, hex(c.mapping), s.value, s.media_type FROM chunk c JOIN book_string s ON s.book_id = c.book_id AND s.idx = c.href_idx WHERE c.book_id = ? ORDER BY c.seq", book.id,
    )
    assertEquals(state.chunkCount, rows.size)
    for ((text, hex, href, mediaType) in rows) {
      val blob = hex!!.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
      assertTrue(MappingCodec.decode(blob, text!!.length).isNotEmpty())
      assertTrue(href!!.endsWith(".xhtml"))
      assertEquals("application/xhtml+xml", mediaType)
    }
    assertEquals(emptyList<Any>(), f.indexer.catalog.eligibleBooks())
  }

  @Test fun `an unchanged book is not read again`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma", epub("emma", EpubFixtures.chapter("One", "Highbury was quiet.")))
    f.indexer.runBatch(deadline)
    val completedAt = f.index.stateOf(book.id)!!.completedAt

    assertEquals(BatchResult(processed = 0, stop = BatchStop.Drained), f.indexer.runBatch(deadline))
    assertEquals(completedAt, f.index.stateOf(book.id)!!.completedAt)
  }

  @Test fun `a changed file replaces the stale chunks`() = runBlocking {
    val f = fixture()
    val file = epub("emma", EpubFixtures.chapter("One", "Highbury was quiet."))
    val book = f.add("Emma", file)
    f.indexer.runBatch(deadline)
    assertEquals(1, f.index.hits("highbury").size)

    val changed = f.change(book, file, listOf(EpubFixtures.chapter("One", "Hartfield in the evening.")))
    assertEquals(1, f.indexer.catalog.eligibleBooks().size)
    f.indexer.runBatch(deadline)

    assertEquals(emptyList<Long>(), f.index.hits("highbury"))
    assertEquals(1, f.index.hits("hartfield").size)
    assertEquals(changed.mtime, f.index.stateOf(book.id)!!.mtime)
    assertEquals(IndexStateEntity.STATUS_DONE, f.index.stateOf(book.id)!!.status)
  }

  @Test fun `a file that changed since the last scan is set aside and the previous index stays`() = runBlocking {
    val f = fixture()
    val file = epub("emma", EpubFixtures.chapter("One", "Highbury was quiet."))
    val book = f.add("Emma", file)
    f.indexer.runBatch(deadline)
    // The book row still holds the old signature: the scan has not noticed yet.
    EpubFixtures.write(file, listOf(EpubFixtures.chapter("One", "Hartfield in the evening, quite different.")))
    f.db.books().update(book.copy(mtime = book.mtime + 1))

    val result = f.indexer.runBatch(deadline)

    assertEquals(BatchResult(processed = 0, stop = BatchStop.Drained), result)
    assertEquals(1, f.index.hits("highbury").size)
    assertEquals(emptyList<Long>(), f.index.hits("hartfield"))
    assertEquals(book.mtime, f.index.stateOf(book.id)!!.mtime)
  }

  @Test fun `a vanished file is neither failed nor skipped`() = runBlocking {
    val f = fixture()
    val file = epub("gone", EpubFixtures.chapter("One", "Some text."))
    val book = f.add("Gone", file)
    file.delete()

    assertEquals(BatchResult(processed = 0, stop = BatchStop.Drained), f.indexer.runBatch(deadline))
    assertNull(f.index.stateOf(book.id))
    assertEquals(listOf(book.id), f.indexer.catalog.eligibleBooks().map { it.id })
  }

  @Test fun `an epub without text is skipped`() = runBlocking {
    val f = fixture()
    val file = epub("pictures", FixtureChapter("Plates", """<img src="plate.png" alt="plate"/><p>   </p>"""))
    val book = f.add("Pictures", file)

    f.indexer.runBatch(deadline)

    val state = f.index.stateOf(book.id)!!
    assertEquals(IndexStateEntity.STATUS_SKIPPED, state.status)
    assertEquals(0, f.index.chunkCount(book.id))
    assertEquals(emptyList<Any>(), f.indexer.catalog.eligibleBooks())
  }

  @Test fun `a damaged archive is failed once and not retried`() = runBlocking {
    val f = fixture()
    val book = f.add("Broken", EpubFixtures.writeCorrupt(File(epubDir, "broken.epub")))

    assertEquals(1, f.indexer.runBatch(deadline).processed)
    assertEquals(IndexStateEntity.STATUS_FAILED, f.index.stateOf(book.id)!!.status)
    assertEquals(0, f.indexer.runBatch(deadline).processed)
  }

  @Test fun `a changed book that turns out unreadable loses its old chunks`() = runBlocking {
    val f = fixture()
    val file = epub("emma", EpubFixtures.chapter("One", "Highbury was quiet."))
    val book = f.add("Emma", file)
    f.indexer.runBatch(deadline)

    EpubFixtures.writeCorrupt(file)
    file.setLastModified(file.lastModified() + 5_000)
    f.db.books().update(book.copy(mtime = file.lastModified(), sizeBytes = file.length()))
    f.indexer.runBatch(deadline)

    assertEquals(IndexStateEntity.STATUS_FAILED, f.index.stateOf(book.id)!!.status)
    assertEquals(emptyList<Long>(), f.index.hits("highbury"))
  }

  @Test fun `newest added books are indexed first and unreadable books are never queued`() = runBlocking {
    val f = fixture()
    val old = f.add("Old", epub("old", EpubFixtures.chapter("One", "Oldword text.")), addedAt = 1)
    val new = f.add("New", epub("new", EpubFixtures.chapter("One", "Newword text.")), addedAt = 2)
    assertEquals(listOf(new.id, old.id), f.indexer.catalog.eligibleBooks().map { it.id })

    // A deadline already in the past stops before the first book.
    assertEquals(BatchResult(processed = 0, stop = BatchStop.Deadline), f.indexer.runBatch(System.currentTimeMillis() - 1))
    assertEquals(2, f.indexer.catalog.eligibleBooks().size)
  }

  @Test fun `an open reader keeps the batch from starting`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma", epub("emma", EpubFixtures.chapter("One", "Highbury was quiet.")))
    f.indexer.setReaderBusy(true)

    assertEquals(BatchResult(processed = 0, stop = BatchStop.ReaderBusy), f.indexer.runBatch(deadline))
    assertNull(f.index.stateOf(book.id))

    f.indexer.setReaderBusy(false)
    assertEquals(1, f.indexer.runBatch(deadline).processed)
  }

  @Test fun `a reader opening mid-book abandons it without writing and the book stays eligible`() = runBlocking {
    val f = fixture()
    val file = epub("big", *bigChapters("Marmalade").toTypedArray())
    val book = f.add("Big", file)
    val running = async(Dispatchers.Default) { f.indexer.runBatch(deadline) }
    delay(400)
    f.indexer.setReaderBusy(true)

    val result = running.await()

    assertEquals(BatchResult(processed = 0, stop = BatchStop.ReaderBusy), result)
    assertNull(f.index.stateOf(book.id))
    assertEquals(0, f.index.chunkCount(book.id))
    assertEquals(listOf(book.id), f.indexer.catalog.eligibleBooks().map { it.id })
  }

  @Test fun `cancelling mid-book leaves the previous index and no terminal write`() = runBlocking {
    val f = fixture()
    val file = epub("big", EpubFixtures.chapter("One", "Highbury was quiet."))
    val book = f.add("Big", file)
    f.indexer.runBatch(deadline)
    val before = f.index.stateOf(book.id)!!
    val changed = f.change(book, file, bigChapters("Marmalade"))
    val running = async(Dispatchers.Default) { f.indexer.runBatch(deadline) }
    delay(400)

    running.cancel()
    runCatching { running.await() }

    assertTrue("the batch finished before it could be cancelled; make the fixture bigger", running.isCancelled)
    assertEquals(before, f.index.stateOf(book.id))
    assertEquals(1, f.index.hits("highbury").size)
    assertEquals(emptyList<Long>(), f.index.hits("marmalade"))
    assertEquals(listOf(changed.id), f.indexer.catalog.eligibleBooks().map { it.id })
  }

  @Test fun `clearing the index while a book is being read keeps it from reappearing`() = runBlocking {
    val f = fixture()
    val other = f.add("Other", epub("other", EpubFixtures.chapter("One", "Persuasion text.")), addedAt = 1)
    f.indexer.runBatch(deadline)
    assertEquals(IndexStateEntity.STATUS_DONE, f.index.stateOf(other.id)!!.status)
    val big = f.add("Big", epub("big", *bigChapters("Marmalade").toTypedArray()), addedAt = 2)
    val running = async(Dispatchers.Default) { f.indexer.runBatch(deadline) }
    delay(400)

    f.indexer.clearIndex()
    val result = running.await()

    assertEquals(BatchStop.Superseded, result.stop)
    assertEquals(0, f.index.chunkCount())
    assertNull(f.index.stateOf(big.id))
    assertNull(f.index.stateOf(other.id))
    assertEquals(emptyList<Long>(), f.index.hits("marmalade"))
    assertEquals(emptyList<Long>(), f.index.hits("persuasion"))
  }

  @Test fun `after the index was cleared the library is indexed again from scratch`() = runBlocking {
    val f = fixture()
    val book = f.add("Emma", epub("emma", EpubFixtures.chapter("One", "Highbury was quiet.")))
    f.indexer.runBatch(deadline)

    f.indexer.clearIndex()
    assertEquals(listOf(book.id), f.indexer.catalog.eligibleBooks().map { it.id })
    assertEquals(1, f.indexer.runBatch(deadline).processed)
    assertEquals(1, f.index.hits("highbury").size)
  }

  /** Three chapters of sixty paragraphs (about 4 KB each, past the 2 KB where jsoup starts misreading `<title/>`), every resource with [head] in its `<head>`; the last chapter mentions a quokka. */
  private fun headed(name: String, head: String, corrupt: Set<String> = emptySet()): File {
    val resources = (0 until 3).map { c ->
      val paragraphs = (0 until 60).joinToString("") { "<p>Paragraph $it of chapter $c tells how the keeper counted gulls.</p>" }
      FixtureResource("c$c.xhtml", "<h2>Chapter $c</h2>$paragraphs" + (if (c == 2) "<p>The final chapter mentions a quokka.</p>" else ""), head)
    }
    return EpubFixtures.write(File(epubDir, "$name.epub"), resources, resources.mapIndexed { c, r -> FixtureToc(r.name, null, "Chapter $c") }, corrupt)
  }

  @Test fun `a book whose chapters have a self-closing title is indexed as fully as one with a closed title`() = runBlocking {
    val f = fixture()
    val selfClosing = f.add("SelfClosing", headed("self-closing", head = "<title/>"))
    val closed = f.add("Closed", headed("closed", head = "<title>T</title>"))

    f.indexer.runBatch(deadline)

    val state = f.index.stateOf(selfClosing.id)!!
    assertEquals(IndexStateEntity.STATUS_DONE, state.status)
    assertEquals(0, state.unreadableResources)
    assertEquals(f.index.chunkTexts(closed.id), f.index.chunkTexts(selfClosing.id))
    assertTrue(f.index.chunkTexts(selfClosing.id).any { it.contains("quokka") })
  }

  @Test fun `a book with one unreadable chapter is indexed from the rest and says so`() = runBlocking {
    val f = fixture()
    val book = f.add("Damaged", headed("damaged", head = "<title>T</title>", corrupt = setOf("c1.xhtml")))

    f.indexer.runBatch(deadline)

    val state = f.index.stateOf(book.id)!!
    assertEquals(IndexStateEntity.STATUS_DONE, state.status)
    assertEquals(1, state.unreadableResources)
    assertFalse(state.truncated)
    assertTrue(f.index.chunkTexts(book.id).any { it.contains("quokka") })
    assertEquals(setOf("Chapter 0", "Chapter 2"), f.chapters(book.id).toSet())
  }

  @Test fun `a book none of whose chapters can be read fails instead of being skipped`() = runBlocking {
    val f = fixture()
    val book = f.add("Ruined", headed("ruined", head = "<title>T</title>", corrupt = setOf("c0.xhtml", "c1.xhtml", "c2.xhtml")))

    assertEquals(1, f.indexer.runBatch(deadline).processed)

    assertEquals(IndexStateEntity.STATUS_FAILED, f.index.stateOf(book.id)!!.status)
    assertEquals(0, f.index.chunkCount(book.id))
  }

  @Test fun `a book that left the library is swept from the index before the next batch`() = runBlocking {
    val f = fixture()
    val kept = f.add("Kept", epub("kept", EpubFixtures.chapter("One", "Highbury was quiet.")))
    val gone = f.add("Gone", epub("gone", EpubFixtures.chapter("One", "Hartfield in the evening.")))
    f.indexer.runBatch(deadline)
    assertEquals(1, f.index.hits("hartfield").size)

    f.db.books().delete(listOf(gone.id))
    f.indexer.runBatch(deadline)

    assertEquals(emptyList<Long>(), f.index.hits("hartfield"))
    assertNull(f.index.stateOf(gone.id))
    assertEquals(1, f.index.hits("highbury").size)
    assertEquals(IndexStateEntity.STATUS_DONE, f.index.stateOf(kept.id)!!.status)
  }

  @Test fun `the one-off merge waits for a first build that put text in the index`() = runBlocking<Unit> {
    val f = fixture()
    val settings = SettingsStore(target)
    settings.setIndexOptimized(false)
    f.indexer.optimizeIfDue() // nothing indexed: must not be recorded as done
    assertFalse(settings.indexOptimized.first())
    f.add("Emma", epub("emma", EpubFixtures.chapter("One", "Highbury was quiet.")))
    f.indexer.runBatch(deadline)
    f.indexer.optimizeIfDue()
    // Done when charging, still waiting otherwise; either way the index keeps answering.
    assertEquals(1, f.index.hits("highbury").size)
    settings.setIndexOptimized(false)
  }
}
