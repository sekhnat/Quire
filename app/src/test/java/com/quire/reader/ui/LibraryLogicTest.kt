package com.quire.reader.ui

import com.quire.reader.data.BookStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {
  private val books = listOf(
    testBook(1, "Alpha", "Jane Austen", lastOpened = 500, status = BookStatus.Reading, progress = .4f, year = 1813, pages = 400, size = 900_000),
    testBook(2, "Beta", "Jane Austen", lastOpened = 900, status = BookStatus.Reading, progress = .1f, year = 1815, pages = 300, size = 100_000),
    testBook(3, "Gamma", "Arthur Doyle", series = "Holmes", seriesNo = 2.0, tags = listOf("Mystery"), isNew = true, year = 1890),
    testBook(4, "Delta", "Arthur Doyle", series = "Holmes", seriesNo = 1.0, tags = listOf("Mystery", "Classic"), status = BookStatus.Finished, progress = 1f, rating = 5),
    testBook(5, "Epsilon", "Mary Shelley", tags = listOf("Gothic")),
  )

  @Test fun `recently opened puts the latest book first and falls back to date added`() {
    val ids = visibleBooks(UiState(), books).map { it.id }
    assertEquals(listOf(2L, 1L), ids.take(2))        // opened books, most recent first
    assertEquals(listOf(5L, 4L, 3L), ids.drop(2))    // never opened: newest added first
  }

  @Test fun `ascending reverses the order`() {
    assertEquals(books.size.toLong(), visibleBooks(UiState(sortAscending = true), books).size.toLong())
    assertEquals(2L, visibleBooks(UiState(sortAscending = true), books).last().id)
  }

  @Test fun `each sort key orders by its own field, biggest first`() {
    assertEquals(3L, visibleBooks(UiState(sort = SortKey.Year), books).first().id)
    assertEquals(1L, visibleBooks(UiState(sort = SortKey.Size), books).first().id)
    assertEquals(1L, visibleBooks(UiState(sort = SortKey.Pages), books).first().id)
    assertEquals(5L, visibleBooks(UiState(sort = SortKey.Added), books).first().id)
  }

  @Test fun `a series scope lists books in reading order`() {
    val list = visibleBooks(UiState(scope = Scope(ScopeKind.Series, "Holmes")), books)
    assertEquals(listOf(4L, 3L), list.map { it.id })
  }

  @Test fun `status filters and the recent filter`() {
    assertEquals(setOf(1L, 2L), visibleBooks(UiState(filter = LibFilter.Reading), books).map { it.id }.toSet())
    assertEquals(setOf(4L), visibleBooks(UiState(filter = LibFilter.Finished), books).map { it.id }.toSet())
    assertEquals(setOf(3L), visibleBooks(UiState(filter = LibFilter.Recent), books).map { it.id }.toSet())
    assertEquals(setOf(3L, 5L), visibleBooks(UiState(filter = LibFilter.Unread), books).map { it.id }.toSet())
  }

  @Test fun `a scope overrides the status filter`() {
    val list = visibleBooks(UiState(filter = LibFilter.Finished, scope = Scope(ScopeKind.Author, "Jane Austen")), books)
    assertEquals(2, list.size)
  }

  @Test fun `search matches title author series and tags`() {
    assertEquals(setOf(3L, 4L), visibleBooks(UiState(query = "holmes"), books).map { it.id }.toSet())
    assertEquals(setOf(5L), visibleBooks(UiState(query = "GOTHIC"), books).map { it.id }.toSet())
    assertEquals(setOf(1L, 2L), visibleBooks(UiState(query = "austen"), books).map { it.id }.toSet())
  }

  @Test fun `card caption follows the active sort`() {
    val b = books[0]
    assertEquals("40%", cardStatus(b, SortKey.Opened))
    assertEquals("400 pages", cardStatus(b, SortKey.Pages))
    assertEquals("1813", cardStatus(b, SortKey.Year))
    assertEquals("No date", cardStatus(books[3], SortKey.Year))
  }

  @Test fun `library data groups authors by surname`() {
    val lib = LibraryData(books, emptyList())
    assertEquals(listOf('A', 'D', 'S'), lib.authorGroups.map { it.first })
    assertEquals("Jane Austen", lib.authorGroups.first().second.single().name)
  }

  @Test fun `series report missing numbers and finished count`() {
    val gappy = books + testBook(6, "Zeta", "Arthur Doyle", series = "Holmes", seriesNo = 5.0)
    val series = LibraryData(gappy, emptyList()).series.single()
    assertEquals(listOf(1.0, 2.0, 5.0), series.books.map { it.seriesNo })
    assertEquals(listOf(3, 4), series.missing)
    assertEquals(5, series.total)
    assertEquals(1, series.finished)
  }

  @Test fun `counts, tags and ratings are derived from the books`() {
    val lib = LibraryData(books, emptyList())
    assertEquals(5, lib.counts.getValue(LibFilter.All))
    assertEquals(2, lib.counts.getValue(LibFilter.Reading))
    assertEquals(2, lib.tags.first { it.first == "Mystery" }.second)
    assertEquals("Mystery", lib.tags.first().first)
    assertEquals(1, lib.ratedFive)
  }

  @Test fun `resume points at the most recently opened book in progress`() {
    assertEquals(2L, LibraryData(books, emptyList()).resume?.id)
    assertTrue(LibraryData(books.filter { it.status != BookStatus.Reading }, emptyList()).resume == null)
  }
}
