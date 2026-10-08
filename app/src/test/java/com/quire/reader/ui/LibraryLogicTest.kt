package com.quire.reader.ui

import com.quire.reader.data.Book
import com.quire.reader.data.BookStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LibraryLogicTest {
  private val books = listOf(
    testBook(1, "Alpha", "Jane Austen", lastOpened = 500, status = BookStatus.Reading, progress = .4f, year = 1813, pages = 400, size = 900_000),
    testBook(2, "Beta", "Jane Austen", lastOpened = 900, status = BookStatus.Reading, progress = .1f, year = 1815, pages = 300, size = 100_000),
    testBook(3, "Gamma", "Arthur Doyle", series = "Holmes", seriesNo = 2.0, tags = listOf("Mystery"), isNew = true, year = 1890),
    testBook(4, "Delta", "Arthur Doyle", series = "Holmes", seriesNo = 1.0, tags = listOf("Mystery", "Classic"), status = BookStatus.Finished, progress = 1f, rating = 5),
    testBook(5, "Epsilon", "Mary Shelley", tags = listOf("Gothic")),
  )

  private fun visible(s: LibraryUiState, all: List<Book>) = visibleBooks(s.bookQuery, LibraryData(all, emptyList()))

  @Test fun `recently opened puts the latest book first and falls back to date added`() {
    val ids = visible(LibraryUiState(), books).map { it.id }
    assertEquals(listOf(2L, 1L), ids.take(2))        // opened books, most recent first
    assertEquals(listOf(5L, 4L, 3L), ids.drop(2))    // never opened: newest added first
  }

  @Test fun `ascending reverses the order`() {
    assertEquals(books.size.toLong(), visible(LibraryUiState(sortAscending = true), books).size.toLong())
    assertEquals(2L, visible(LibraryUiState(sortAscending = true), books).last().id)
  }

  @Test fun `each sort key orders by its own field, biggest first`() {
    assertEquals(3L, visible(LibraryUiState(sort = SortKey.Year), books).first().id)
    assertEquals(1L, visible(LibraryUiState(sort = SortKey.Size), books).first().id)
    assertEquals(1L, visible(LibraryUiState(sort = SortKey.Pages), books).first().id)
    assertEquals(5L, visible(LibraryUiState(sort = SortKey.Added), books).first().id)
  }

  @Test fun `a series scope lists books in reading order`() {
    val list = visible(LibraryUiState(scope = Scope(ScopeKind.Series, "Holmes")), books)
    assertEquals(listOf(4L, 3L), list.map { it.id })
  }

  @Test fun `in a series books without a number come last, most recently opened first`() {
    val holmes = books + listOf(
      testBook(7, "Eta", "Arthur Doyle", series = "Holmes", lastOpened = 100),
      testBook(8, "Theta", "Arthur Doyle", series = "Holmes", lastOpened = 300),
    )
    val list = visible(LibraryUiState(scope = Scope(ScopeKind.Series, "Holmes")), holmes)
    assertEquals(listOf(4L, 3L, 8L, 7L), list.map { it.id })
  }

  @Test fun `filtering before sorting gives the same list as sorting the whole library first`() {
    val authors = listOf("Jane Austen", "Arthur Doyle", "Mary Shelley")
    val series = listOf(null, "Holmes", "Emma")
    val tagSets = listOf(emptyList(), listOf("Mystery"), listOf("Mystery", "Classic"), listOf("Gothic"))
    val titles = listOf("Alpha", "Beta", "Gamma", "Alpha")
    val statuses = BookStatus.entries
    val random = Random(7)
    // Few distinct values per field, so every sort key has ties down to the title.
    val library = (1L..120L).map { id ->
      testBook(
        id, titles[random.nextInt(titles.size)], authors[random.nextInt(authors.size)],
        series = series[random.nextInt(series.size)], seriesNo = listOf(null, 1.0, 2.0, 2.0)[random.nextInt(4)],
        tags = tagSets[random.nextInt(tagSets.size)], status = statuses[random.nextInt(statuses.size)],
        lastOpened = listOf(0L, 0L, 500L, 900L)[random.nextInt(4)], addedAt = listOf(1000L, 2000L)[random.nextInt(2)],
        year = listOf(null, 1813, 1890)[random.nextInt(3)], pages = listOf(200, 300)[random.nextInt(2)],
        size = listOf(100_000L, 900_000L)[random.nextInt(2)], isNew = random.nextBoolean(),
      )
    }
    val lib = LibraryData(library, emptyList())
    val scopes = listOf(null) + authors.map { Scope(ScopeKind.Author, it) } + series.filterNotNull().map { Scope(ScopeKind.Series, it) } +
      listOf("Mystery", "Classic", "Gothic").map { Scope(ScopeKind.Tag, it) }
    var combinations = 0
    for (sort in SortKey.entries) for (ascending in listOf(false, true)) for (filter in LibFilter.entries) for (scope in scopes)
      for (query in listOf("", "  ", "a", "holmes", "MyStErY", "austen gamma", "e m")) {
        val s = LibraryUiState(sort = sort, sortAscending = ascending, filter = filter, scope = scope, query = query)
        assertEquals("$s", sortThenFilter(s, library).map { it.id }, visibleBooks(s.bookQuery, lib).map { it.id })
        combinations++
      }
    assertEquals(5 * 2 * 5 * 9 * 7, combinations)
  }

  /** The library list as it was worked out before filtering came first: the whole library sorted, then narrowed. */
  private fun sortThenFilter(s: LibraryUiState, all: List<Book>): List<Book> {
    val order = compareBy<Book> { s.sort.value(it) }.thenBy { it.addedAt }.thenBy { it.sortTitle }
    var list = all.sortedWith(if (s.sortAscending) order else order.reversed())
    s.scope?.let { sc ->
      list = list.filter {
        when (sc.kind) {
          ScopeKind.Author -> it.primaryAuthor == sc.value
          ScopeKind.Series -> it.series == sc.value
          ScopeKind.Tag -> sc.value in it.tags
        }
      }
      if (sc.kind == ScopeKind.Series && s.sort == SortKey.Opened) list = list.sortedBy { it.seriesNo ?: Double.MAX_VALUE }
    }
    if (s.scope == null) {
      list = list.filter {
        when (s.filter) {
          LibFilter.All -> true
          LibFilter.Recent -> it.isNew
          LibFilter.Reading -> it.status == BookStatus.Reading
          LibFilter.Unread -> it.status == BookStatus.Unread
          LibFilter.Finished -> it.status == BookStatus.Finished
        }
      }
    }
    if (s.query.isNotBlank()) {
      val q = s.query.lowercase()
      list = list.filter { (it.title + " " + it.author + " " + (it.series ?: "") + " " + it.tags.joinToString(" ")).lowercase().contains(q) }
    }
    return list
  }

  @Test fun `status filters and the recent filter`() {
    assertEquals(setOf(1L, 2L), visible(LibraryUiState(filter = LibFilter.Reading), books).map { it.id }.toSet())
    assertEquals(setOf(4L), visible(LibraryUiState(filter = LibFilter.Finished), books).map { it.id }.toSet())
    assertEquals(setOf(3L), visible(LibraryUiState(filter = LibFilter.Recent), books).map { it.id }.toSet())
    assertEquals(setOf(3L, 5L), visible(LibraryUiState(filter = LibFilter.Unread), books).map { it.id }.toSet())
  }

  @Test fun `a scope overrides the status filter`() {
    val list = visible(LibraryUiState(filter = LibFilter.Finished, scope = Scope(ScopeKind.Author, "Jane Austen")), books)
    assertEquals(2, list.size)
  }

  @Test fun `search matches title author series and tags`() {
    assertEquals(setOf(3L, 4L), visible(LibraryUiState(query = "holmes"), books).map { it.id }.toSet())
    assertEquals(setOf(5L), visible(LibraryUiState(query = "GOTHIC"), books).map { it.id }.toSet())
    assertEquals(setOf(1L, 2L), visible(LibraryUiState(query = "austen"), books).map { it.id }.toSet())
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
