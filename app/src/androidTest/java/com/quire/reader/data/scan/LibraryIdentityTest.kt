package com.quire.reader.data.scan

import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.BookTagEntity
import com.quire.reader.data.db.DbTestCase
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.EpubFixtures
import com.quire.reader.reader.PublicationLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Reading history survives Calibre renames, moves, vanished files and removed folders, through real scans of real files. */
class LibraryIdentityTest : DbTestCase() {
  private val root = File(target.cacheDir, "identity-tests/${UUID.randomUUID()}")
  private val lib = File(root, "lib")

  @After fun deleteFiles() { root.deleteRecursively() }

  private class Fixture(val db: QuireDatabase, val folderId: Long, val scanner: LibraryScanner)

  private fun fixture(): Fixture = runBlocking {
    lib.mkdirs()
    val db = open()
    val folderId = db.folders().insert(FolderEntity(path = lib.absolutePath))
    Fixture(db, folderId, LibraryScanner(db, PublicationLoader(target), CoverStore(target), SettingsStore(target)))
  }

  private fun book(file: File, text: String = "It was a dark and stormy night.") =
    EpubFixtures.write(file, listOf(EpubFixtures.chapter("One", text)))

  private fun calibreOpf(dir: File, uuid: String, title: String) = File(dir, "metadata.opf").writeText(
    """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uuid_id" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">""" +
      """<dc:identifier opf:scheme="uuid" id="uuid_id">$uuid</dc:identifier><dc:title>$title</dc:title><dc:creator opf:role="aut">Ann Author</dc:creator><dc:subject>Mystery</dc:subject></metadata></package>""",
  )

  /** Gives the single book in the library a reading position, a bookmark, a highlight with a note and a tag of the user's. */
  private fun Fixture.read(bookId: Long) = runBlocking {
    db.states().edit(bookId) { it.copy(locatorJson = """{"href":"c0.xhtml"}""", progress = 0.4f, status = "reading", lastOpenedAt = 1_000) }
    db.annotations().addBookmark(BookmarkEntity(bookId = bookId, locatorJson = "{}", label = "One", progress = 0.1f, createdAt = 1))
    db.annotations().addHighlight(HighlightEntity(bookId = bookId, locatorJson = "{}", text = "dark and stormy", note = "nice", progress = 0.2f, createdAt = 2))
    db.books().insertTags(listOf(BookTagEntity(bookId, "favourite", BookTagEntity.ORIGIN_USER)))
  }

  private fun Fixture.assertHistory(bookId: Long) = runBlocking {
    assertEquals(0.4f, db.states().get(bookId)!!.progress, 0f)
    assertEquals(listOf("One"), db.annotations().observeBookmarks(bookId).first().map { it.label })
    assertEquals(listOf("nice"), db.annotations().observeHighlights(bookId).first().map { it.note })
  }

  private fun Fixture.live() = runBlocking { db.books().observeAll().first() }
  private fun Fixture.missing() = runBlocking { db.books().observeMissing().first() }
  private fun Fixture.scan() = runBlocking { scanner.scan() }

  /** Moves [from] to [to] the way a sync or file manager does, keeping the modified time. */
  private fun move(from: File, to: File) {
    to.parentFile!!.mkdirs()
    val mtime = from.lastModified()
    assertTrue(from.renameTo(to))
    to.setLastModified(mtime)
  }

  @Test fun `a Calibre title edit renames folder and file and the book keeps its id and history`() {
    val f = fixture()
    val oldDir = File(lib, "Ann Author/Teh Title (12)")
    book(File(oldDir, "Teh Title - Ann Author.epub"))
    calibreOpf(oldDir, "calibre-uuid-12", "Teh Title")
    f.scan()
    val id = f.live().single().id
    f.read(id)

    val newDir = File(lib, "Ann Author/The Title (12)")
    move(File(oldDir, "Teh Title - Ann Author.epub"), File(newDir, "The Title - Ann Author.epub"))
    calibreOpf(newDir, "calibre-uuid-12", "The Title")
    oldDir.deleteRecursively()
    val result = f.scan()

    val row = f.live().single()
    assertEquals(id, row.id)
    assertEquals("The Title", row.title)
    assertTrue(row.path.endsWith("The Title (12)/The Title - Ann Author.epub"))
    assertEquals(setOf("Mystery", "favourite"), row.tagList.toSet())
    f.assertHistory(id)
    assertEquals(emptyList<Any>(), f.missing())
    assertEquals(ScanResult(added = 0, updated = 0, removed = 0, unreadable = 0, moved = 1), result)
  }

  @Test fun `a file moved to another folder keeps its history and its text index`() = runBlocking {
    val f = fixture()
    val file = book(File(lib, "inbox/Novel.epub"))
    f.scan()
    val before = f.db.books().byId(f.live().single().id)!!
    f.read(before.id)
    f.db.index().replaceBook(before.id, before.mtime, before.sizeBytes, listOf(chunk(before.id, 0, "dark and stormy night")), doneState(before, before.id, chunks = 1))

    move(file, File(lib, "shelf/Novel (renamed).epub"))
    f.scan()

    val after = f.db.books().byId(before.id)!!
    assertTrue(after.path.endsWith("shelf/Novel (renamed).epub"))
    assertNull(after.missingSince)
    f.assertHistory(before.id)
    assertNotNull(f.db.stateOf(before.id))
    assertEquals(1, f.db.hits("stormy").size)
    assertEquals(1, f.live().size)
  }

  @Test fun `a vanished book with history is kept as missing and comes back with its file`() {
    val f = fixture()
    val file = book(File(lib, "Novel.epub"))
    f.scan()
    val id = f.live().single().id
    f.read(id)
    val kept = File(root, "kept.epub")
    move(file, kept)

    assertEquals(1, f.scan().removed)
    assertEquals(emptyList<Any>(), f.live())
    val missing = f.missing().single()
    assertEquals(id, missing.id)
    assertEquals(1, missing.highlights)
    assertEquals(1, missing.bookmarks)
    f.assertHistory(id)

    move(kept, file)
    f.scan()
    assertEquals(id, f.live().single().id)
    assertEquals(emptyList<Any>(), f.missing())
    f.assertHistory(id)
  }

  @Test fun `a vanished book without history is forgotten`() = runBlocking {
    val f = fixture()
    val file = book(File(lib, "Novel.epub"))
    f.scan()
    val id = f.live().single().id
    file.delete()
    f.scan()
    assertNull(f.db.books().byId(id))
    assertEquals(emptyList<Any>(), f.missing())
  }

  @Test fun `a file that turns up long after its book went missing takes the history back`() {
    val f = fixture()
    val file = book(File(lib, "Novel.epub"))
    f.scan()
    val id = f.live().single().id
    f.read(id)
    val away = File(root, "away.epub")
    move(file, away)
    f.scan()
    assertEquals(1, f.missing().size)

    move(away, File(lib, "Later/Novel - copy.epub"))
    f.scan()
    assertEquals(id, f.live().single().id)
    f.assertHistory(id)
  }

  @Test fun `history never moves onto a book that has history of its own`() {
    val f = fixture()
    // Every fixture EPUB has the same unique identifier and title, so they would match on the weakest key.
    val a = book(File(lib, "A.epub"), "First book.")
    val b = book(File(lib, "B.epub"), "Second book, a different one.")
    f.scan()
    val idA = f.live().single { it.path == a.absolutePath }.id
    val idB = f.live().single { it.path == b.absolutePath }.id
    f.read(idA)
    runBlocking { f.db.states().edit(idB) { it.copy(lastOpenedAt = 2_000, status = "reading") } }
    a.delete()
    f.scan()

    assertEquals(idA, f.missing().single().id)
    assertEquals(listOf(idB), f.live().map { it.id })
    f.assertHistory(idA)
  }

  @Test fun `removing a folder keeps history under missing books and adding it again brings the books back`() = runBlocking {
    val f = fixture()
    book(File(lib, "Kept.epub"), "A book that was read.")
    book(File(lib, "Dropped.epub"), "A book never opened.")
    f.scan()
    val read = f.live().single { it.path.endsWith("Kept.epub") }.id
    val unread = f.live().single { it.path.endsWith("Dropped.epub") }.id
    f.read(read)

    val removal = f.db.books().removeFolder(f.folderId, at = 5_000)
    assertEquals(1, removal.keptWithHistory)
    assertEquals(emptyList<FolderEntity>(), f.db.folders().observeAll().first())
    assertEquals(emptyList<Any>(), f.live())
    assertEquals(listOf(read), f.missing().map { it.id })
    assertNull(f.db.books().byId(unread))
    f.scan() // The removed folder is not walked, so nothing changes.
    assertEquals(listOf(read), f.missing().map { it.id })

    f.db.folders().rewatch(f.folderId)
    f.scan()
    assertEquals(read, f.live().single { it.path.endsWith("Kept.epub") }.id)
    assertEquals(2, f.live().size)
    f.assertHistory(read)
  }

  @Test fun `a removed folder whose books have no history is deleted outright`() = runBlocking {
    val f = fixture()
    book(File(lib, "Unread.epub"))
    f.scan()
    assertEquals(0, f.db.books().removeFolder(f.folderId, at = 5_000).keptWithHistory)
    assertEquals(emptyList<FolderEntity>(), f.db.folders().all())
  }

  @Test fun `books stored before identity keys get them on the next scan and can then move`() = runBlocking {
    val f = fixture()
    val file = book(File(lib, "Old.epub"))
    // A row the way a version 3 scan left it: no identity.
    val id = f.db.books().save(bookEntity(f.folderId, "Old").copy(path = file.absolutePath, mtime = file.lastModified(), sizeBytes = file.length()), emptyList())
    assertFalse(f.db.books().knownFiles().single().hasIdentity)
    f.read(id)

    val result = f.scan()
    assertEquals(ScanResult(added = 0, updated = 0, removed = 0, unreadable = 0), result)
    assertEquals(BookIdentity.fingerprint(file), f.db.books().byId(id)!!.fingerprint)

    move(file, File(lib, "Moved/Old.epub"))
    f.scan()
    assertEquals(id, f.live().single().id)
    f.assertHistory(id)
  }

  @Test fun `forgetting a missing book deletes its history and leaves live books alone`() = runBlocking {
    val f = fixture()
    val a = book(File(lib, "A.epub"), "First book.")
    book(File(lib, "B.epub"), "Second book.")
    f.scan()
    val idA = f.live().single { it.path == a.absolutePath }.id
    val idB = f.live().single { it.id != idA }.id
    f.read(idA)
    // Fixture books share their identifier and title; history of B's own keeps A from taking B's file over.
    f.db.states().edit(idB) { it.copy(lastOpenedAt = 2_000) }
    a.delete()
    f.scan()

    f.db.books().forgetMissing(listOf(idA, idB))
    assertNull(f.db.books().byId(idA))
    assertNull(f.db.states().get(idA))
    assertEquals(emptyList<Any>(), f.missing())
    assertEquals(listOf(idB), f.live().map { it.id })
  }
}
