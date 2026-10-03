package com.quire.reader.data.index

import com.quire.reader.data.RECENT_DAYS
import com.quire.reader.data.db.ChunkRow
import com.quire.reader.data.db.IndexedBook
import com.quire.reader.data.db.MatchRow
import com.quire.reader.data.db.PageRow
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.toBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.TimeUnit

/**
 * Library text search over the index. SQLite finds the first [maxExamined] matching passages in books that are valid and
 * that the filters allow ([com.quire.reader.data.db.SearchDao]); this class keeps the owned ones, counts and ranks the
 * books, then loads text for the few passages that become snippets and cuts their excerpts. No publication is opened,
 * chunk text is read only for the passages behind shown snippets, and the snippets come from the same sample as the counts.
 *
 * [maxExamined] bounds how many matching passages one library query looks at; tests pass a small one. Filters allowing
 * at most [perBookMax] books are searched book by book (see [sampleMatches]).
 */
class TextSearcher(
  private val db: QuireDatabase,
  private val maxExamined: Int = MAX_COUNTED_PASSAGES,
  private val maxBooks: Int = MAX_RESULT_BOOKS,
  private val snippetsPerBook: Int = SNIPPETS_PER_BOOK,
  private val pageSize: Int = PAGE_SIZE,
  private val perBookMax: Int = PER_BOOK_SEARCH_MAX,
  private val probeBooks: Int = BROAD_FILTER_PROBE_BOOKS,
  private val maxPrefixDocuments: Int = MAX_PREFIX_DOCUMENTS,
) {
  private val search get() = db.search()

  /** Results for [query] that follow the database: a new value whenever books, index state or reading state change. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun observe(query: FtsQuery.Result.Query, filters: TextSearchFilters): Flow<TextSearchResult> =
    db.invalidationTracker.createFlow("text_chunk", "index_state", "book", "book_state", "book_tag")
      .mapLatest { search(query, filters) }

  suspend fun search(query: FtsQuery.Result.Query, filters: TextSearchFilters, now: Long = System.currentTimeMillis()): TextSearchResult {
    val recentSince = now - TimeUnit.DAYS.toMillis(RECENT_DAYS)
    val commonPrefix = query.prefix?.takeIf { isCommonPrefix(it) }
    val sampled = sampleMatches(if (commonPrefix != null) query.withoutPrefix() else query, filters, recentSince)
    val (sample, capped, incomplete) = sampled
    val rows = sample.filter { firstMatchByte(it.offsets)?.let { first -> first < it.primaryEndByte } == true }
    if (rows.isEmpty()) return TextSearchResult(emptyList(), 0, capped, incomplete, commonPrefix)

    val perBook = rows.groupBy { it.bookId }
    val ranked = perBook.map { (id, owned) -> BookRank(id, owned.size, owned.first().lastOpenedAt) }.sortedWith(rankBooks).take(maxBooks)
    val shown = ranked.map { it.bookId }
    val snippetRows: Map<Long, List<MatchRow>> = shown.associateWith { id -> perBook.getValue(id).sortedBy { it.seq }.take(snippetsPerBook) }

    val books = db.books().rowsByIds(shown).associateBy { it.id }
    val indexed = search.indexedBooks(shown).associateBy { it.bookId }
    val chunks = search.chunks(snippetRows.values.flatten().map { it.id }).associateBy { it.id }
    val results = ranked.mapNotNull { rank ->
      val book = books[rank.bookId] ?: return@mapNotNull null
      val state = indexed[rank.bookId] ?: return@mapNotNull null
      val snippets = snippetRows.getValue(rank.bookId).mapNotNull { row ->
        val chunk = chunks[row.id] ?: return@mapNotNull null
        snippet(state, chunk.seq, chunk.chapter, chunk.progression, chunk.text, chunk.mapping, row.offsets)
      }
      BookTextResult(book.toBook(now), PassageCount(rank.passages, capped), state.truncated, snippets)
    }
    return TextSearchResult(results, perBook.size, capped, incomplete, commonPrefix)
  }

  /** The passages to count, whether the examine cap cut them, and whether a filtered scan stopped before covering every match. */
  private class Sample(val rows: List<MatchRow>, val capped: Boolean, val incomplete: Boolean = false) {
    operator fun component1() = rows
    operator fun component2() = capped
    operator fun component3() = incomplete
  }

  /**
   * Whether the library holds so many chunks with a term starting with [prefix] that merging them would blow the time
   * budget (more than [maxPrefixDocuments] in total). The terms are read in pages in term order and counting stops as
   * soon as the limit is passed, so a common prefix costs about the limit's worth of document reads, not the prefix's.
   */
  private suspend fun isCommonPrefix(prefix: String): Boolean {
    val from = foldedTerm(prefix)
    val until = prefixRangeEnd(from)
    var after: String? = null
    var documents = 0L
    while (true) {
      val page = search.terms(from, until, after, TERM_PAGE)
      for (t in page) {
        documents += t.documents
        if (documents > maxPrefixDocuments) return true
      }
      if (page.size < TERM_PAGE) return false
      after = page.last().term
    }
  }

  /**
   * The matching passages to count: at most [maxExamined] of them, and whether that limit (or the scan limit) cut the sample.
   * With no filters the cap alone bounds the work. With filters, a narrow one (a handful of books) is searched book by book
   * inside each book's docid range, which is exact and skips everything else. A broad one is scanned across the library
   * when the query has few enough matches to read them all (rejecting a match costs about 6 µs, so this is bounded by
   * [FILTERED_SCAN_FACTOR] times the cap); otherwise the word is common, and the first [probeBooks] allowed books are
   * searched individually, which fills the cap quickly. If they do not, the result is marked incomplete.
   */
  private suspend fun sampleMatches(query: FtsQuery.Result.Query, filters: TextSearchFilters, recentSince: Long): Sample {
    val status = filters.status?.name?.lowercase()
    suspend fun matches(cap: Int, minDocid: Long, maxDocid: Long) =
      search.libraryMatches(query.match, cap, minDocid, maxDocid, filters.author, filters.series, filters.tag, status, recentSince)

    if (filters == TextSearchFilters.None) return matches(maxExamined, 0, Long.MAX_VALUE).let { Sample(it, it.size >= maxExamined) }

    val allowed = search.allowedBookIds(filters.author, filters.series, filters.tag, status, recentSince)
    if (allowed.isEmpty()) return Sample(emptyList(), capped = false)

    /** Searches [ids] one by one inside each book's docid range, in index order, until the cap is reached. */
    suspend fun inBooks(ids: List<Long>): List<MatchRow> {
      val ranges = ids.mapNotNull { id -> search.bookRange(id).let { r -> if (r.firstId != null && r.lastId != null) r.firstId to r.lastId else null } }.sortedBy { it.first }
      val rows = ArrayList<MatchRow>()
      for ((first, last) in ranges) {
        if (rows.size >= maxExamined) break
        rows += matches(maxExamined - rows.size, first, last)
      }
      return rows
    }

    if (allowed.size <= perBookMax) return inBooks(allowed).let { Sample(it, it.size >= maxExamined) }

    // A broad filter. If the query has few enough matches to read them all, one scan of the library is exact.
    val scanLimit = maxExamined * FILTERED_SCAN_FACTOR
    val scan = search.scanBound(query.match, scanLimit)
    val last = scan.lastDocid ?: return Sample(emptyList(), capped = false)
    if (scan.matchCount < scanLimit) return matches(maxExamined, 0, last).let { Sample(it, it.size >= maxExamined) }

    // Too many matches to scan past the books the filter rejects, but a common word fills the cap within a few books:
    // search the first few allowed books and say so if that was not enough to fill it.
    val probed = allowed.take(probeBooks)
    val rows = inBooks(probed)
    val capped = rows.size >= maxExamined
    return Sample(rows, capped, incomplete = !capped && allowed.size > probed.size)
  }

  /** The next page of [bookId]'s matches after chunk [afterSeq] (-1 for the first), unaffected by the library examine cap. */
  suspend fun page(query: FtsQuery.Result.Query, bookId: Long, afterSeq: Int = -1): BookTextPage {
    val state = search.indexedBooks(listOf(bookId)).firstOrNull() ?: return BookTextPage(emptyList(), null, truncated = false)
    val rows: List<PageRow> = search.bookMatches(query.match, bookId, afterSeq, pageSize + 1)
    val page = rows.take(pageSize)
    val snippets = page.mapNotNull { snippet(state, it.seq, it.chapter, it.progression, it.text, it.mapping, it.offsets) }
    return BookTextPage(snippets, if (rows.size > pageSize) page.last().seq else null, state.truncated)
  }

  /** The snippet for one matching chunk, or null when its stored mapping no longer fits its text. */
  private fun snippet(state: IndexedBook, seq: Int, chapter: String, progression: Double, text: String, mapping: String, offsets: String): Snippet? {
    val segments = runCatching { MappingCodec.decode(mapping) }.getOrNull() ?: return null
    val excerpt = buildExcerpt(text, segments, offsets) ?: return null
    val locator = excerpt.target.fullLocatorJson() ?: return null
    val target = IndexTarget(state.bookId, state.mtime, state.sizeBytes, locator, excerpt.target.highlight, progression)
    return Snippet(seq, chapter, excerpt.spans, target)
  }

  companion object {
    /** The most books one library search returns; more matches are reported as a count. */
    const val MAX_RESULT_BOOKS = 40
    const val SNIPPETS_PER_BOOK = 5
    const val PAGE_SIZE = 20
    private const val TERM_PAGE = 16

    /** Filters allowing at most this many books are searched book by book; broader ones scan the library. */
    const val PER_BOOK_SEARCH_MAX = 40

    /** A broad filter is scanned for matches only if the query has fewer than this many times the examine cap of them. */
    const val FILTERED_SCAN_FACTOR = 8

    /** Past that, this many allowed books (oldest-indexed first) are searched one by one: a common word fills the cap in a few. */
    const val BROAD_FILTER_PROBE_BOOKS = 8
  }
}
