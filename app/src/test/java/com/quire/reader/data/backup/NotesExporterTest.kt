package com.quire.reader.data.backup

import com.quire.reader.data.db.HighlightEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotesExporterTest {
  private fun highlight(
    locatorJson: String, text: String, note: String? = null, progress: Float = 0.5f, createdAt: Long = 1,
  ) = HighlightEntity(bookId = 1, locatorJson = locatorJson, text = text, note = note, progress = progress, createdAt = createdAt)

  private val identityKey = "calibre:uuid-1"

  @Test fun `a note file is titled, ordered by position, and carries a locator comment`() {
    val markdown = NotesExporter.markdown(
      title = "Emma", author = "Jane Austen", identityKey = identityKey,
      highlights = listOf(
        highlight("""{"href":"c2.xhtml","title":"Chapter 2"}""", "Later passage", progress = 0.6f, createdAt = 2),
        highlight("""{"href":"c1.xhtml","title":"Chapter 1"}""", "First passage", note = "nice", progress = 0.2f, createdAt = 1),
      ),
    )
    val lines = markdown.split("\n")
    assertEquals(listOf("# Emma", "", "Jane Austen"), lines.take(3))
    // Reading order, not export order.
    val quoteLines = lines.withIndex().filter { it.value.startsWith("> ") }.map { it.value }
    assertEquals(listOf("> First passage", "> Later passage"), quoteLines)
    // The locator comments are machine-readable and escape the identity key.
    val comments = lines.filter { it.startsWith("<!-- quire://") }
    assertEquals(2, comments.size)
    assertFalse(comments.first().contains("calibre:uuid-1"))
    assertTrueComment(comments.first(), """{"href":"c1.xhtml","title":"Chapter 1"}""")
  }

  private fun assertTrueComment(comment: String, locatorJson: String) {
    val expected = "quire://book/" + java.net.URLEncoder.encode(identityKey, "UTF-8") +
      "?locator=" + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(locatorJson.toByteArray())
    assertEquals("<!-- $expected -->", comment)
  }

  @Test fun `notes are their own paragraphs and variants stay separate`() {
    val markdown = NotesExporter.markdown(
      "Emma", "Jane Austen", identityKey,
      listOf(highlight("""{"href":"c1.xhtml","title":"Chapter 1"}""", "passage", note = "one${NoteVariants.SEPARATOR}two")),
    )
    assertEquals(true, markdown.contains("\none\n"))
    assertEquals(true, markdown.contains("\ntwo\n"))
    assertEquals(false, markdown.contains(NoteVariants.SEPARATOR))
  }

  @Test fun `chapter labels come from the locator then the table of contents then the resource then nothing`() {
    fun chapter(h: HighlightEntity, toc: Map<String, String> = emptyMap()) =
      NotesExporter.markdown("T", "A", identityKey, listOf(h), toc).split("\n").first { it.startsWith("## ") }.removePrefix("## ")
    assertEquals("Stamped", chapter(highlight("""{"href":"c1.xhtml","title":"Stamped"}""", "x")))
    assertEquals("From contents", chapter(highlight("""{"href":"c1.xhtml"}""", "x"), mapOf("c1.xhtml" to "From contents")))
    assertEquals("c3.xhtml", chapter(highlight("""{"href":"c3.xhtml"}""", "x")))
    assertEquals("Unknown chapter", chapter(highlight("not json", "x")))
  }

  @Test fun `a multiline highlight is quoted line by line`() {
    val markdown = NotesExporter.markdown(
      "T", "A", identityKey,
      listOf(highlight("""{"href":"c1.xhtml","title":"C"}""", "first line\nsecond line")),
    )
    assertEquals(true, markdown.contains("> first line\n> second line"))
  }
}
