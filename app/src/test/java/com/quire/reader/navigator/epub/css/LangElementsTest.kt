package com.quire.reader.navigator.epub.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LangElementsTest {
  private val chapter = (1..2_000).joinToString("") { "<p>Paragraph $it of a long chapter.</p>" }

  @Test fun `the html and body languages are read without parsing the chapter`() {
    val doc = langElements("""<html xmlns="http://www.w3.org/1999/xhtml" lang="en"><head><title>T</title></head><body xml:lang="fr" class="c">$chapter</body></html>""")
    assertEquals("en", doc.selectFirst("html")!!.attr("lang"))
    assertEquals("fr", doc.body().attr("xml:lang"))
    assertTrue("the chapter itself is not parsed", doc.body().childNodeSize() == 0)
  }

  @Test fun `a self-closing title does not hide the body's language`() {
    val doc = langElements("""<html xmlns="http://www.w3.org/1999/xhtml"><head><title/></head><body xml:lang="EN-US">$chapter</body></html>""")
    assertEquals("EN-US", doc.body().attr("xml:lang"))
  }

  @Test fun `a body tag mentioned in the head is not taken for the real one`() {
    val doc = langElements("""<html><head><script>var s = "<body lang='xx'>";</script></head><body lang="de"><p>Text</p></body></html>""")
    assertEquals("de", doc.body().attr("lang"))
  }

  @Test fun `self-closing and upper-case body tags are found`() {
    assertEquals("it", langElements("""<html><head></head><BODY LANG="it"/></html>""").body().attr("lang"))
  }

  @Test fun `a document without a body tag is parsed whole`() {
    val doc = langElements("""<html lang="es"><head><title>T</title></head><p>Text</p></html>""")
    assertEquals("es", doc.selectFirst("html")!!.attr("lang"))
    assertEquals("Text", doc.body().text())
  }
}
