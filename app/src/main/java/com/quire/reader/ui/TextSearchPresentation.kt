package com.quire.reader.ui

import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.index.ExcerptSpan
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexActivity
import com.quire.reader.data.index.IndexGap
import com.quire.reader.data.index.PassageCount
import com.quire.reader.data.index.Snippet
import com.quire.reader.data.index.TextSearchResult
import com.quire.reader.data.index.TextSearcher
import java.text.NumberFormat
import java.util.Locale

// The words the library text search and its Settings use for what the index is doing and what a search found. The
// decisions (which message, which notices, what counts) live here so they can be tested; the screens only lay them out.

private fun number(n: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

// ── coverage and indexing state ─────────────────────────────────────────────

/** "12 of 14": the books with usable text out of the books that could be indexed. */
fun coverageFraction(c: IndexCoverage): String = "${number(c.searchable)} of ${number(c.eligible)}"

/** Books that are not fully searchable, as "1 failed · 2 skipped · 3 partly indexed"; null when there are none. */
fun coverageIssues(c: IndexCoverage): String? = listOfNotNull(
  if (c.failed > 0) "${number(c.failed)} failed" else null,
  if (c.skipped > 0) "${number(c.skipped)} skipped" else null,
  if (c.partial > 0) "${number(c.partial)} partly indexed" else null,
).joinToString(" · ").ifEmpty { null }

/** One line on how much of the library can be searched; finishing indexing never reads as "everything is searchable". */
fun coverageLine(c: IndexCoverage): String =
  "${coverageFraction(c)} ${if (c.eligible == 1) "book" else "books"} searchable" + (coverageIssues(c)?.let { " · $it" } ?: "")

/** Whether a search may be missing books, either unindexed or indexed only in part. */
fun isPartial(c: IndexCoverage?): Boolean = c != null && (c.searchable < c.eligible || c.partial > 0)

/**
 * What to say about the indexer. [headline] is why books may be missing (null when nothing needs explaining),
 * [coverage] the searchable-books line, [progress] the fraction done while indexing runs, and [needsAccess] says the
 * only way forward is the all-files access switch. [inSettings] is true where the indexing switch is already on screen,
 * so the library's pointer to Settings is left out.
 */
data class IndexStatusText(val headline: String?, val coverage: String?, val progress: Float?, val needsAccess: Boolean)

fun indexStatusText(coverage: IndexCoverage?, activity: IndexActivity, inSettings: Boolean = false): IndexStatusText {
  val searchable = coverage?.searchable ?: 0
  val headline = when (activity) {
    is IndexActivity.Running -> "Indexing books · ${number(activity.done)} of ${number(activity.total)}"
    IndexActivity.PausedForReader -> "Indexing is paused while you read."
    IndexActivity.WaitingForCharging -> "Indexing is waiting for the device to charge."
    IndexActivity.Disabled ->
      if (searchable > 0) "Indexing is turned off. Books already indexed are still searchable, but new and changed books are not being added."
      else "Indexing is turned off, so there is nothing to search inside books." + if (inSettings) "" else " Turn it on in Settings."
    IndexActivity.PermissionMissing -> "Quire needs All files access to read and index your books."
    IndexActivity.Idle -> when {
      coverage == null -> null
      coverage.eligible == 0 -> "Add books to search inside them."
      searchable == 0 && coverage.failed + coverage.skipped >= coverage.eligible -> "None of your books could be indexed."
      searchable == 0 -> "No books are searchable yet."
      else -> null
    }
  }
  val progress = (activity as? IndexActivity.Running)?.let { if (it.total > 0) it.done.toFloat() / it.total else null }
  return IndexStatusText(headline, coverage?.takeIf { it.eligible > 0 }?.let(::coverageLine), progress, activity == IndexActivity.PermissionMissing)
}

// ── what a search shows ─────────────────────────────────────────────────────

/** A title with an optional explanation, for the states that have no book list to show. */
data class StatusCopy(val title: String, val detail: String? = null)

/** The message for a status with no results to list, or null while there are results. */
fun textSearchStatusCopy(status: TextSearchStatus, coverage: IndexCoverage?): StatusCopy? = when (status) {
  TextSearchStatus.Idle ->
    if ((coverage?.searchable ?: 0) > 0) StatusCopy("Search inside your books", "Type a word or a \"quoted phrase\". Matching passages are listed by book.") else null
  TextSearchStatus.TooShort -> StatusCopy("Type a little more", "A search needs at least two letters or digits.")
  TextSearchStatus.OverLimit ->
    StatusCopy("That search is too long", "A search can have up to ${FtsQuery.MAX_TOKENS} words. Shorten it to search; nothing is cut off for you.")
  TextSearchStatus.Searching -> StatusCopy("Searching…")
  is TextSearchStatus.NoMatch ->
    if (status.result.incomplete) {
      // The scan stopped early, so "no matches" would be a claim the search cannot back.
      StatusCopy("Couldn’t check every book", "This search is too broad to finish across your library, so books that match may be missing. Try a more specific search.")
    } else if (coverage != null && coverage.searchable == 0) {
      // Nothing was searched, so "no matches" would say more than is known.
      StatusCopy("Nothing to search yet", "Books have to be indexed before you can search inside them.")
    } else {
      StatusCopy(
        "No matches",
        when {
          coverage == null -> null
          coverage.searchable < coverage.eligible -> "Not every book is searchable yet, so a match may be in one that isn’t."
          coverage.partial > 0 -> "Some books are only partly searchable, so a match may be in a part that isn’t."
          else -> "No passage in your books contains that."
        },
      )
    }
  is TextSearchStatus.Results -> null
}

/** The notes that qualify a result list, in the order they matter. Also shown with "No matches" (the downgrade note can apply to it). */
fun resultNotices(result: TextSearchResult): List<String> = listOfNotNull(
  if (result.incomplete && result.books.isNotEmpty()) "This search is too broad to check every book, so some matches may be missing." else null,
  if (result.capped) "Very common search: counts are lower bounds and the order is approximate. Add a word to narrow it." else null,
  if (result.moreBooks > 0) "Showing the top ${number(result.books.size)} of ${if (result.capped) "at least " else ""}${number(result.matchingBooks)} matching books. Narrow the search to see the rest." else null,
  result.prefixDowngraded?.let { "Showing exact matches for \"$it\" — keep typing for prefix matches" },
)

/** The note under a book card whose index is missing text; null when it is complete. */
fun cardNote(gap: IndexGap): String? = when (gap) {
  IndexGap.None -> null
  IndexGap.FirstPartOnly -> "Only the first part of this book is searchable"
  IndexGap.PartsUnreadable -> "Parts of this book couldn’t be read, so some passages may be missing"
  IndexGap.Both -> "Only the first part of this book is searchable, and some of it couldn’t be read"
}

/** The note under the in-book search status ("Show all in this book") when the book's index is missing text. */
fun sheetNote(gap: IndexGap): String? = when (gap) {
  IndexGap.None -> null
  IndexGap.FirstPartOnly -> "Only the first part of this book is searchable, so later matches are not listed."
  IndexGap.PartsUnreadable -> "Parts of this book couldn’t be read, so some passages may be missing."
  IndexGap.Both -> "Only the first part of this book is searchable, and some of it couldn’t be read, so some matches may be missing."
}

/** "1 passage", "12 passages", "5000+ passages": passages that match, not occurrences of the words. */
fun passageLabel(count: PassageCount): String = "${count.label} ${if (count.value == 1 && !count.isCapped) "passage" else "passages"}"

/** The excerpts a book card lists: a few, or up to the most the search returns once expanded. */
const val SNIPPETS_COLLAPSED = 3

fun shownSnippets(snippets: List<Snippet>, expanded: Boolean): List<Snippet> =
  snippets.take(if (expanded) TextSearcher.SNIPPETS_PER_BOOK else SNIPPETS_COLLAPSED)

/** How many more excerpts expanding would reveal. */
fun hiddenSnippets(snippets: List<Snippet>, expanded: Boolean): Int =
  if (expanded) 0 else (minOf(snippets.size, TextSearcher.SNIPPETS_PER_BOOK) - SNIPPETS_COLLAPSED).coerceAtLeast(0)

/** An excerpt as one string plus the character ranges to highlight (end exclusive), with nothing interpreted as markup. */
data class HighlightedText(val text: String, val hits: List<IntRange>)

fun highlightedText(spans: List<ExcerptSpan>): HighlightedText {
  val text = StringBuilder()
  val hits = ArrayList<IntRange>()
  for (span in spans) {
    if (span.text.isEmpty()) continue
    if (span.hit) hits += text.length until text.length + span.text.length
    text.append(span.text)
  }
  return HighlightedText(text.toString(), hits)
}

// ── sizes ───────────────────────────────────────────────────────────────────

/** A byte count for people: "512 B", "48 KB", "13.4 MB", "1.25 GB". */
fun formatBytes(bytes: Long): String = when {
  bytes < 1024 -> "$bytes B"
  bytes < 1024 * 1024 -> "${bytes / 1024} KB"
  bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / 1048576.0)
  else -> "%.2f GB".format(Locale.US, bytes / 1073741824.0)
}
