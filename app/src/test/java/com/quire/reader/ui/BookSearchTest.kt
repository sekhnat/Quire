package com.quire.reader.ui

import com.quire.reader.data.index.BookTextPage
import com.quire.reader.data.index.ExcerptSpan
import com.quire.reader.data.index.IndexGap
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.data.index.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchTest {
  private fun snippet(seq: Int) = Snippet(seq, "Chapter", listOf(ExcerptSpan("match $seq", hit = true)), IndexTarget(1, 10, 20, "{}", "match $seq", 0.5))
  private fun page(vararg seqs: Int, next: Int? = null, gap: IndexGap = IndexGap.None) = BookTextPage(seqs.map(::snippet), next, gap)
  private fun seqs(ui: BookSearchUi) = ui.snippets.map { it.seq }

  @Test fun `blank input is idle and too little input is too short`() {
    assertEquals(BookSearchStatus.Idle, planBookSearch("  ").ui.status)
    assertEquals(BookSearchStatus.TooShort, planBookSearch("a").ui.status)
    assertNull(planBookSearch("a").query)
  }

  @Test fun `more than the token limit is over the limit and never truncated`() {
    val plan = planBookSearch((1..70).joinToString(" ") { "w$it" })
    assertEquals(BookSearchStatus.OverLimit, plan.ui.status)
    assertNull(plan.query)
  }

  @Test fun `a searchable query keeps library semantics and starts searching`() {
    val plan = planBookSearch("\"large handsome\" stone")
    assertEquals(BookSearchStatus.Searching, plan.ui.status)
    assertNotNull(plan.query)
    assertEquals("\"large handsome\" \"stone\"*", plan.query!!.match)
  }

  @Test fun `an empty last page is no match and keeps the book's gap`() {
    val ui = bookSearchFirstPage(page(gap = IndexGap.FirstPartOnly))
    assertEquals(BookSearchStatus.NoMatch, ui.status)
    assertEquals(IndexGap.FirstPartOnly, ui.gap)
  }

  @Test fun `a first page has results and a cursor for more`() {
    val ui = bookSearchFirstPage(page(1, 2, next = 2))
    assertEquals(BookSearchStatus.Results, ui.status)
    assertEquals(listOf(1, 2), seqs(ui))
    assertTrue(ui.hasMore)
  }

  @Test fun `a page that lists nothing but has more is not reported as no match`() {
    assertEquals(BookSearchStatus.Results, bookSearchFirstPage(page(next = 7)).status)
  }

  @Test fun `the next page is appended in order and ends the list on the last page`() {
    val ui = bookSearchFirstPage(page(1, 2, next = 2)).withPage(2, page(5, 9))
    assertEquals(listOf(1, 2, 5, 9), seqs(ui))
    assertFalse(ui.hasMore)
  }

  @Test fun `a repeated page is ignored`() {
    val first = bookSearchFirstPage(page(1, 2, next = 2))
    val once = first.withPage(2, page(5, 9, next = 9))
    assertEquals(once.snippets, once.withPage(2, page(5, 9, next = 9)).snippets)
    assertEquals(9, once.withPage(2, page(5, 9, next = 9)).nextAfterSeq)
  }

  @Test fun `a page for another cursor is ignored and loading ends`() {
    val ui = bookSearchFirstPage(page(1, 2, next = 2)).copy(loadingMore = true).withPage(40, page(41))
    assertEquals(listOf(1, 2), seqs(ui))
    assertEquals(2, ui.nextAfterSeq)
    assertFalse(ui.loadingMore)
  }

  @Test fun `a match already listed is not listed twice`() {
    val ui = bookSearchFirstPage(page(1, 2, next = 2)).withPage(2, page(2, 3))
    assertEquals(listOf(1, 2, 3), seqs(ui))
  }

  @Test fun `a page arriving after the list was replaced is dropped`() {
    val fresh = planBookSearch("garden").ui
    assertEquals(fresh.snippets, fresh.withPage(2, page(5)).snippets)
  }

  @Test fun `later pages keep reporting the book's gap`() {
    val ui = bookSearchFirstPage(page(1, next = 1)).withPage(1, page(2, gap = IndexGap.PartsUnreadable))
    assertEquals(IndexGap.PartsUnreadable, ui.gap)
  }
}
