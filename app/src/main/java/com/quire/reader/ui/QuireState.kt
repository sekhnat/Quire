package com.quire.reader.ui

import androidx.tracing.trace
import com.quire.reader.data.Book
import com.quire.reader.data.BookStatus
import com.quire.reader.data.compactCount
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextStatusFilter
import com.quire.reader.theme.ReaderTheme
import java.time.LocalDate

enum class OnboardStep { Welcome, Access, Folders, Scan }
enum class LibView(val label: String) { Books("Books"), Authors("Authors"), Series("Series"), Tags("Tags") }
enum class LibLayout { Grid, List, Comfortable, Shelves }
enum class LibFilter(val label: String) { All("All"), Reading("In progress"), Unread("Unread"), Recent("Recently added"), Finished("Finished") }

/**
 * A word-count range for the library's length filter: [min] is included, [max] is not, and null leaves that side open
 * ("12K+" has no max, "under 40K" no min). Books not counted yet, or unreadable (0), are in no range.
 */
data class WordRange(val min: Int? = null, val max: Int? = null) {
  fun matches(words: Int?): Boolean = words != null && words > 0 && (min == null || words >= min) && (max == null || words < max)

  /** "Under 5K", "5K–10K", "100K+". */
  val label: String get() = when {
    min != null && max != null -> "${compactCount(min).uppercase()}–${compactCount(max).uppercase()}"
    min != null -> "${compactCount(min).uppercase()}+"
    max != null -> "Under ${compactCount(max).uppercase()}"
    else -> "Any length"
  }

  companion object {
    /**
     * A custom range from the two text fields: either may be empty (min only is "min+", max only "up to max"), but not
     * both. Null when both are empty, a typed side is not a number, or the minimum is not below the maximum.
     */
    fun custom(min: String, max: String): WordRange? {
      val lo = parseWordBound(min)
      val hi = parseWordBound(max)
      if ((lo == null && min.isNotBlank()) || (hi == null && max.isNotBlank())) return null
      if (lo == null && hi == null) return null
      if (lo != null && hi != null && lo >= hi) return null
      return WordRange(lo, hi)
    }
  }
}

/** The ready-made length ranges the filter sheet offers. */
enum class WordPreset(val range: WordRange) {
  Under5k(WordRange(max = 5_000)),
  From5kTo10k(WordRange(5_000, 10_000)),
  From10kTo20k(WordRange(10_000, 20_000)),
  From20kTo50k(WordRange(20_000, 50_000)),
  From50kTo100k(WordRange(50_000, 100_000)),
  Over100k(WordRange(min = 100_000)),
}

/** A typed bound: digits with optional separators and an optional "k" or "m" ("12k", "12,000", "1.5m"); null if empty or not a number. */
fun parseWordBound(text: String): Int? {
  val t = text.trim().lowercase().replace(",", "").replace(" ", "")
  if (t.isEmpty()) return null
  val scale = when {
    t.endsWith("k") -> 1_000.0
    t.endsWith("m") -> 1_000_000.0
    else -> 1.0
  }
  val n = (if (scale == 1.0) t else t.dropLast(1)).toDoubleOrNull() ?: return null
  val words = n * scale
  return if (words.isFinite() && words >= 1 && words <= Int.MAX_VALUE) words.toInt() else null
}

enum class Sheet { Display, Contents, Voices }
/** The library's sheets for the selected books: add a tag, file info, the copy folder picker, and the two confirmations. */
enum class BulkSheet { Tag, Info, Copy, ConfirmRemove, ConfirmDelete }
enum class TocTab(val label: String) { Contents("Contents"), Bookmarks("Bookmarks"), Highlights("Highlights") }
enum class ScopeKind(val label: String) { Author("Author"), Series("Series"), Tag("Tag") }
data class Scope(val kind: ScopeKind, val value: String) {
  val label get() = "${kind.label}: $value"
}

/**
 * Sort keys. Each key's value is "bigger = first" in the default (descending) order: most recently opened,
 * newest added, newest published, largest, longest.
 */
enum class SortKey(val label: String, val desc: String, val asc: String, val icon: Int) {
  Opened("Recently opened", "Most recent first", "Oldest first", com.quire.reader.R.drawable.ph_clock_counter_clockwise),
  Added("Last added", "Newest first", "Oldest first", com.quire.reader.R.drawable.ph_tray_arrow_down),
  Year("Publication date", "Newest first", "Oldest first", com.quire.reader.R.drawable.ph_calendar_blank),
  Size("File size", "Largest first", "Smallest first", com.quire.reader.R.drawable.ph_hard_drives),
  Pages("Page count", "Longest first", "Shortest first", com.quire.reader.R.drawable.ph_files),
  /** Only offered while word counts are shown; books not counted yet come last when longest is first. */
  Words("Word count", "Longest first", "Shortest first", com.quire.reader.R.drawable.ph_text_align_left);

  fun value(b: Book): Double = when (this) {
    Opened -> b.lastOpened.toDouble()
    Added -> b.addedAt.toDouble()
    Year -> (b.year ?: 0).toDouble()
    Size -> b.sizeBytes.toDouble()
    Pages -> b.pages.toDouble()
    Words -> (b.words ?: 0).toDouble()
  }
}

/** The library screen's view choices and searches. */
data class LibraryUiState(
  val view: LibView = LibView.Books,
  val filter: LibFilter = LibFilter.All,
  val sort: SortKey = SortKey.Opened,
  val sortAscending: Boolean = false,
  val sortOpen: Boolean = false,
  /** The length filter (null = any length), and whether its sheet is open. Not saved; only offered while word counts are shown. */
  val words: WordRange? = null,
  val wordsOpen: Boolean = false,
  val scope: Scope? = null,
  val query: String = "",
  val searchOpen: Boolean = false,
  /** What the search field looks at; the text typed for [SearchScope.Text] is kept apart from [query]. */
  val searchScope: SearchScope = SearchScope.Metadata,
  val textLibraryQuery: String = "",
  /** How "Inside books" results are ordered; follows the saved setting. */
  val textSearchOrder: SearchOrder = SearchOrder.Relevance,
  val layout: LibLayout = LibLayout.Grid,
  val importOpen: Boolean = false,
  /** The books picked for an action; a long press starts picking, and the library is in selection mode while any are. */
  val selected: Set<Long> = emptySet(),
  /** The sheet open for the selected books, if any. */
  val bulkSheet: BulkSheet? = null,
) {
  val selecting: Boolean get() = selected.isNotEmpty()
}

fun statusLabel(b: Book) = when {
  !b.readable -> "Can't open"
  b.status == BookStatus.Reading -> "${b.pct}%"
  b.status == BookStatus.Finished -> "Finished"
  b.isNew -> "New"
  else -> "Unread"
}

/** The saved layout with that name; anything unknown (or nothing saved yet) is the grid. */
fun libLayoutOf(name: String?): LibLayout = LibLayout.entries.firstOrNull { it.name == name } ?: LibLayout.Grid

/** The saved sort with that name; anything unknown (or nothing saved yet) is Recently opened. */
fun sortKeyOf(name: String?): SortKey = SortKey.entries.firstOrNull { it.name == name } ?: SortKey.Opened

/** `author · series #n`, or just the author for a book outside a series. */
fun authorLine(b: Book): String =
  if (b.series != null) "${b.author} · ${b.series}${b.seriesNoLabel?.let { " $it" } ?: ""}" else b.author

private val paragraphBreaks = Regex("\\s*\\n+\\s*")

/** A book's synopsis as one running paragraph for a list row; null when it has none. */
fun synopsisPreview(desc: String?): String? = desc?.trim()?.takeIf { it.isNotEmpty() }?.replace(paragraphBreaks, " ")

/** The caption under a cover: the active sort's metadata if there is one, otherwise reading status. */
fun cardStatus(b: Book, sort: SortKey): String = when (sort) {
  SortKey.Added -> "Added " + b.addedLabel.replace(", ${LocalDate.now().year}", "")
  SortKey.Year -> b.year?.toString() ?: "No date"
  SortKey.Size -> b.sizeLabel
  SortKey.Pages -> "${b.pages} pages"
  SortKey.Words -> b.wordsShort ?: "Not counted yet"
  SortKey.Opened -> statusLabel(b)
}

/**
 * A book's word count for a metadata line ("92k words"), or null: word counts are off ([show] false), the book is not
 * counted yet, or the caption already shows the count ([sort] is by words).
 */
fun wordsLabel(b: Book, show: Boolean, sort: SortKey? = null): String? = if (show && sort != SortKey.Words) b.wordsShort else null

/** [base] with the book's word count after it ("Unread · 92k words") when [wordsLabel] gives one. */
fun withWords(base: String, b: Book, show: Boolean, sort: SortKey? = null): String = wordsLabel(b, show, sort)?.let { "$base · $it" } ?: base

/** What decides the library's book list. Nothing else in [LibraryUiState] changes it, so nothing else recomputes it. */
data class BookQuery(
  val sort: SortKey = SortKey.Opened,
  val ascending: Boolean = false,
  val filter: LibFilter = LibFilter.All,
  val scope: Scope? = null,
  val query: String = "",
  val words: WordRange? = null,
)

val LibraryUiState.bookQuery: BookQuery get() = BookQuery(sort, sortAscending, filter, scope, query, words)

/**
 * Books for the library list: those in the scope (or, without one, passing the status filter) that match the search,
 * in the chosen order. Only the survivors are sorted.
 */
fun visibleBooks(q: BookQuery, lib: LibraryData): List<Book> = trace("Library.visibleBooks") {
  val scope = q.scope
  val words = q.words
  val text = q.query.takeIf { it.isNotBlank() }?.lowercase()
  val searchText = if (text != null) lib.searchText else null
  val kept = ArrayList<Book>()
  lib.books.forEachIndexed { i, b ->
    val inView = if (scope != null) when (scope.kind) {
      ScopeKind.Author -> b.primaryAuthor == scope.value
      ScopeKind.Series -> b.series == scope.value
      ScopeKind.Tag -> scope.value in b.tags
    } else when (q.filter) {
      LibFilter.All -> true
      LibFilter.Recent -> b.isNew
      LibFilter.Reading -> b.status == BookStatus.Reading
      LibFilter.Unread -> b.status == BookStatus.Unread
      LibFilter.Finished -> b.status == BookStatus.Finished
    }
    if (inView && (words == null || words.matches(b.words)) && (text == null || searchText!![i].contains(text))) kept += b
  }
  var order = compareBy<Book> { q.sort.value(it) }.thenBy { it.addedAt }.thenBy { it.sortTitle }
  if (!q.ascending) order = order.reversed()
  // In a series, the default sort is reading order; books without a number come last, still most recently opened first.
  if (scope?.kind == ScopeKind.Series && q.sort == SortKey.Opened) order = compareBy<Book> { it.seriesNo ?: Double.MAX_VALUE }.then(order)
  kept.sortWith(order)
  kept
}

/**
 * The library filters that narrow a text search: the same scope and status filter [visibleBooks] applies, without its
 * metadata query, and never a book with one of the [hidden] tags. Like there, an active scope replaces the status filter.
 */
fun textFilters(s: LibraryUiState, hidden: Set<String> = emptySet()): TextSearchFilters =
  textFiltersOf(s).copy(hiddenTags = hidden, minWords = s.words?.min, maxWords = s.words?.max)

private fun textFiltersOf(s: LibraryUiState): TextSearchFilters = when (val scope = s.scope) {
  null -> TextSearchFilters(
    status = when (s.filter) {
      LibFilter.All -> null
      LibFilter.Reading -> TextStatusFilter.Reading
      LibFilter.Unread -> TextStatusFilter.Unread
      LibFilter.Finished -> TextStatusFilter.Finished
      LibFilter.Recent -> TextStatusFilter.Recent
    },
  )
  else -> when (scope.kind) {
    ScopeKind.Author -> TextSearchFilters(author = scope.value)
    ScopeKind.Series -> TextSearchFilters(series = scope.value)
    ScopeKind.Tag -> TextSearchFilters(tag = scope.value)
  }
}
