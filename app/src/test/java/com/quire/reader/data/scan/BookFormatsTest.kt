package com.quire.reader.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookFormatsTest {
  private fun names(vararg n: String) = BookFormats.preferred(n.map { File("/books/$it") }).map { it.name }.sorted()

  @Test fun `one book per name, best format first`() {
    assertEquals(listOf("Emma - Jane Austen.epub"), names("Emma - Jane Austen.mobi", "Emma - Jane Austen.epub", "Emma - Jane Austen.azw3"))
    assertEquals(listOf("Emma.azw3"), names("Emma.mobi", "Emma.azw3"))
    assertEquals(listOf("Emma.MOBI"), names("Emma.MOBI"))
    assertEquals(listOf("Emma.EPUB"), names("Emma.EPUB", "emma.azw3"))
  }

  @Test fun `different books and other files`() {
    assertEquals(listOf("A.mobi", "B.azw3", "C.epub"), names("A.mobi", "B.azw3", "C.epub", "cover.jpg", "metadata.opf", "D.pdf", "E.azw", ".hidden.epub"))
  }

  @Test fun `formats and labels`() {
    assertTrue(BookFormats.isBook("x.Azw3"))
    assertFalse(BookFormats.isBook("x.prc"))
    assertTrue(BookFormats.isMobi(File("x.mobi")))
    assertTrue(BookFormats.isMobi(File("x.azw3")))
    assertFalse(BookFormats.isMobi(File("x.epub")))
    assertEquals("AZW3", BookFormats.label("/a/b.azw3"))
    assertEquals("MOBI", BookFormats.label("/a/b.mobi"))
    assertEquals("EPUB", BookFormats.label("/a/b.epub"))
  }
}
