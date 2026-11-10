package com.quire.reader.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inputs are shaped after real Calibre libraries (the text as it is after XML parsing). */
class MetadataTextTest {
  private fun d(html: String?) = MetadataText.description(html)

  @Test
  fun `plain fields decode entities and collapse whitespace`() {
    assertEquals("Silks & Scourges", MetadataText.plain("Silks &amp; Scourges"))
    assertEquals("Toil & Trouble", MetadataText.plain("Toil & Trouble"))
    assertEquals("Café au lait", MetadataText.plain("Caf&eacute; au\n  lait"))
    assertEquals("AT&T", MetadataText.plain("AT&T"))
    assertNull(MetadataText.plain(" &nbsp; "))
    assertNull(MetadataText.plain(null))
  }

  @Test
  fun `description decodes every entity, Windows-1252 quotes included`() {
    assertEquals(
      "“Wildly original.” —Charles Stross on Gideon’s “bones”… café – end",
      d("<p>&ldquo;Wildly original.&rdquo; &#8212;Charles Stross on <i>Gideon</i>&rsquo;s &#147;bones&#148;&hellip;&#160;caf&#233; &#8211; end</p>"),
    )
  }

  @Test
  fun `description escaped twice loses its tags`() {
    assertEquals(
      "Six months ago, Hallie Thatcher’s parents sold the inn.\nReally.",
      d("&lt;p&gt;Six months ago, Hallie Thatcher&rsquo;s parents sold the inn.&lt;/p&gt;&lt;p&gt;Really.&lt;/p&gt;"),
    )
    assertEquals("Sumptuous and addictive, An Education in Malice.", d("&lt;P&gt;&lt;B&gt;Sumptuous and addictive,&#160;&lt;I&gt;An Education in Malice&lt;/I&gt;.&lt;/B&gt;&lt;/P&gt;"))
  }

  @Test
  fun `headings, rules, quotes and opening blocks start a line`() {
    assertEquals(
      "The Ninth House will die.\nReview\n“Unlike anything.”\nA quote\nAfter the rule",
      d("<div>The Ninth House will die.<h3>Review</h3><p>“Unlike anything.”</p><blockquote><p>A quote</p></blockquote><hr />After the rule</div>"),
    )
    assertEquals("Line one\nLine two\n\nAfter a gap", d("Line one<br>Line two<br/><br />After a gap"))
  }

  @Test
  fun `newlines inside HTML are spaces, as a browser shows them`() {
    assertEquals(
      "The two hide in the sanctuary of Anna’s roof-top flat.\nLoki was prophesied.",
      d("<p>The two\n hide in the sanctuary of Anna’s roof-top flat.</p>\n            <blockquote class=\"userstuff\">\n  <p>Loki was prophesied.</p>\n</blockquote></html>"),
    )
  }

  @Test
  fun `text without tags keeps its own paragraphs`() {
    assertEquals(
      "Who says you can’t go home again?\n\nPhiladelphia broker Devyn Winters.",
      d("Who says you can&rsquo;t go home again?\n \n\n\nPhiladelphia broker Devyn Winters.  "),
    )
  }

  @Test
  fun `styles and scripts are dropped, empty descriptions are null`() {
    assertEquals("Text", d("<style>p { color: red }</style><font face=\".SF NS Text\"><span style=\"font-size: 14px;\">Text</span></font>"))
    assertNull(d("<p></p><p>&nbsp;</p>"))
    assertNull(d("   "))
    assertNull(d(null))
  }

  @Test
  fun `tidy cleans every field of parsed metadata`() {
    val raw = OpfMetadata(
      title = "You &amp; Me", titleSort = "you &amp; me", authors = listOf("A &amp; B", " "), authorSort = null,
      series = "Clem &amp; Wist", seriesIndex = 1.0, tags = listOf("Drama &amp; Romance", "Drama & Romance"), rating = 0,
      description = "<p>x &mdash; y</p>", year = null, language = "en", addedAtMillis = null,
    )
    val m = raw.tidy()
    assertEquals("You & Me", m.title)
    assertEquals("you & me", m.titleSort)
    assertEquals(listOf("A & B"), m.authors)
    assertEquals("Clem & Wist", m.series)
    assertEquals(listOf("Drama & Romance"), m.tags)
    assertEquals("x — y", m.description)
  }
}
