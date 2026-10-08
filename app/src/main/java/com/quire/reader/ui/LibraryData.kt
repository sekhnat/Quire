package com.quire.reader.ui

import androidx.tracing.trace
import com.quire.reader.data.Book
import com.quire.reader.data.BookStatus
import com.quire.reader.data.db.FolderEntity

data class AuthorEntry(val name: String, val books: List<Book>)
data class SeriesEntry(val name: String, val author: String, val books: List<Book>, val missing: List<Int>, val total: Int) {
  val finished: Int get() = books.count { it.status == BookStatus.Finished }
}
data class ShelfDef(val title: String, val sub: String, val coverWidthDp: Int, val books: List<Book>, val filter: LibFilter? = null, val scope: Scope? = null)

/**
 * Everything the library screens derive from the book list, computed once per change rather than on every
 * recomposition. `LibraryState` builds it off the main thread. The cheap values every screen shows are worked out then;
 * the lazy ones belong to one view each and are first read by `LibraryState`'s derived flows, also off the main
 * thread, so composition only ever reads values that are already there.
 */
class LibraryData(val books: List<Book>, val folders: List<FolderEntity>, val loaded: Boolean = true) {
  val byId: Map<Long, Book> = books.associateBy { it.id }

  val counts: Map<LibFilter, Int> = mapOf(
    LibFilter.All to books.size,
    LibFilter.Reading to books.count { it.status == BookStatus.Reading },
    LibFilter.Unread to books.count { it.status == BookStatus.Unread },
    LibFilter.Recent to books.count { it.isNew },
    LibFilter.Finished to books.count { it.status == BookStatus.Finished },
  )

  /** The book "Continue reading" points at: the most recently opened one that is still in progress. */
  val resume: Book? = books.filter { it.status == BookStatus.Reading }.maxByOrNull { it.lastOpened }

  /** What a metadata search looks in, lowercased, by position in [books]. */
  val searchText: List<String> by traced("searchText") {
    books.map { (it.title + " " + it.author + " " + (it.series ?: "") + " " + it.tags.joinToString(" ")).lowercase() }
  }

  /** Authors grouped by the first letter of their sort name; anything that isn't A–Z goes under '#'. */
  val authorGroups: List<Pair<Char, List<AuthorEntry>>> by traced("authorGroups") {
    books.groupBy { it.primaryAuthor }
      .map { (name, bs) -> AuthorEntry(name, bs.sortedBy { it.sortTitle }) }
      .sortedBy { it.books.first().authorSort }
      .groupBy { e -> e.books.first().authorSort.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#' }
      .toSortedMap(compareBy<Char> { if (it == '#') '￿' else it })
      .map { it.key to it.value }
  }

  val series: List<SeriesEntry> by traced("series") {
    books.filter { it.series != null }.groupBy { it.series!! }.map { (name, bs) ->
      val sorted = bs.sortedBy { it.seriesNo ?: Double.MAX_VALUE }
      val owned = sorted.mapNotNull { it.seriesNo }.map { it.toInt() }.toSet()
      val highest = owned.maxOrNull() ?: 0
      SeriesEntry(name, sorted.first().author, sorted, (1..highest).filter { it !in owned }, maxOf(highest, sorted.size))
    }.sortedBy { it.name.lowercase() }
  }

  /** Every tag with its book count, most used first, then by name. */
  val tags: List<Pair<String, Int>> by traced("tags") {
    val count = LinkedHashMap<String, Int>()
    books.forEach { b -> b.tags.forEach { count[it] = (count[it] ?: 0) + 1 } }
    // Lowercased once per tag rather than on every comparison.
    count.entries.map { Triple(it.key, it.value, it.key.lowercase()) }
      .sortedWith(compareByDescending<Triple<String, Int, String>> { it.second }.thenBy { it.third })
      .map { it.first to it.second }
  }

  val ratedFive: Int by traced("ratedFive") { books.count { it.rating == 5 } }

  val shelves: List<ShelfDef> by traced("shelves") {
    buildList {
      val reading = books.filter { it.status == BookStatus.Reading }.sortedByDescending { it.lastOpened }
      if (reading.isNotEmpty()) add(ShelfDef("Continue reading", reading.size.toString(), 120, reading, filter = LibFilter.Reading))
      val recent = books.filter { it.isNew }.sortedByDescending { it.addedAt }
      if (recent.isNotEmpty()) add(ShelfDef("Recently added", "${recent.size} new", 96, recent, filter = LibFilter.Recent))
      series.sortedByDescending { it.books.size }.take(2).forEach {
        add(ShelfDef(it.name, "${it.books.size} of ${it.total}", 96, it.books, scope = Scope(ScopeKind.Series, it.name)))
      }
      tags.filter { it.second >= 3 }.take(2).forEach { (tag, n) ->
        add(ShelfDef(tag, "Tag · $n", 96, books.filter { tag in it.tags }, scope = Scope(ScopeKind.Tag, tag)))
      }
    }
  }

  fun siblings(book: Book): List<Book> = if (book.series == null) emptyList() else books.filter { it.series == book.series }.sortedBy { it.seriesNo ?: Double.MAX_VALUE }
  fun moreBy(book: Book): List<Book> = books.filter { it.primaryAuthor == book.primaryAuthor && it.id != book.id }.sortedBy { it.sortTitle }

  companion object { val Empty = LibraryData(emptyList(), emptyList(), loaded = false) }
}

/** A lazy value whose first computation shows in a system trace as `LibraryData.<name>`. */
private fun <T> traced(name: String, compute: () -> T): Lazy<T> = lazy { trace("LibraryData.$name", compute) }
