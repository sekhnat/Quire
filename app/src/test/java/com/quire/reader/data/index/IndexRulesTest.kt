package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexRulesTest {
  private val signature = IndexSignature(mtime = 1_700_000_000_000, sizeBytes = 2_500_000)

  @Test fun `books rank by passages, then most recently opened, then id`() {
    val books = listOf(
      BookRank(bookId = 9, passages = 3, lastOpenedAt = 0),
      BookRank(bookId = 4, passages = 7, lastOpenedAt = 100),
      BookRank(bookId = 2, passages = 7, lastOpenedAt = 500),
      BookRank(bookId = 6, passages = 3, lastOpenedAt = 0),
      BookRank(bookId = 5, passages = 3, lastOpenedAt = 900),
    )
    assertEquals(listOf(2L, 4L, 5L, 6L, 9L), books.sortedWith(rankBooks).map { it.bookId })
  }

  @Test fun `ranking does not depend on the order results arrive in`() {
    val books = List(20) { BookRank(bookId = it.toLong(), passages = it % 3, lastOpenedAt = (it % 2) * 10L) }
    val expected = books.sortedWith(rankBooks)
    repeat(5) { seed -> assertEquals(expected, books.shuffled(java.util.Random(seed.toLong())).sortedWith(rankBooks)) }
  }

  @Test fun `a book with no index state needs indexing`() {
    assertTrue(needsIndexing(signature.mtime, signature.sizeBytes, null))
  }

  @Test fun `an unchanged signature is settled and a changed mtime or size needs indexing again`() {
    assertFalse(needsIndexing(signature.mtime, signature.sizeBytes, signature))
    assertTrue(needsIndexing(signature.mtime + 1, signature.sizeBytes, signature))
    assertTrue(needsIndexing(signature.mtime, signature.sizeBytes + 1, signature))
  }

  @Test fun `an index carries forward only when size and source fingerprint both match`() {
    assertTrue(canCarryIndex(signature.sizeBytes, "fp", signature, "fp"))
    assertFalse("different content", canCarryIndex(signature.sizeBytes, "fp2", signature, "fp"))
    assertFalse("different size", canCarryIndex(signature.sizeBytes + 1, "fp", signature, "fp"))
    assertFalse("no state", canCarryIndex(signature.sizeBytes, "fp", null, "fp"))
    assertFalse("book fingerprint unknown", canCarryIndex(signature.sizeBytes, null, signature, null))
    assertFalse("source fingerprint unknown", canCarryIndex(signature.sizeBytes, "fp", signature, null))
  }

  @Test fun `counts below the cap are exact and counts from a capped query read as N plus`() {
    val exact = PassageCount.of(12, examined = MAX_COUNTED_PASSAGES - 1)
    assertFalse(exact.isCapped)
    assertEquals("12", exact.label)

    val capped = PassageCount.of(300, examined = MAX_COUNTED_PASSAGES)
    assertTrue(capped.isCapped)
    assertEquals("300+", capped.label)
  }

  @Test fun `typed words are folded the way the index stores terms`() {
    assertEquals("zurich", foldedTerm("ZÜRICH"))
    assertEquals("cafe", foldedTerm("Café"))
    assertEquals("cafe", foldedTerm("cafe\u0301")) // decomposed accent
    assertEquals("日本", foldedTerm("日本"))
    assertEquals("싱숑", foldedTerm("싱숑")) // Hangul syllables stay whole, as SQLite keeps them
    assertEquals("싱숑".length, foldedTerm("싱숑").length)
  }

  @Test fun `the term range for a prefix covers every term that starts with it and nothing else`() {
    val end = prefixRangeEnd("pem")
    for (inside in listOf("pem", "pemberley", "pemz", "pem日本", "pem\uD835\uDC9C")) assertTrue(inside, inside < end && inside >= "pem")
    for (outside in listOf("pel", "pen", "pe", "pex")) assertTrue(outside, outside >= end || outside < "pem")
  }
}
