package com.quire.reader.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class BookIdentityTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun epub(file: File, chapter: String = "<p>Hello</p>", uid: String? = "urn:uuid:book-1", padding: Int = 0): File {
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
      fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
      put("mimetype", "application/epub+zip")
      put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
      val uniqueIdentifier = if (uid == null) "" else """ unique-identifier="id""""
      val identifier = if (uid == null) "" else """<dc:identifier id="id">$uid</dc:identifier>"""
      put("OEBPS/content.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"$uniqueIdentifier><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$identifier<dc:title>T</dc:title></metadata></package>""")
      put("OEBPS/ch1.xhtml", chapter)
      // Random bytes do not compress, so the file really is larger than the hashed tail.
      if (padding > 0) { zip.putNextEntry(ZipEntry("OEBPS/pad.bin")); zip.write(Random(7).nextBytes(padding)); zip.closeEntry() }
    }
    return file
  }

  private fun calibreOpf(dir: File, uuid: String) = File(dir, "metadata.opf").writeText(
    """<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uuid_id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf"><dc:identifier opf:scheme="uuid" id="uuid_id">$uuid</dc:identifier><dc:title>T</dc:title></metadata></package>""",
  )

  @Test fun `the fingerprint follows the content, not the name or place of the file`() {
    val a = epub(tmp.root.resolve("a/Book.epub"))
    val copy = a.copyTo(tmp.root.resolve("elsewhere/Renamed.epub"))
    val changed = epub(tmp.root.resolve("b/Book.epub"), chapter = "<p>Hellp</p>")
    assertNotNull(BookIdentity.fingerprint(a))
    assertEquals(BookIdentity.fingerprint(a), BookIdentity.fingerprint(copy))
    assertNotEquals(BookIdentity.fingerprint(a), BookIdentity.fingerprint(changed))
  }

  @Test fun `a change at the start of a file larger than the hashed tail still changes the fingerprint`() {
    // Stored entries' CRC-32s are in the central directory at the end, so an edit far from the tail shows up there.
    val a = epub(tmp.root.resolve("a.epub"), chapter = "<p>A</p>", padding = 3 * BookIdentity.TAIL_BYTES)
    val b = epub(tmp.root.resolve("b.epub"), chapter = "<p>B</p>", padding = 3 * BookIdentity.TAIL_BYTES)
    assertEquals(a.length(), b.length())
    assert(a.length() > 2 * BookIdentity.TAIL_BYTES)
    assertNotEquals(BookIdentity.fingerprint(a), BookIdentity.fingerprint(b))
  }

  @Test fun `the EPUB unique identifier is read through the container`() {
    assertEquals("urn:uuid:book-1", BookIdentity.epubUid(epub(tmp.root.resolve("a.epub"))))
    assertNull(BookIdentity.epubUid(epub(tmp.root.resolve("b.epub"), uid = null)))
    assertNull(BookIdentity.epubUid(tmp.newFile("broken.epub").apply { writeText("not a zip") }))
  }

  @Test fun `the Calibre uuid counts only for the single EPUB of a folder`() {
    val dir = tmp.root.resolve("Author/Title (12)")
    val book = epub(dir.resolve("Title - Author.epub"))
    calibreOpf(dir, "c-uuid")
    assertEquals("c-uuid", BookIdentity.calibreUuid(book))
    assertEquals(BookIdentity("c-uuid", "urn:uuid:book-1", BookIdentity.fingerprint(book)), BookIdentity.read(book))

    epub(dir.resolve("Other.epub"))
    assertNull(BookIdentity.calibreUuid(book))
    assertNull(BookIdentity.calibreUuid(epub(tmp.root.resolve("plain/Book.epub"))))
  }

  @Test fun `an unreadable file has no keys`() {
    assertEquals(BookIdentity(null, null, null), BookIdentity.read(tmp.root.resolve("gone.epub")))
  }
}
