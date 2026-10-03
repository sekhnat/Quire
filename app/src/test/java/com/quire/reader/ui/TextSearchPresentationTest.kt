package com.quire.reader.ui

import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.index.BookTextResult
import com.quire.reader.data.index.ExcerptSpan
import com.quire.reader.data.index.IndexActivity
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.data.index.PassageCount
import com.quire.reader.data.index.Snippet
import com.quire.reader.data.index.TextSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchPresentationTest {
  private fun coverage(eligible: Int = 10, searchable: Int = 10, failed: Int = 0, skipped: Int = 0, truncated: Int = 0) =
    IndexCoverage(eligible, searchable, failed, skipped, truncated)

  private fun snippet(seq: Int) = Snippet(seq, "Chapter", listOf(ExcerptSpan("match", hit = true)), IndexTarget(1, 10, 20, "{}", "match", 0.5))
  private fun book(id: Long, snippets: Int = 1) = BookTextResult(testBook(id, "Book $id"), PassageCount(snippets, false), false, (1..snippets).map(::snippet))
  private fun result(books: Int = 1, matching: Int = books, capped: Boolean = false, incomplete: Boolean = false, downgraded: String? = null) =
    TextSearchResult((1..books).map { book(it.toLong()) }, matching, capped, incomplete, downgraded)

  // ── coverage ──

  @Test fun `finished indexing with failed skipped and truncated books does not claim everything is searchable`() {
    val line = coverageLine(coverage(eligible = 14, searchable = 11, failed = 1, skipped = 2, truncated = 3))
    assertEquals("11 of 14 books searchable · 1 failed · 2 skipped · 3 partly indexed", line)
  }

  @Test fun `a fully searchable library says so without issues`() {
    assertEquals("10 of 10 books searchable", coverageLine(coverage()))
    assertNull(coverageIssues(coverage()))
  }

  @Test fun `a single book is not pluralised`() {
    assertEquals("1 of 1 book searchable", coverageLine(coverage(eligible = 1, searchable = 1)))
  }

  @Test fun `coverage is partial while books are missing or only partly indexed`() {
    assertFalse(isPartial(coverage()))
    assertTrue(isPartial(coverage(searchable = 9)))
    assertTrue(isPartial(coverage(truncated = 1)))
    assertFalse(isPartial(null))
  }

  // ── index state ──

  @Test fun `each indexer state has its own message`() {
    val c = coverage(searchable = 4)
    val texts = listOf(
      indexStatusText(c, IndexActivity.Running(4, 10)).headline,
      indexStatusText(c, IndexActivity.PausedForReader).headline,
      indexStatusText(c, IndexActivity.WaitingForCharging).headline,
      indexStatusText(c, IndexActivity.Disabled).headline,
      indexStatusText(c, IndexActivity.PermissionMissing).headline,
      indexStatusText(coverage(searchable = 0), IndexActivity.Idle).headline,
    )
    assertEquals(texts.size, texts.map { assertNotNull(it); it }.toSet().size)
  }

  @Test fun `running shows its progress and only permission missing asks for access`() {
    val running = indexStatusText(coverage(searchable = 4), IndexActivity.Running(4, 10))
    assertEquals(0.4f, running.progress!!, 0.0001f)
    assertEquals("Indexing books · 4 of 10", running.headline)
    assertFalse(running.needsAccess)
    assertTrue(indexStatusText(coverage(), IndexActivity.PermissionMissing).needsAccess)
    assertNull(indexStatusText(coverage(), IndexActivity.Idle).progress)
  }

  @Test fun `turned off with a retained index explains that new and changed books are not added`() {
    val kept = indexStatusText(coverage(searchable = 6), IndexActivity.Disabled).headline!!
    assertTrue(kept.contains("still searchable"))
    assertTrue(kept.contains("not being added"))
    val none = indexStatusText(coverage(searchable = 0), IndexActivity.Disabled).headline!!
    assertFalse(none.contains("still searchable"))
  }

  @Test fun `the Settings status does not send the user to Settings, the library status does`() {
    val empty = coverage(searchable = 0)
    assertTrue(indexStatusText(empty, IndexActivity.Disabled).headline!!.endsWith("Turn it on in Settings."))
    val inSettings = indexStatusText(empty, IndexActivity.Disabled, inSettings = true).headline!!
    assertFalse(inSettings.contains("Settings"))
    assertTrue(inSettings.startsWith("Indexing is turned off"))
  }

  @Test fun `an idle healthy index needs no headline but an empty or failed one does`() {
    assertNull(indexStatusText(coverage(), IndexActivity.Idle).headline)
    assertEquals("Add books to search inside them.", indexStatusText(coverage(eligible = 0, searchable = 0), IndexActivity.Idle).headline)
    assertEquals("None of your books could be indexed.", indexStatusText(coverage(eligible = 3, searchable = 0, failed = 2, skipped = 1), IndexActivity.Idle).headline)
    assertEquals("No books are searchable yet.", indexStatusText(coverage(eligible = 3, searchable = 0), IndexActivity.Idle).headline)
  }

  @Test fun `no coverage yet gives no coverage line`() {
    assertNull(indexStatusText(null, IndexActivity.Idle).coverage)
    assertNull(indexStatusText(coverage(eligible = 0, searchable = 0), IndexActivity.Idle).coverage)
  }

  // ── status copy ──

  @Test fun `every search state without a list has distinct copy`() {
    val c = coverage()
    val copies = listOf(
      textSearchStatusCopy(TextSearchStatus.Idle, c),
      textSearchStatusCopy(TextSearchStatus.TooShort, c),
      textSearchStatusCopy(TextSearchStatus.OverLimit, c),
      textSearchStatusCopy(TextSearchStatus.Searching, c),
      textSearchStatusCopy(TextSearchStatus.NoMatch(result(books = 0)), c),
      textSearchStatusCopy(TextSearchStatus.NoMatch(result(books = 0, incomplete = true)), c),
    )
    assertTrue(copies.all { it != null })
    assertEquals(copies.size, copies.map { it!!.title }.toSet().size)
  }

  @Test fun `an over-limit query says it is never truncated and names the limit`() {
    val detail = textSearchStatusCopy(TextSearchStatus.OverLimit, coverage())!!.detail!!
    assertTrue(detail.contains("64"))
    assertTrue(detail.contains("nothing is cut off"))
  }

  @Test fun `an empty incomplete result is never presented as no matches`() {
    val copy = textSearchStatusCopy(TextSearchStatus.NoMatch(result(books = 0, incomplete = true)), coverage())!!
    assertFalse(copy.title.contains("No matches"))
    assertTrue(copy.detail!!.contains("may be missing"))
  }

  @Test fun `no match over a partly indexed library warns that the match may be in an unsearchable book`() {
    val partial = textSearchStatusCopy(TextSearchStatus.NoMatch(result(books = 0)), coverage(searchable = 7))!!
    val full = textSearchStatusCopy(TextSearchStatus.NoMatch(result(books = 0)), coverage())!!
    assertEquals("No matches", partial.title)
    assertTrue(partial.detail!!.contains("Not every book is searchable yet"))
    assertEquals("No passage in your books contains that.", full.detail)
  }

  @Test fun `no match when every book is searchable but one is only partly indexed blames the missing part not missing books`() {
    val detail = textSearchStatusCopy(TextSearchStatus.NoMatch(result(books = 0)), coverage(truncated = 1))!!.detail!!
    assertTrue(detail.contains("only partly searchable"))
    assertFalse(detail.contains("Not every book"))
  }

  @Test fun `no match before any book is searchable says there is nothing to search yet`() {
    val copy = textSearchStatusCopy(TextSearchStatus.NoMatch(result(books = 0)), coverage(eligible = 5, searchable = 0))!!
    assertEquals("Nothing to search yet", copy.title)
  }

  @Test fun `idle invites a search only when something is searchable and results have no status copy`() {
    assertNotNull(textSearchStatusCopy(TextSearchStatus.Idle, coverage()))
    assertNull(textSearchStatusCopy(TextSearchStatus.Idle, coverage(searchable = 0)))
    assertNull(textSearchStatusCopy(TextSearchStatus.Idle, null))
    assertNull(textSearchStatusCopy(TextSearchStatus.Results(result()), coverage()))
  }

  // ── notices ──

  @Test fun `a plain result has no notices`() {
    assertTrue(resultNotices(result()).isEmpty())
  }

  @Test fun `more matching books than listed is stated with the totals`() {
    val notices = resultNotices(result(books = 40, matching = 63))
    assertEquals(listOf("Showing the top 40 of 63 matching books. Narrow the search to see the rest."), notices)
  }

  @Test fun `a capped result says counts are lower bounds and the total is a minimum`() {
    val notices = resultNotices(result(books = 40, matching = 52, capped = true))
    assertEquals(2, notices.size)
    assertTrue(notices[0].contains("lower bounds"))
    assertTrue(notices[1].contains("at least 52"))
  }

  @Test fun `an incomplete result with books says matches may be missing`() {
    assertTrue(resultNotices(result(incomplete = true)).single().contains("some matches may be missing"))
  }

  @Test fun `a downgraded prefix is explained even when nothing matched`() {
    val expected = "Showing exact matches for \"the\" — keep typing for prefix matches"
    assertEquals(listOf(expected), resultNotices(result(books = 0, downgraded = "the")))
    assertEquals(listOf(expected), resultNotices(result(downgraded = "the")))
  }

  // ── cards ──

  @Test fun `passage counts read as matching passages and keep the plus when capped`() {
    assertEquals("1 passage", passageLabel(PassageCount(1, false)))
    assertEquals("12 passages", passageLabel(PassageCount(12, false)))
    assertEquals("5000+ passages", passageLabel(PassageCount(5000, true)))
    assertEquals("1+ passages", passageLabel(PassageCount(1, true)))
  }

  @Test fun `a card shows three excerpts and expands to the five the search returns`() {
    val five = (1..5).map(::snippet)
    assertEquals(listOf(1, 2, 3), shownSnippets(five, expanded = false).map { it.seq })
    assertEquals(listOf(1, 2, 3, 4, 5), shownSnippets(five, expanded = true).map { it.seq })
    assertEquals(2, hiddenSnippets(five, expanded = false))
    assertEquals(0, hiddenSnippets(five, expanded = true))
  }

  @Test fun `a book with three or fewer excerpts has nothing to expand`() {
    assertEquals(0, hiddenSnippets((1..3).map(::snippet), expanded = false))
    assertEquals(0, hiddenSnippets(listOf(snippet(1)), expanded = false))
    assertEquals(1, shownSnippets(listOf(snippet(1)), expanded = false).size)
  }

  @Test fun `highlight ranges line up with the joined excerpt text`() {
    val h = highlightedText(listOf(ExcerptSpan("…saw ", hit = false), ExcerptSpan("Pemberley", hit = true), ExcerptSpan(" at last, ", hit = false), ExcerptSpan("the park", hit = true)))
    assertEquals("…saw Pemberley at last, the park", h.text)
    assertEquals(listOf("Pemberley", "the park"), h.hits.map { h.text.substring(it.first, it.last + 1) })
  }

  @Test fun `markup in an excerpt is kept as plain text and empty spans are ignored`() {
    val h = highlightedText(listOf(ExcerptSpan("", hit = true), ExcerptSpan("<b>", hit = false), ExcerptSpan("&amp;", hit = true)))
    assertEquals("<b>&amp;", h.text)
    assertEquals(listOf(3..7), h.hits)
  }

  // ── sizes ──

  @Test fun `byte counts are shown in the unit that reads best`() {
    assertEquals("512 B", formatBytes(512))
    assertEquals("48 KB", formatBytes(48 * 1024L + 100))
    assertEquals("13.4 MB", formatBytes((13.4 * 1048576).toLong()))
    assertEquals("1.25 GB", formatBytes((1.25 * 1073741824).toLong()))
  }
}
