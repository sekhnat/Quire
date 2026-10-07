package com.quire.reader.ui

import com.quire.reader.data.Book
import com.quire.reader.data.BookStatus
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextStatusFilter
import com.quire.reader.theme.ReaderTheme
import java.time.LocalDate

enum class OnboardStep { Welcome, Access, Folders, Scan }
enum class LibView(val label: String) { Books("Books"), Authors("Authors"), Series("Series"), Tags("Tags") }
enum class LibLayout { Grid, List, Comfortable, Shelves }
enum class LibFilter(val label: String) { All("All"), Reading("In progress"), Unread("Unread"), Recent("Recently added"), Finished("Finished") }
enum class Sheet { Display, Contents }
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
  Pages("Page count", "Longest first", "Shortest first", com.quire.reader.R.drawable.ph_files);

  fun value(b: Book): Double = when (this) {
    Opened -> b.lastOpened.toDouble()
    Added -> b.addedAt.toDouble()
    Year -> (b.year ?: 0).toDouble()
    Size -> b.sizeBytes.toDouble()
    Pages -> b.pages.toDouble()
  }
}

/** The library screen's view choices and searches. */
data class LibraryUiState(
  val view: LibView = LibView.Books,
  val filter: LibFilter = LibFilter.All,
  val sort: SortKey = SortKey.Opened,
  val sortAscending: Boolean = false,
  val sortOpen: Boolean = false,
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
)

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
  SortKey.Opened -> statusLabel(b)
}

/** Books for the library list after sort, scope, filter and search have been applied. */
fun visibleBooks(s: LibraryUiState, all: List<Book>): List<Book> {
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

/**
 * The library filters that narrow a text search: the same scope and status filter [visibleBooks] applies, without its
 * metadata query. Like there, an active scope replaces the status filter.
 */
fun textFilters(s: LibraryUiState): TextSearchFilters = when (val scope = s.scope) {
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
