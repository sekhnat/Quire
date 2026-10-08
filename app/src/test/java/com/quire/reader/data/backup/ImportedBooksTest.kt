package com.quire.reader.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportedBooksTest {
  @get:Rule val tmp = TemporaryFolder()

  private val dir by lazy { tmp.newFolder("imported") }
  private val incomingDir by lazy { tmp.newFolder("incoming") }

  private fun file(dir: File, name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }
  private fun incoming(bytes: ByteArray) = file(incomingDir, "Emma.epub.tmp", bytes)

  /** Bytes long enough to span several of the comparison's buffers. */
  private val book = ByteArray(200_000) { (it * 31 % 251).toByte() }
  private fun changedAt(index: Int) = book.copyOf().also { it[index] = (it[index] + 1).toByte() }

  @Test fun `identical files have the same contents`() {
    assertTrue(ImportedBooks.sameContents(file(dir, "a", book), file(incomingDir, "b", book.copyOf())))
    assertTrue(ImportedBooks.sameContents(file(dir, "empty-a", ByteArray(0)), file(incomingDir, "empty-b", ByteArray(0))))
  }

  @Test fun `files of the same size that differ anywhere do not`() {
    val a = file(dir, "a", book)
    for (index in listOf(0, (1 shl 16) - 1, 1 shl 16, book.size - 1)) {
      assertFalse("differs at $index", ImportedBooks.sameContents(a, file(incomingDir, "b$index", changedAt(index))))
    }
  }

  @Test fun `files of different sizes do not`() {
    assertFalse(ImportedBooks.sameContents(file(dir, "a", book), file(incomingDir, "b", book + 0)))
    assertFalse(ImportedBooks.sameContents(file(dir, "c", ByteArray(0)), file(incomingDir, "d", byteArrayOf(0))))
  }

  @Test fun `an identical book of the same name is not copied again`() {
    val local = file(dir, "Emma.epub", book)
    val modified = local.lastModified()
    val incoming = incoming(book.copyOf())
    assertNull(ImportedBooks.placeIncoming(dir, "Emma.epub", incoming))
    assertFalse(incoming.exists())
    assertEquals(setOf("Emma.epub"), dir.list()!!.toSet())
    assertArrayEquals(book, local.readBytes())
    assertEquals(modified, local.lastModified())
  }

  @Test fun `a different book of the same name and size is kept beside it`() {
    file(dir, "Emma.epub", book)
    val other = changedAt(book.size / 2)
    val placed = ImportedBooks.placeIncoming(dir, "Emma.epub", incoming(other))
    assertEquals(File(dir, "Emma (2).epub"), placed)
    assertArrayEquals(book, File(dir, "Emma.epub").readBytes())
    assertArrayEquals(other, placed!!.readBytes())
  }

  @Test fun `a different book of the same name and another size is kept beside it`() {
    file(dir, "Emma.epub", book)
    val other = book.copyOf(1000)
    val placed = ImportedBooks.placeIncoming(dir, "Emma.epub", incoming(other))
    assertEquals(File(dir, "Emma (2).epub"), placed)
    assertArrayEquals(book, File(dir, "Emma.epub").readBytes())
    assertArrayEquals(other, placed!!.readBytes())
  }

  @Test fun `a book already kept under a numbered name is not copied again`() {
    file(dir, "Emma.epub", book)
    val other = changedAt(7)
    assertEquals(File(dir, "Emma (2).epub"), ImportedBooks.placeIncoming(dir, "Emma.epub", incoming(other)))
    assertNull(ImportedBooks.placeIncoming(dir, "Emma.epub", incoming(other.copyOf())))
    val third = changedAt(8)
    assertEquals(File(dir, "Emma (3).epub"), ImportedBooks.placeIncoming(dir, "Emma.epub", incoming(third)))
    assertEquals(setOf("Emma.epub", "Emma (2).epub", "Emma (3).epub"), dir.list()!!.toSet())
  }

  @Test fun `a name without an extension is numbered before nothing`() {
    file(dir, "notes", book)
    assertEquals(File(dir, "notes (2)"), ImportedBooks.placeIncoming(dir, "notes", incoming(changedAt(0))))
  }
}
