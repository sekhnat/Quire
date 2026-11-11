package com.quire.reader.ui

import com.quire.reader.data.BookStatus
import com.quire.reader.data.index.TextSearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordFilterTest {
  private val books = listOf(
    testBook(1, "Tiny", "Jane Austen", words = 4_999),
    testBook(2, "Five", "Jane Austen", words = 5_000),
    testBook(3, "Short", "Arthur Doyle", words = 9_999, status = BookStatus.Reading, progress = .3f),
    testBook(4, "Novelette", "Arthur Doyle", words = 10_000),
    testBook(5, "Novella", "Mary Shelley", words = 35_000),
    testBook(6, "Novel", "Mary Shelley", words = 80_000),
    testBook(7, "Epic", "Mary Shelley", words = 100_000),
    testBook(8, "Uncounted", "Mary Shelley", words = null),
    testBook(9, "Unreadable", "Mary Shelley", words = 0),
  )

  private fun ids(s: LibraryUiState) = visibleBooks(s.bookQuery, LibraryData(books, emptyList())).map { it.id }.toSet()

  @Test fun `no word filter keeps every book, counted or not`() {
    assertEquals(books.map { it.id }.toSet(), ids(LibraryUiState()))
  }

  @Test fun `each preset takes the books in its range and bounds belong to the range above`() {
    fun preset(p: WordPreset) = ids(LibraryUiState(words = p.range))
    assertEquals(setOf(1L), preset(WordPreset.Under5k))
    assertEquals(setOf(2L, 3L), preset(WordPreset.From5kTo10k))
    assertEquals(setOf(4L), preset(WordPreset.From10kTo20k))
    assertEquals(setOf(5L), preset(WordPreset.From20kTo50k))
    assertEquals(setOf(6L), preset(WordPreset.From50kTo100k))
    assertEquals(setOf(7L), preset(WordPreset.Over100k))
  }

  @Test fun `the presets cover every counted book exactly once`() {
    val counted = books.filter { (it.words ?: 0) > 0 }.map { it.id }
    val covered = WordPreset.entries.flatMap { p -> ids(LibraryUiState(words = p.range)) }
    assertEquals(counted.sorted(), covered.sorted())
  }

  @Test fun `books not counted yet or unreadable are in no range`() {
    for (range in listOf(WordRange(max = 5_000), WordRange(min = 1), WordRange(1, 1_000_000), WordRange())) {
      assertFalse(range.matches(null))
      assertFalse(range.matches(0))
    }
    assertFalse(8L in ids(LibraryUiState(words = WordRange(max = 1_000_000))))
    assertFalse(9L in ids(LibraryUiState(words = WordRange(max = 1_000_000))))
  }

  @Test fun `a custom range with only a minimum is min and up, with only a maximum is up to it`() {
    assertEquals(setOf(5L, 6L, 7L), ids(LibraryUiState(words = WordRange.custom("30k", ""))))
    assertEquals(setOf(1L, 2L, 3L), ids(LibraryUiState(words = WordRange.custom("", "10000"))))
    assertEquals(setOf(4L, 5L), ids(LibraryUiState(words = WordRange.custom("10k", "50k"))))
  }

  @Test fun `it combines with the status filter and with a scope`() {
    assertEquals(setOf(3L), ids(LibraryUiState(filter = LibFilter.Reading, words = WordPreset.From5kTo10k.range)))
    assertEquals(setOf(3L, 4L), ids(LibraryUiState(scope = Scope(ScopeKind.Author, "Arthur Doyle"), words = WordRange(min = 5_000))))
    assertEquals(setOf(2L), ids(LibraryUiState(filter = LibFilter.Unread, words = WordPreset.From5kTo10k.range)))
  }

  @Test fun `the search box still narrows within a word range`() {
    assertEquals(setOf(7L), ids(LibraryUiState(query = "epic", words = WordPreset.Over100k.range)))
    assertEquals(emptySet<Long>(), ids(LibraryUiState(query = "tiny", words = WordPreset.Over100k.range)))
  }

  @Test fun `bounds are read from typed text`() {
    assertEquals(12_000, parseWordBound("12k"))
    assertEquals(12_000, parseWordBound(" 12K "))
    assertEquals(12_000, parseWordBound("12,000"))
    assertEquals(12_000, parseWordBound("12000"))
    assertEquals(1_500_000, parseWordBound("1.5m"))
    assertEquals(2_500, parseWordBound("2.5k"))
    assertNull(parseWordBound(""))
    assertNull(parseWordBound("   "))
    assertNull(parseWordBound("abc"))
    assertNull(parseWordBound("k"))
    assertNull(parseWordBound("0"))
    assertNull(parseWordBound("-5"))
  }

  @Test fun `a custom range needs one bound and a minimum below the maximum`() {
    assertNull(WordRange.custom("", ""))
    assertNull(WordRange.custom("abc", ""))
    assertNull(WordRange.custom("50k", "10k"))
    assertNull(WordRange.custom("10k", "10k"))
    assertEquals(WordRange(12_000, null), WordRange.custom("12k", ""))
    assertEquals(WordRange(null, 40_000), WordRange.custom("", "40k"))
    assertEquals(WordRange(12_000, 40_000), WordRange.custom("12k", "40k"))
    // A typo in one side does not quietly turn into an open-ended range.
    assertNull(WordRange.custom("12k", "oops"))
    assertNull(WordRange.custom("oops", "40k"))
  }

  @Test fun `labels read naturally`() {
    assertEquals("Under 5K", WordPreset.Under5k.range.label)
    assertEquals("5K–10K", WordPreset.From5kTo10k.range.label)
    assertEquals("20K–50K", WordPreset.From20kTo50k.range.label)
    assertEquals("100K+", WordPreset.Over100k.range.label)
    assertEquals("12K+", WordRange(min = 12_000).label)
    assertEquals("Under 40K", WordRange(max = 40_000).label)
    assertEquals("12K–40K", WordRange(12_000, 40_000).label)
    assertTrue(WordPreset.entries.map { it.range.label }.toSet().size == WordPreset.entries.size)
  }

  @Test fun `the range travels to the text search with and without a scope`() {
    val range = WordRange(10_000, 20_000)
    assertEquals(TextSearchFilters(minWords = 10_000, maxWords = 20_000), textFilters(LibraryUiState(words = range)))
    assertEquals(
      TextSearchFilters(author = "Jane Austen", minWords = 10_000, maxWords = 20_000),
      textFilters(LibraryUiState(scope = Scope(ScopeKind.Author, "Jane Austen"), words = range)),
    )
    assertEquals(TextSearchFilters(maxWords = 5_000), textFilters(LibraryUiState(words = WordPreset.Under5k.range)))
    assertEquals(TextSearchFilters.None, textFilters(LibraryUiState(words = null)))
  }
}
