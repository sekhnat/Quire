package com.quire.reader.data.index

import com.quire.reader.data.index.FtsQuery.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtsQueryTest {
  private fun match(input: String): String? = (FtsQuery.parse(input) as Result.Query).match

  private fun cjk(input: String): String? = (FtsQuery.parse(input) as Result.Query).cjk

  private fun words(n: Int) = List(n) { "w$it" }.joinToString(" ")

  @Test fun `words are ANDed and only the last one is a prefix`() {
    assertEquals("\"pember\"*", match("pember"))
    assertEquals("\"King\" \"Queen\"*", match("King Queen"))
  }

  @Test fun `a trailing space or punctuation finishes the last word`() {
    assertEquals("\"king\" \"queen\"", match("king queen "))
    assertEquals("\"queen\"", match("queen."))
  }

  @Test fun `a closed quote is a phrase with no prefix`() {
    assertEquals("\"large handsome\"", match("\"large handsome\""))
    assertEquals("\"stone\" \"large handsome\"", match("stone \"large handsome\""))
    assertEquals("\"large handsome\" \"stone\"*", match("\"large handsome\" stone"))
  }

  @Test fun `an unmatched opening quote is an unfinished phrase that matches what closing it would`() {
    assertEquals("\"large hand\"", match("\"large hand"))
    assertEquals(match("\"large hand\""), match("\"large hand"))
    assertEquals("\"stone\" \"large hand\"", match("stone \"large hand"))
  }

  @Test fun `operators and syntax characters are plain text`() {
    assertEquals("\"pemberley\" \"OR\" \"dracula\"*", match("pemberley OR dracula"))
    assertEquals("\"text\" \"pemberley\"*", match("text:pemberley"))
    assertEquals("\"pemberley\"*", match("-pemberley"))
    assertEquals("\"NEAR\" \"2\" \"foo\"*", match("NEAR/2 foo"))
    assertEquals("\"NOT\" \"a\" \"b\"*", match("NOT (a b"))
    assertEquals("\"pem\"", match("pem*"))
  }

  @Test fun `only tokenised quoted words ever reach the MATCH string`() {
    val nasty = listOf(
      "a\"b", "\"; DROP TABLE book; --", "NOT (a OR b)", "col:val -x \"unterminated", "^abc", "\\\"x\\\"", "x*y*z", "a \"\" b",
      "\"a b\"c\"", "AND OR NOT NEAR", "'single' quote", "tab\tand\nnewline words",
    )
    val oneTerm = Regex("\"[^\"*\\s]+( [^\"*\\s]+)*\"\\*?")
    for (input in nasty) {
      val r = FtsQuery.parse(input) as? Result.Query ?: continue
      val m = r.match!!
      assertTrue("$input -> $m", Regex("$oneTerm( $oneTerm)*").matches(m))
      assertTrue("$input -> $m", m.count { it == '*' } <= 1)
      if ('*' in m) assertTrue(m.endsWith("\"*"))
    }
  }

  @Test fun `Unicode words keep their letters and are counted by character not UTF-16 unit`() {
    assertEquals("\"Café\" \"naïve\"*", match("Café naïve"))
    assertEquals(Result.TooShort, FtsQuery.parse("𝒜")) // one character, two chars
    assertEquals(Result.Query("\"𝒜b\"*", 1, "𝒜b"), FtsQuery.parse("𝒜b"))
  }

  @Test fun `input without two characters or without any word is too short`() {
    for (input in listOf("", " ", "a", " a ", "ñ", "--", "...", "\"\"", "—…", "😀😀")) {
      assertEquals("'$input'", Result.TooShort, FtsQuery.parse(input))
    }
    assertEquals(Result.Query("\"a\"", 1), FtsQuery.parse("a."))
  }

  @Test fun `sixty four tokens are searched and sixty five are refused, never truncated`() {
    assertEquals(64, (FtsQuery.parse(words(64)) as Result.Query).tokenCount)
    assertEquals(Result.OverLimit, FtsQuery.parse(words(65)))
    assertEquals(Result.OverLimit, FtsQuery.parse("\"${words(65)}\""))
    assertEquals(Result.OverLimit, FtsQuery.parse("${words(40)} \"${words(25)}\""))
    assertEquals(64, (FtsQuery.parse("${words(40)} \"${words(24)}\"") as Result.Query).tokenCount)
  }

  @Test fun `hyphens and apostrophes split into counted tokens`() {
    assertEquals(Result.Query("\"don\" \"t\" \"e\" \"mail\"*", 4, "mail"), FtsQuery.parse("don't e-mail"))
  }

  @Test fun `only an unfinished final word is a prefix and it can be asked for as the exact word`() {
    val typing = FtsQuery.parse("king queen") as Result.Query
    assertEquals("queen", typing.prefix)
    assertEquals("\"king\" \"queen\"", typing.withoutPrefix().match)
    assertEquals(null, typing.withoutPrefix().prefix)
    for (finished in listOf("king queen ", "king \"queen\"", "king \"queen")) {
      val q = FtsQuery.parse(finished) as Result.Query
      assertEquals(null, q.prefix)
      assertEquals(q, q.withoutPrefix())
    }
  }

  @Test fun `CJK runs go to the bigram index as substrings, a single character as a prefix`() {
    val q = FtsQuery.parse("日本語") as Result.Query
    assertEquals(null, q.match)
    assertEquals("\"日本 本語\"", q.cjk)
    assertEquals(null, q.prefix)
    assertEquals("\"猫\"*", cjk("猫")) // one CJK character is enough to search
    assertEquals("\"東京\" \"京都\"", cjk("東京 京都"))
    assertEquals("\"東京\" \"京都\"", cjk("東京、京都"))
  }

  @Test fun `mixed queries search Latin words in the main index and CJK runs in the bigram index`() {
    val q = FtsQuery.parse("hotel 東京 station") as Result.Query
    assertEquals("\"hotel\" \"station\"*", q.match)
    assertEquals("\"東京\"", q.cjk)
    assertEquals("\"日本\"", cjk("abc日本")) // a token mixing scripts is searched by its CJK run
    assertEquals(null, match("abc日本"))
    assertEquals("\"서울 울에\"", cjk("서울에")) // Korean with an attached particle
  }

  @Test fun `only one quoted phrase of two or more tokens is a phrase that looks across splits`() {
    fun phrase(input: String) = (FtsQuery.parse(input) as Result.Query).phrase
    assertTrue(phrase("\"of the\""))
    assertTrue(phrase("\"of the"))
    assertEquals(false, phrase("\"word\""))
    assertEquals(false, phrase("of the"))
    assertEquals(false, phrase("\"of the\" king"))
    assertEquals(false, phrase("\"of the\" \"king's\""))
    assertEquals(false, phrase("\"of the 東京\""))
  }
}
