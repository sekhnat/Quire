package com.quire.reader.data.index

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlNormalizerTest {
  @Test fun `a self-closing title is written as a pair however it is spelled`() {
    assertEquals("<head><title></title></head>", normalizeHtml("<head><title/></head>"))
    assertEquals("<head><TITLE ></TITLE></head>", normalizeHtml("<head><TITLE /></head>"))
    assertEquals("""<title id="x"></title>""", normalizeHtml("""<title id="x"/>"""))
  }

  @Test fun `every raw-text element is repaired, attributes and slashes in values kept`() {
    for (tag in listOf("script", "style", "textarea", "xmp", "iframe", "noembed", "noframes")) {
      assertEquals("""<$tag src="a/b.js"></$tag>""", normalizeHtml("""<$tag src="a/b.js"/>"""))
    }
  }

  @Test fun `void elements, closed titles and look-alike names are left alone`() {
    val html = """<head><title>Book</title><meta charset="utf-8"/><link href="s.css"/></head><body><p>a<br/>b</p><img src="i.png"/><titles/><subtitle/></body>"""
    assertSame(html, normalizeHtml(html))
  }

  @Test fun `a document with nothing to repair is returned as the same instance`() {
    val html = "<html><head><title>T</title></head><body><p>Text</p></body></html>"
    assertSame(html, normalizeHtml(html))
  }

  // jsoup recovers from `<title/>` in a document of about 2 KB or less and swallows most of the body in a larger one (measured
  // on jsoup 1.22.2: 2,040 chars lose half the paragraphs; the real book *Juliet Takes a Breath* keeps 184 of 369,000 characters
  // of body text). The body below is therefore far past that size.
  @Test fun `after normalising, jsoup finds the body text a self-closing title used to swallow`() {
    val body = (1..200).joinToString("") { "<p>Paragraph $it of the chapter, long enough to count.</p>" }
    val html = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title/></head><body>$body</body></html>"""
    assertTrue("jsoup's HTML mode loses paragraphs behind <title/>", Jsoup.parse(html).body().select("p").size < 200)
    assertEquals(200, Jsoup.parse(normalizeHtml(html)).body().select("p").size)
  }
}
