package com.quire.reader.data.mobi

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XhtmlTest {
  private val ns = Xhtml.NS

  @Test fun `well-formed XHTML is kept, with self-closing elements written open and closed`() {
    val source = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="$ns"><head><title>T</title></head><body><a id="x"/><div class="gap"/><p>After the gap.<br/></p><img src="a.jpg" alt=""/></body></html>"""
    val out = Xhtml.document(source, "T")
    assertTrue(out.contains("""<a id="x"></a><div class="gap"></div><p>After the gap.<br/></p><img src="a.jpg" alt=""/>"""))
    assertTrue(Xhtml.isWellFormed(out))
    // Read as HTML, as Readium's content iterator reads it, the paragraph is not swallowed by the anchor or the div.
    val p = Jsoup.parse(out).selectFirst("p")!!
    assertEquals("body", p.parent()!!.tagName())
  }

  @Test fun `markup that is not well-formed is rewritten as XHTML`() {
    val out = Xhtml.document("<html><body><p>One<p>Two &nbsp; <b>bold</body></html>", "Book")
    assertTrue(Xhtml.isWellFormed(out))
    assertTrue(out.contains("xmlns=\"$ns\""))
    assertTrue(out.contains("<title>Book</title>"))
    assertFalse(out.contains("&nbsp;"))
  }
}
