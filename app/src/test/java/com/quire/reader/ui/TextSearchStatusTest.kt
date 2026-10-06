package com.quire.reader.ui

import com.quire.reader.data.index.BookTextResult
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexGap
import com.quire.reader.data.index.PassageCount
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextSearchResult
import com.quire.reader.data.index.TextStatusFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextSearchStatusTest {
  private fun result(vararg ids: Long) = TextSearchResult(ids.map { BookTextResult(testBook(it, "Book $it"), PassageCount(1, false), IndexGap.None, emptyList()) }, ids.size, capped = false)

  private fun input(q: String, filters: TextSearchFilters = TextSearchFilters.None) = TextSearchInput(q, filters)

  private fun matchOf(q: FtsQuery.Result.Query): String = q.match.orEmpty()

  @Test fun `blank input is idle and one-character or punctuation-only input is too short, with no search`() = runTest {
    val searched = mutableListOf<String>()
    val inputs = MutableStateFlow(input(""))
    val seen = mutableListOf<TextSearchStatus>()
    val job = launch { textSearchStatus(inputs) { q, _, _ -> searched += matchOf(q); flowOf(result(1)) }.collect { seen += it } }
    runCurrent(); assertEquals(TextSearchStatus.Idle, seen.last())
    inputs.value = input("a"); runCurrent(); assertEquals(TextSearchStatus.TooShort, seen.last())
    inputs.value = input("?!…"); runCurrent(); assertEquals(TextSearchStatus.TooShort, seen.last())
    inputs.value = input("  "); runCurrent(); assertEquals(TextSearchStatus.Idle, seen.last())
    advanceUntilIdle()
    assertEquals(emptyList<String>(), searched)
    job.cancel()
  }

  @Test fun `an over-long query is reported at once without waiting or searching`() = runTest {
    val searched = mutableListOf<String>()
    val seen = mutableListOf<TextSearchStatus>()
    val job = launch { textSearchStatus(MutableStateFlow(input(List(65) { "w$it" }.joinToString(" ")))) { q, _, _ -> searched += matchOf(q); flowOf(result(1)) }.collect { seen += it } }
    runCurrent()
    assertEquals(listOf<TextSearchStatus>(TextSearchStatus.OverLimit), seen)
    advanceUntilIdle()
    assertEquals(emptyList<String>(), searched)
    job.cancel()
  }

  @Test fun `a query searches only after the debounce and shows searching until results arrive`() = runTest {
    val seen = mutableListOf<TextSearchStatus>()
    val job = launch { textSearchStatus(MutableStateFlow(input("pemberley"))) { _, _, _ -> flow { delay(100); emit(result(1, 2)) } }.collect { seen += it } }
    advanceTimeBy(249); runCurrent()
    assertEquals(emptyList<TextSearchStatus>(), seen)
    advanceTimeBy(1); runCurrent()
    assertEquals(listOf<TextSearchStatus>(TextSearchStatus.Searching), seen)
    advanceTimeBy(100); runCurrent()
    assertEquals(TextSearchStatus.Results(result(1, 2)), seen.last())
    job.cancel()
  }

  @Test fun `typing again within the debounce searches only for the last text`() = runTest {
    val searched = mutableListOf<String>()
    val inputs = MutableStateFlow(input("pem"))
    val job = launch { textSearchStatus(inputs) { q, _, _ -> searched += matchOf(q); flowOf(result(1)) }.collect { } }
    advanceTimeBy(200); inputs.value = input("pemb")
    advanceTimeBy(200); inputs.value = input("pembe")
    advanceUntilIdle()
    assertEquals(listOf("\"pembe\"*"), searched)
    job.cancel()
  }

  @Test fun `a slow search for older text can never overwrite the result for newer text`() = runTest {
    val inputs = MutableStateFlow(input("old query"))
    val seen = mutableListOf<TextSearchStatus>()
    val job = launch {
      textSearchStatus(inputs) { q, _, _ ->
        flow {
          if (matchOf(q).contains("old")) { delay(5_000); emit(result(1)) } else { delay(10); emit(result(2)) }
        }
      }.collect { seen += it }
    }
    advanceTimeBy(300); runCurrent() // the old search is under way
    inputs.value = input("new query")
    advanceUntilIdle()
    assertEquals(TextSearchStatus.Results(result(2)), seen.last())
    assertTrue(seen.none { it == TextSearchStatus.Results(result(1)) })
    job.cancel()
  }

  @Test fun `results stay live as the database changes and a search with no books is no match`() = runTest {
    val seen = mutableListOf<TextSearchStatus>()
    val job = launch { textSearchStatus(MutableStateFlow(input("pemberley"))) { _, _, _ -> flow { emit(result()); emit(result(7)) } }.collect { seen += it } }
    advanceUntilIdle()
    assertEquals(listOf(TextSearchStatus.Searching, TextSearchStatus.NoMatch(result()), TextSearchStatus.Results(result(7))), seen)
    job.cancel()
  }

  @Test fun `changing a filter searches again with the new filter`() = runTest {
    val filters = mutableListOf<TextSearchFilters>()
    val inputs = MutableStateFlow(input("pemberley"))
    val job = launch { textSearchStatus(inputs) { _, f, _ -> filters += f; flowOf(result(1)) }.collect { } }
    advanceUntilIdle()
    inputs.value = input("pemberley", TextSearchFilters(status = TextStatusFilter.Reading))
    advanceUntilIdle()
    assertEquals(listOf(TextSearchFilters.None, TextSearchFilters(status = TextStatusFilter.Reading)), filters)
    job.cancel()
  }

  @Test fun `text search input is blank unless the open search field is in text mode`() {
    val typed = UiState(textLibraryQuery = "pemberley", query = "austen")
    assertEquals("", textSearchInput(typed).query)
    assertEquals("", textSearchInput(typed.copy(searchOpen = true)).query)
    assertEquals("pemberley", textSearchInput(typed.copy(searchOpen = true, searchScope = SearchScope.Text)).query)
    assertEquals("", textSearchInput(typed.copy(searchOpen = false, searchScope = SearchScope.Text)).query)
  }

  @Test fun `an empty result that may be incomplete is reported as no match carrying that fact`() = runTest {
    val partial = TextSearchResult(emptyList(), 0, capped = true, incomplete = true)
    val seen = mutableListOf<TextSearchStatus>()
    val job = launch { textSearchStatus(MutableStateFlow(input("pemberley"))) { _, _, _ -> flowOf(partial) }.collect { seen += it } }
    advanceUntilIdle()
    assertEquals(TextSearchStatus.NoMatch(partial), seen.last())
    assertTrue((seen.last() as TextSearchStatus.NoMatch).result.incomplete)
    job.cancel()
  }
}
