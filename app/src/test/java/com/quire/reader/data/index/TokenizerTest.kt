package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenizerTest {
  private fun words(s: String) = Tokenizer.tokenize(s).map { s.substring(it.startChar, it.endChar) }

  @Test fun `apostrophes hyphens underscores and decimal points split tokens`() {
    assertEquals(listOf("don", "t", "e", "mail", "foo", "bar", "3", "14"), words("don't e-mail foo_bar 3.14"))
    assertEquals(listOf("can", "t"), words("can’t")) // curly apostrophe
    assertEquals(listOf("one", "two"), words("one—two")) // em dash
  }

  @Test fun `accented and decomposed letters stay inside their token`() {
    assertEquals(listOf("café", "naïve"), words("café, naïve."))
    assertEquals(listOf("café"), words("café")) // e + combining acute
  }

  @Test fun `a combining mark that starts a run is skipped`() {
    val text = "́abc"
    assertEquals(listOf("abc"), words(text))
    assertEquals(1, Tokenizer.tokenize(text).single().startChar)
  }

  @Test fun `CJK runs are one token and punctuation or emoji alone make none`() {
    assertEquals(listOf("日本語", "text"), words("日本語 text"))
    assertTrue(Tokenizer.tokenize("—…!? 😀").isEmpty())
  }

  @Test fun `offsets are given in chars and in UTF-8 bytes`() {
    // x, space, U+1D49C (two chars, four bytes), b, space, n with tilde (one char, two bytes)
    val text = "x 𝒜b ñ"
    val t = Tokenizer.tokenize(text)
    assertEquals(listOf(Token(0, 1, 0, 1), Token(2, 5, 2, 7), Token(6, 7, 8, 10)), t)
  }
}
