package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.jsoup.Jsoup
import org.junit.Test

class ChapterLabelerTest {
  private val order = listOf("cover.xhtml", "ch1.xhtml", "ch2.xhtml", "ch3.xhtml")

  @Test fun `files take the label of the last chapter that starts at or before them`() {
    val labeler = ChapterLabeler(order, listOf(ChapterEntry("ch1.xhtml", null, "One"), ChapterEntry("ch3.xhtml", null, "Three")))
    assertEquals("", labeler.labelFor("cover.xhtml", null))
    assertEquals("One", labeler.labelFor("ch1.xhtml", "p:nth-child(1)"))
    assertEquals("One", labeler.labelFor("ch2.xhtml", "p:nth-child(1)"))
    assertEquals("Three", labeler.labelFor("ch3.xhtml", "p:nth-child(1)"))
  }

  @Test fun `an anchored chapter takes over from the first element under its anchor`() {
    val labeler = ChapterLabeler(
      order,
      listOf(ChapterEntry("ch1.xhtml", null, "Part"), ChapterEntry("ch1.xhtml", "a", "Alpha"), ChapterEntry("ch1.xhtml", "b", "Beta")),
    )
    assertEquals("Part", labeler.labelFor("ch1.xhtml", "#intro > p:nth-child(1)"))
    assertEquals("Alpha", labeler.labelFor("ch1.xhtml", "#a"))
    assertEquals("Alpha", labeler.labelFor("ch1.xhtml", "#a ~ p"))
    assertEquals("Alpha", labeler.labelFor("ch1.xhtml", "#unrelated > p"))
    assertEquals("Beta", labeler.labelFor("ch1.xhtml", "#b > h2"))
  }

  @Test fun `an anchor only matches a whole id`() {
    val labeler = ChapterLabeler(order, listOf(ChapterEntry("ch1.xhtml", "ch1", "Chapter 1")))
    assertEquals("", labeler.labelFor("ch1.xhtml", "#ch10 > p"))
    assertEquals("Chapter 1", labeler.labelFor("ch1.xhtml", "#ch1 > p"))
  }

  @Test fun `untitled entries are ignored and titles are trimmed`() {
    val labeler = ChapterLabeler(order, listOf(ChapterEntry("ch1.xhtml", null, " One "), ChapterEntry("ch2.xhtml", null, "  ")))
    assertEquals("One", labeler.labelFor("ch2.xhtml", null))
  }

  @Test fun `a file missing from the reading order keeps the current label`() {
    val labeler = ChapterLabeler(order, listOf(ChapterEntry("ch1.xhtml", null, "One")))
    labeler.labelFor("ch1.xhtml", null)
    assertEquals("One", labeler.labelFor("extra.xhtml", null))
  }

  // ── labels judged by document order (anchors that the element selectors do not name) ──────────────────────────────────

  /** The parsed [html] and a way to get the selector Readium reports for an element: jsoup's, over the same parse. */
  private class Page(html: String) {
    private val doc = Jsoup.parse(html)
    val order = ResourceOrder.parse(html)!!
    fun css(query: String): String = doc.selectFirst(query)!!.cssSelector()
  }

  private val book = listOf("front.xhtml", "ch1.xhtml", "ch2.xhtml", "ch3.xhtml")

  @Test fun `an anchor inside a heading starts its chapter although the selector does not name it`() {
    val page = Page("<body><h1><a id=\"ch2\">2</a></h1><p id=\"first\">Text.</p><p>More.</p></body>")
    val labeler = ChapterLabeler(book, listOf(ChapterEntry("ch1.xhtml", null, "One"), ChapterEntry("ch1.xhtml", "ch2", "Two")))
    val heading = labeler.markFor("ch1.xhtml", page.css("h1"), page.order)
    assertEquals(ChapterMark("Two", startsChapter = true), heading)
    assertEquals(ChapterMark("Two", startsChapter = false), labeler.markFor("ch1.xhtml", page.css("p#first"), page.order))
    assertEquals(ChapterMark("Two", startsChapter = false), labeler.markFor("ch1.xhtml", page.css("p:not(#first)"), page.order))
  }

  @Test fun `an empty anchor before a heading starts the chapter at that heading`() {
    val page = Page("<body><p>End of one.</p><a id=\"c2\"/><h2>Two</h2><p>Body.</p></body>")
    val labeler = ChapterLabeler(book, listOf(ChapterEntry("ch1.xhtml", null, "One"), ChapterEntry("ch1.xhtml", "c2", "Two")))
    assertEquals("One", labeler.labelFor("ch1.xhtml", page.css("p"), page.order))
    assertEquals(ChapterMark("Two", true), labeler.markFor("ch1.xhtml", page.css("h2"), page.order))
    assertEquals("Two", labeler.labelFor("ch1.xhtml", page.css("h2 ~ p"), page.order))
  }

  @Test fun `several chapters in one file each own the elements up to the next anchor`() {
    val page = Page(
      "<body><p id=\"pre\">Before any chapter.</p>" +
        "<div id=\"a\"><h2>Alpha</h2><p class=\"a1\">a one</p><p class=\"a2\">a two</p></div>" +
        "<span id=\"b\"></span><h2 class=\"hb\">Beta</h2><p class=\"b1\">b one</p>" +
        "<h2 class=\"hc\"><a name=\"c\"></a>Gamma</h2><p class=\"c1\">c one</p></body>",
    )
    val labeler = ChapterLabeler(
      book,
      listOf(ChapterEntry("ch1.xhtml", "a", "Alpha"), ChapterEntry("ch1.xhtml", "b", "Beta"), ChapterEntry("ch1.xhtml", "c", "Gamma")),
    )
    val labels = listOf("#pre", ".a1", ".a2", ".b1", ".c1").associateWith { labeler.labelFor("ch1.xhtml", page.css(it), page.order) }
    assertEquals(mapOf("#pre" to "", ".a1" to "Alpha", ".a2" to "Alpha", ".b1" to "Beta", ".c1" to "Gamma"), labels)
  }

  @Test fun `chapter starts are reported once per chapter in a file`() {
    val page = Page("<body><h2 id=\"a\">A</h2><p>a</p><h2 id=\"b\">B</h2><p>b</p></body>")
    val labeler = ChapterLabeler(book, listOf(ChapterEntry("ch1.xhtml", "a", "Alpha"), ChapterEntry("ch1.xhtml", "b", "Beta")))
    val marks = listOf("h2#a", "h2#a + p", "h2#b", "h2#b + p").map { labeler.markFor("ch1.xhtml", page.css(it), page.order) }
    assertEquals(listOf(true, false, true, false), marks.map { it.startsChapter })
    assertEquals(listOf("Alpha", "Alpha", "Beta", "Beta"), marks.map { it.label })
  }

  @Test fun `elements before the first anchor keep the chapter of the previous file or none`() {
    val page = Page("<body><p class=\"lead\">Lead-in.</p><h2 id=\"two\">Two</h2><p class=\"t\">Text.</p></body>")
    val firstBook = ChapterLabeler(book, listOf(ChapterEntry("ch1.xhtml", "two", "Two")))
    assertEquals("", firstBook.labelFor("ch1.xhtml", page.css(".lead"), page.order))
    assertEquals("Two", firstBook.labelFor("ch1.xhtml", page.css(".t"), page.order))

    val later = ChapterLabeler(book, listOf(ChapterEntry("front.xhtml", null, "Preface"), ChapterEntry("ch1.xhtml", "two", "Two")))
    assertEquals("Preface", later.labelFor("front.xhtml", null))
    assertEquals("Preface", later.labelFor("ch1.xhtml", page.css(".lead"), page.order))
    assertEquals("Two", later.labelFor("ch1.xhtml", page.css(".t"), page.order))
  }

  @Test fun `front matter before every chapter has an empty label and the first chapter starts at its file`() {
    val labeler = ChapterLabeler(book, listOf(ChapterEntry("ch1.xhtml", null, "One"), ChapterEntry("ch2.xhtml", null, "Two")))
    assertEquals(ChapterMark("", false), labeler.markFor("front.xhtml", "p", null))
    assertEquals("One", labeler.labelFor("ch1.xhtml", "p:nth-child(1)"))
    assertEquals("Two", labeler.labelFor("ch2.xhtml", "p:nth-child(1)"))
  }

  @Test fun `nested contents take the deepest chapter that has begun`() {
    // Part One (ch1) > Chapter 1 (ch1#c1), Chapter 2 (ch2); Part Two (ch3) > Chapter 3 (ch3#c3). Flattened parents first.
    val entries = listOf(
      ChapterEntry("ch1.xhtml", null, "Part One"), ChapterEntry("ch1.xhtml", "c1", "Chapter 1"), ChapterEntry("ch2.xhtml", null, "Chapter 2"),
      ChapterEntry("ch3.xhtml", null, "Part Two"), ChapterEntry("ch3.xhtml", "c3", "Chapter 3"),
    )
    val labeler = ChapterLabeler(book, entries)
    val ch1 = Page("<body><p class=\"epigraph\">Epigraph.</p><h2 id=\"c1\">1</h2><p class=\"t\">Text.</p></body>")
    assertEquals("Part One", labeler.labelFor("ch1.xhtml", ch1.css(".epigraph"), ch1.order))
    assertEquals("Chapter 1", labeler.labelFor("ch1.xhtml", ch1.css(".t"), ch1.order))
    assertEquals("Chapter 2", labeler.labelFor("ch2.xhtml", "p:nth-child(1)"))
    val ch3 = Page("<body><p class=\"part\">Part title page.</p><h2 id=\"c3\">3</h2><p class=\"t\">Text.</p></body>")
    assertEquals("Part Two", labeler.labelFor("ch3.xhtml", ch3.css(".part"), ch3.order))
    assertEquals("Chapter 3", labeler.labelFor("ch3.xhtml", ch3.css(".t"), ch3.order))
  }

  @Test fun `a chapter whose contents entry has no fragment spans its whole file and the next file continues it`() {
    val labeler = ChapterLabeler(book, listOf(ChapterEntry("ch1.xhtml", null, "One")))
    assertEquals("One", labeler.labelFor("ch1.xhtml", "p:nth-child(1)"))
    assertEquals("One", labeler.labelFor("ch2.xhtml", "p:nth-child(1)"))
  }

  @Test fun `an anchor the file does not contain changes nothing and an unknown selector falls back to its text`() {
    val page = Page("<body><p class=\"a\">Text.</p></body>")
    val labeler = ChapterLabeler(book, listOf(ChapterEntry("ch1.xhtml", null, "One"), ChapterEntry("ch1.xhtml", "gone", "Gone"), ChapterEntry("ch1.xhtml", "later", "Later")))
    assertEquals("One", labeler.labelFor("ch1.xhtml", page.css(".a"), page.order))
    assertEquals("Later", labeler.labelFor("ch1.xhtml", "#later > p", page.order))
  }

  @Test fun `contents listed out of reading order do not move a later file back`() {
    val labeler = ChapterLabeler(book, listOf(ChapterEntry("ch3.xhtml", null, "Three"), ChapterEntry("ch1.xhtml", null, "One")))
    assertEquals("One", labeler.labelFor("ch2.xhtml", null))
    assertEquals("Three", labeler.labelFor("ch3.xhtml", null))
  }

  @Test fun `only files with chapters that begin at anchors need a document order`() {
    val labeler = ChapterLabeler(book, listOf(ChapterEntry("ch1.xhtml", null, "One"), ChapterEntry("ch2.xhtml", "x", "Two"), ChapterEntry("elsewhere.xhtml", "y", "Off")))
    assertFalse(labeler.hasAnchors("ch1.xhtml"))
    assertTrue(labeler.hasAnchors("ch2.xhtml"))
    assertFalse(labeler.hasAnchors("elsewhere.xhtml"))
  }

  @Test fun `heading selectors end in a heading tag`() {
    assertTrue(isHeadingSelector("h1"))
    assertTrue(isHeadingSelector("#pgepubid00007 > h2:nth-child(2)"))
    assertTrue(isHeadingSelector("body > div.chapter > h3.title"))
    assertTrue(isHeadingSelector("#x h4"))
  }

  @Test fun `paragraphs and look-alike tags are not headings`() {
    assertFalse(isHeadingSelector(null))
    assertFalse(isHeadingSelector(""))
    assertFalse(isHeadingSelector("#pgepubid00007 > a > p:nth-child(4)"))
    assertFalse(isHeadingSelector("h7"))
    assertFalse(isHeadingSelector("h1x"))
    assertFalse(isHeadingSelector("div > h2 > span"))
    assertFalse(isHeadingSelector("#h1"))
  }
}
