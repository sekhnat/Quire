package com.quire.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fitting a passage onto the quote card (spec feature 10), against a simple measure: characters × size ≤ budget. */
class QuoteFitTest {
  private val sizes = listOf(34, 30, 26, 22)
  private fun budget(limit: Int) = { text: String, size: Int -> text.length * size <= limit }

  @Test
  fun `a short passage keeps the largest size`() {
    assertEquals(QuoteFit("Call me Ishmael.", 34, shortened = false), fitQuote("  Call me\n Ishmael. ", sizes, budget(10_000)))
  }

  @Test
  fun `a passage that exactly fits keeps its size`() {
    val text = "a".repeat(10)
    assertEquals(34, fitQuote(text, sizes, budget(340)).size)
    assertFalse(fitQuote(text, sizes, budget(340)).shortened)
  }

  @Test
  fun `a longer passage steps the size down`() {
    val fit = fitQuote("x".repeat(100), sizes, budget(2_700))
    assertEquals(26, fit.size)
    assertFalse(fit.shortened)
  }

  @Test
  fun `past the smallest size it is cut at a word boundary`() {
    val words = (1..60).joinToString(" ") { "word$it" }
    val fit = fitQuote(words, sizes, budget(22 * 50))
    assertTrue(fit.shortened)
    assertEquals(22, fit.size)
    assertTrue(fit.text.endsWith("…"))
    assertTrue(fit.text.length <= 50)
    assertTrue(words.startsWith(fit.text.removeSuffix("…")))
    assertFalse(fit.text.removeSuffix("…").endsWith(" "))
  }

  @Test
  fun `a cut never ends on a dangling comma or dash`() {
    val fit = fitQuote("one two, three four five six", sizes, budget(22 * 10))
    assertEquals("one two…", fit.text)
  }

  @Test
  fun `a single very long word, or unspaced CJK, is cut between characters`() {
    val long = "Pneumonoultramicroscopicsilicovolcanoconiosis".repeat(3)
    val fit = fitQuote(long, sizes, budget(22 * 20))
    assertTrue(fit.shortened)
    assertEquals(20, fit.text.length)
    val cjk = "我們都是這樣長大的".repeat(20)
    val cut = fitQuote(cjk, sizes, budget(22 * 30))
    assertTrue(cut.text.length <= 30 && cut.text.endsWith("…"))
  }
}
