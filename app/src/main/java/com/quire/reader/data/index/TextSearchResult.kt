package com.quire.reader.data.index

import com.quire.reader.data.Book

/** The reading-status chips that narrow a library text search, as the library's own status filter does. */
enum class TextStatusFilter { Reading, Unread, Finished, Recent }

/**
 * The library filters that apply to a text search: one of author, series or tag (the library scope) or a reading
 * status. The metadata substring search is deliberately not here; text search ignores it.
 */
data class TextSearchFilters(
  val author: String? = null,
  val series: String? = null,
  val tag: String? = null,
  val status: TextStatusFilter? = null,
) {
  companion object { val None = TextSearchFilters() }
}

/**
 * Where a search result opens the reader. [indexedMtime] and [indexedSizeBytes] are the file signature the text was
 * indexed from: a target is only valid while the book still has it ([isCurrentFor]).
 * [locatorJson] is the full Readium locator, including `text` (before, highlight, after) cut from the indexed text;
 * [highlight] is the same highlight on its own and [progression] the nearest known position in the whole book (0..1),
 * used when the passage itself cannot be found.
 */
data class IndexTarget(
  val bookId: Long,
  val indexedMtime: Long,
  val indexedSizeBytes: Long,
  val locatorJson: String,
  val highlight: String,
  val progression: Double,
) {
  fun isCurrentFor(mtime: Long, sizeBytes: Long): Boolean = indexedMtime == mtime && indexedSizeBytes == sizeBytes
}

/** One matching passage: [spans] is the excerpt to show, [seq] the chunk's place in the book, [chapter] where it is. */
data class Snippet(val seq: Int, val chapter: String, val spans: List<ExcerptSpan>, val target: IndexTarget)

/**
 * One book in the results. [passages] counts matching passages (not occurrences) and reads "N+" when the query hit
 * the examine cap; [truncated] says the book's index stops before its end, so absence of matches proves nothing.
 * [snippets] are the first few matches in reading order.
 */
data class BookTextResult(val book: Book, val passages: PassageCount, val truncated: Boolean, val snippets: List<Snippet>)

/**
 * A library text search: the best [books] and how many books matched in all.
 * - [capped]: the query was so common that only the first matching passages were examined, so counts are lower bounds
 *   and the ranking is approximate.
 * - [incomplete]: books that match may be missing altogether, because a broad filter on a common word stopped the scan
 *   early. An empty result with this set is not "no match" and must not be shown as one.
 * - [prefixDowngraded]: the word the user was still typing is so common as a prefix that it was matched exactly instead
 *   ("showing exact matches, keep typing for prefix matches"); null when the query ran as typed.
 */
data class TextSearchResult(
  val books: List<BookTextResult>,
  val matchingBooks: Int,
  val capped: Boolean,
  val incomplete: Boolean = false,
  val prefixDowngraded: String? = null,
) {
  /** Books that matched but are not in [books]. */
  val moreBooks: Int get() = matchingBooks - books.size
}

/** One page of a single book's matches. [nextAfterSeq] is the cursor for the next page, null on the last. */
data class BookTextPage(val snippets: List<Snippet>, val nextAfterSeq: Int?, val truncated: Boolean)
