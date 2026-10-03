package com.quire.reader.ui

import com.quire.reader.data.index.BookTextPage
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.Snippet

/**
 * The reader's search overlay in library-search mode ("Show all in this book"): [query] is typed with the library's
 * "Inside books" semantics and matched in [bookId] only, instead of going through Readium's in-book search.
 */
data class BookSearchMode(val bookId: Long, val query: String)

enum class BookSearchStatus { Idle, TooShort, OverLimit, Searching, Results, NoMatch }

/**
 * What the overlay shows in library-search mode: the pages loaded so far, in reading order. [nextAfterSeq] is the
 * cursor of the page still to load (null when everything is loaded); [truncated] says the book's index stops before
 * its end, so matches past that point cannot be listed.
 */
data class BookSearchUi(
  val status: BookSearchStatus = BookSearchStatus.Idle,
  val snippets: List<Snippet> = emptyList(),
  val nextAfterSeq: Int? = null,
  val truncated: Boolean = false,
  val loadingMore: Boolean = false,
) {
  val hasMore: Boolean get() = nextAfterSeq != null
}

/** What typing [text] means before any database work: the status to show now, and the query to run, if there is one. */
data class BookSearchPlan(val ui: BookSearchUi, val query: FtsQuery.Result.Query?)

fun planBookSearch(text: String): BookSearchPlan = when (val parsed = FtsQuery.parse(text)) {
  FtsQuery.Result.TooShort -> BookSearchPlan(BookSearchUi(if (text.isBlank()) BookSearchStatus.Idle else BookSearchStatus.TooShort), null)
  FtsQuery.Result.OverLimit -> BookSearchPlan(BookSearchUi(BookSearchStatus.OverLimit), null)
  is FtsQuery.Result.Query -> BookSearchPlan(BookSearchUi(BookSearchStatus.Searching), parsed)
}

/** The state once a query's first page has arrived. */
fun bookSearchFirstPage(page: BookTextPage): BookSearchUi =
  if (page.snippets.isEmpty() && page.nextAfterSeq == null) BookSearchUi(BookSearchStatus.NoMatch, truncated = page.truncated)
  else BookSearchUi(BookSearchStatus.Results, page.snippets, page.nextAfterSeq, page.truncated)

/**
 * Adds the page that was requested after chunk [afterSeq]. A page answering any other cursor (a repeat, or one from
 * before the list changed) is ignored, and a match already listed is never listed twice, so pages stay stable.
 */
fun BookSearchUi.withPage(afterSeq: Int, page: BookTextPage): BookSearchUi {
  if (status != BookSearchStatus.Results || nextAfterSeq != afterSeq) return copy(loadingMore = false)
  val seen = snippets.mapTo(HashSet()) { it.seq }
  return copy(snippets = snippets + page.snippets.filter { it.seq !in seen }, nextAfterSeq = page.nextAfterSeq, truncated = page.truncated, loadingMore = false)
}
