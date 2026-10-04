package com.quire.reader.navigator.epub.css

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Url

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalReadiumApi::class)
class ReadiumCssTest {
  private val css = ReadiumCss(
    layout = Layout(language = Language("en")),
    assetsBaseHref = Url("https://readium/assets/")!!,
  )

  /** A real-world shape: a self-closing `<title/>`, a body that already declares its language, and enough text to be large. */
  private fun largeSelfClosingTitle(): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?>""")
    append("""<html xmlns="http://www.w3.org/1999/xhtml"><head><title/></head><body xml:lang="EN-US">""")
    repeat(200) { append("<p>Paragraph $it of a book whose title element is written self-closing, which is legal XHTML.</p>") }
    append("</body></html>")
  }

  private fun String.openingTag(name: String): String = Regex("""<$name(\s[^>]*)?>""").find(this)!!.value

  @Test fun `a body that already declares its language is not given a second xml-lang behind a self-closing title`() {
    val html = largeSelfClosingTitle()
    assertTrue("the fixture must be large enough to trigger the misparse", html.length > 3 * 1024)

    val injected = css.injectHtml(html)

    val bodyTag = injected.openingTag("body")
    assertEquals(bodyTag, 1, Regex("""xml:lang=""").findAll(bodyTag).count())
    // The language is hoisted to <html> from the real body, once, so the page stays well-formed XML.
    val htmlTag = injected.openingTag("html")
    assertEquals(htmlTag, 1, Regex("""xml:lang=""").findAll(htmlTag).count())
    // The original text is what is served: the self-closing title is not rewritten.
    assertTrue(injected.contains("<title/>"))
  }
}
