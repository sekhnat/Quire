package com.quire.reader.ui

import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextStatusFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class TextFiltersTest {
  @Test fun `no scope and no status filter leaves text search unfiltered`() {
    assertEquals(TextSearchFilters.None, textFilters(UiState()))
    assertEquals(TextSearchFilters.None, textFilters(UiState(query = "austen")))
  }

  @Test fun `each status filter maps to its text filter`() {
    assertEquals(TextStatusFilter.Reading, textFilters(UiState(filter = LibFilter.Reading)).status)
    assertEquals(TextStatusFilter.Unread, textFilters(UiState(filter = LibFilter.Unread)).status)
    assertEquals(TextStatusFilter.Finished, textFilters(UiState(filter = LibFilter.Finished)).status)
    assertEquals(TextStatusFilter.Recent, textFilters(UiState(filter = LibFilter.Recent)).status)
  }

  @Test fun `an author, series or tag scope maps to its filter and replaces the status filter`() {
    assertEquals(TextSearchFilters(author = "Jane Austen"), textFilters(UiState(filter = LibFilter.Finished, scope = Scope(ScopeKind.Author, "Jane Austen"))))
    assertEquals(TextSearchFilters(series = "Holmes"), textFilters(UiState(scope = Scope(ScopeKind.Series, "Holmes"))))
    assertEquals(TextSearchFilters(tag = "Gothic"), textFilters(UiState(scope = Scope(ScopeKind.Tag, "Gothic"))))
  }

  @Test fun `the metadata query never narrows a text search though it narrows the book list`() {
    val books = listOf(testBook(1, "Alpha", "Jane Austen"), testBook(2, "Beta", "Mary Shelley"))
    val state = UiState(query = "austen", scope = Scope(ScopeKind.Author, "Mary Shelley"))
    assertEquals(emptyList<Long>(), visibleBooks(state, books).map { it.id })
    assertEquals(TextSearchFilters(author = "Mary Shelley"), textFilters(state))
  }
}
