package com.quire.reader.ui

import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexActivity
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextSearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Whether the library search field looks at book metadata or at the text inside books. */
enum class SearchScope(val label: String) { Metadata("Titles & authors"), Text("Inside books") }

/** What the library's text search has to show. */
sealed interface TextSearchStatus {
  /** Nothing typed, or the field is not in text mode. */
  data object Idle : TextSearchStatus
  /** Typed, but fewer than two characters or no word characters. */
  data object TooShort : TextSearchStatus
  data object OverLimit : TextSearchStatus
  data object Searching : TextSearchStatus
  data class Results(val result: TextSearchResult) : TextSearchStatus
  /** Nothing matched. Check [TextSearchResult.incomplete]: the search may have stopped early rather than found nothing. */
  data class NoMatch(val result: TextSearchResult) : TextSearchStatus
}

/** The text search status together with how much of the library is searchable and what the indexer is doing. */
data class LibraryTextSearch(
  val status: TextSearchStatus = TextSearchStatus.Idle,
  val coverage: IndexCoverage? = null,
  val activity: IndexActivity = IndexActivity.Idle,
)

/** What a library text search runs on: the typed text (blank when not in text mode), the active library filters and the order. */
data class TextSearchInput(val query: String, val filters: TextSearchFilters, val order: SearchOrder = SearchOrder.Relevance)

/** The text search input for a UI state; blank unless the open search field is in text mode. */
fun textSearchInput(s: UiState): TextSearchInput =
  if (s.searchOpen && s.searchScope == SearchScope.Text) TextSearchInput(s.textLibraryQuery, textFilters(s), s.textSearchOrder) else TextSearchInput("", TextSearchFilters.None)

/**
 * Turns changing inputs into the status to show. A new input cancels the previous one's search outright, so an older
 * result can never be published after a newer input. A query waits [debounceMs] for further typing before it searches
 * (the limits are reported at once), and a search keeps publishing as the database changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun textSearchStatus(
  inputs: Flow<TextSearchInput>,
  debounceMs: Long = 250,
  search: (FtsQuery.Result.Query, TextSearchFilters, SearchOrder) -> Flow<TextSearchResult>,
): Flow<TextSearchStatus> = inputs.distinctUntilChanged().flatMapLatest { input ->
  if (input.query.isBlank()) return@flatMapLatest flowOf(TextSearchStatus.Idle)
  when (val parsed = FtsQuery.parse(input.query)) {
    FtsQuery.Result.TooShort -> flowOf(TextSearchStatus.TooShort)
    FtsQuery.Result.OverLimit -> flowOf(TextSearchStatus.OverLimit)
    is FtsQuery.Result.Query -> flow {
      delay(debounceMs) // wait for the user to pause typing
      emit(TextSearchStatus.Searching)
      emitAll(search(parsed, input.filters, input.order).map { if (it.books.isEmpty()) TextSearchStatus.NoMatch(it) else TextSearchStatus.Results(it) })
    }
  }
}
