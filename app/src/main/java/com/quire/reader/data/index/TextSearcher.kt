package com.quire.reader.data.index

import com.quire.reader.data.RECENT_DAYS
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.IndexStateEntity
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.db.SearchableBook
import com.quire.reader.data.db.SearchableState
import com.quire.reader.data.toBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.TimeUnit

/**
 * Library text search. The books that may appear come from [db] (readable, present, allowed by the filters) and must have
 * a `done` index state for their current file; the index ([index]) streams the ids of matching chunks in id order, and
 * since each book owns a contiguous id range, [ChunkRanges] assigns every id to its book with no further lookup. Chunks do
 * not overlap, so every match is counted as it is. Text is read only for the passages behind shown snippets.
 *
 * [maxExamined] bounds how many matching passages one library query counts; tests pass a small one. Filters allowing at
 * most [perBookMax] books are searched book by book, inside each book's id range.
 */
class TextSearcher(
  private val db: QuireDatabase,
  private val indexDb: IndexDatabase,
  private val index: IndexStore = IndexStore(RoomIndexSql(indexDb)),
  private val maxExamined: Int = MAX_COUNTED_PASSAGES,
  private val maxBooks: Int = MAX_RESULT_BOOKS,
  private val snippetsPerBook: Int = SNIPPETS_PER_BOOK,
  private val pageSize: Int = PAGE_SIZE,
  private val perBookMax: Int = PER_BOOK_SEARCH_MAX,
  private val maxPrefixDocuments: Int = MAX_PREFIX_DOCUMENTS,
) {
  /** Results for [query] that follow both databases: a new value whenever books, reading state or the index change. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun observe(query: FtsQuery.Result.Query, filters: TextSearchFilters, order: SearchOrder = SearchOrder.Relevance): Flow<TextSearchResult> =
    combine(
      db.invalidationTracker.createFlow("book", "book_state", "book_tag"),
      indexDb.invalidationTracker.createFlow("index_state"),
    ) { _, _ -> }.mapLatest { search(query, filters, order) }

  /** A book that can be searched: its index is current and has text, so it owns the chunk ids [first]..[last]. */
  private class Candidate(val book: SearchableBook, val state: SearchableState) {
    val first: Long get() = state.firstChunkId
    val last: Long get() = state.lastChunkId
  }

  /** The books to search for [filters]: both databases read at once, then joined here. */
  private suspend fun candidates(filters: TextSearchFilters, recentSince: Long): List<Candidate> = coroutineScope {
    val states = async { indexDb.states().searchable() }
    val books = async { db.search().searchableBooks(filters.author, filters.series, filters.tag, filters.status?.name?.lowercase(), recentSince) }
    candidates(books.await(), states.await())
  }

  private fun candidates(books: List<SearchableBook>, states: List<SearchableState>): List<Candidate> {
    val byBook = states.associateBy { it.bookId }
    return books.mapNotNull { b ->
      val s = byBook[b.id] ?: return@mapNotNull null
      if (s.mtime != b.mtime || s.sizeBytes != b.sizeBytes) null else Candidate(b, s)
    }
  }

  suspend fun search(
    query: FtsQuery.Result.Query,
    filters: TextSearchFilters,
    order: SearchOrder = SearchOrder.Relevance,
    now: Long = System.currentTimeMillis(),
  ): TextSearchResult {
    val recentSince = now - TimeUnit.DAYS.toMillis(RECENT_DAYS)
    var q = query
    var downgraded: String? = null
    q.prefix?.let { if (isCommon(foldedTerm(it), cjk = false)) { q = q.withoutPrefix(); downgraded = it } }
    q.cjkPrefixes.firstOrNull { isCommon(it, cjk = true) }?.let { q = q.withoutCjkPrefixes(); downgraded = downgraded ?: it }

    val candidates = candidates(filters, recentSince)
    if (candidates.isEmpty()) return TextSearchResult(emptyList(), 0, capped = false, prefixDowngraded = downgraded)
    val ranges = ChunkRanges(candidates.map { BookChunks(it.book.id, it.first, it.last) })
    val byBook = candidates.associateBy { it.book.id }
    val byIndex = ranges.ranges.map { byBook.getValue(it.bookId) }

    // Count matching passages per book, in id order, until the cap.
    val counts = IntArray(ranges.size)
    val firstIds = Array(ranges.size) { ArrayList<Long>(snippetsPerBook) }
    val counted = if (q.phrase) HashSet<Long>() else null
    var examined = 0
    var lastExamined = Long.MIN_VALUE
    var scanned = 0L
    var incomplete = false
    val narrow = filters != TextSearchFilters.None && ranges.size <= perBookMax
    val scanLimit = maxExamined.toLong() * FILTERED_SCAN_FACTOR
    fun take(id: Long): Boolean {
      scanned++
      val i = ranges.indexOf(id)
      if (i >= 0) {
        counts[i]++
        if (firstIds[i].size < snippetsPerBook) firstIds[i] += id
        counted?.add(id)
        lastExamined = id
        if (++examined >= maxExamined) return false
      }
      if (!narrow && filters != TextSearchFilters.None && scanned >= scanLimit) { incomplete = true; return false }
      return true
    }
    val spans = if (narrow) ranges.ranges.map { it.first..it.last } else listOf(ranges.span!!)
    for (span in spans) {
      if (examined >= maxExamined || incomplete) break
      stream(q, span.first, span.last, ::take)
    }
    val capped = examined >= maxExamined
    if (q.phrase) {
      // A phrase may also run across the split of a long element; such a match belongs to the chunk after the split.
      val end = if (capped || incomplete) lastExamined else ranges.span!!.last
      for (hit in index.seamHits(q.match!!, ranges.span!!.first, end)) {
        val i = ranges.indexOf(hit.chunkId)
        if (i < 0 || !hit.crossesSplit || !counted!!.add(hit.chunkId)) continue
        counts[i]++
        // firstIds holds the book's lowest matching ids, so the seam's chunk joins them if it is lower than one of them.
        firstIds[i] += hit.chunkId
        firstIds[i].sort()
        if (firstIds[i].size > snippetsPerBook) firstIds[i].removeAt(firstIds[i].lastIndex)
      }
    }
    val matching = counts.indices.filter { counts[it] > 0 }
    if (matching.isEmpty()) return TextSearchResult(emptyList(), 0, capped, incomplete, downgraded)

    val shownIdx = rankOrder(q, matching, counts, byIndex, ranges, order, examined, capped).take(maxBooks)
    val snippetIds = shownIdx.flatMap { firstIds[it] }
    val built = snippets(q, snippetIds)
    val bookRows = db.books().rowsByIds(shownIdx.map { byIndex[it].book.id }).associateBy { it.id }
    val results = shownIdx.mapNotNull { i ->
      val c = byIndex[i]
      val book = bookRows[c.book.id] ?: return@mapNotNull null
      val snippets = firstIds[i].mapNotNull { id -> built[id]?.let { snippet(c.state, it.first, it.second) } }
      BookTextResult(book.toBook(now), PassageCount(counts[i], capped), c.state.gap, snippets)
    }
    return TextSearchResult(results, matching.size, capped, incomplete, downgraded)
  }

  /** Streams the matching ids of [q] from [from] to [to] into [onRow], in id order, until it returns false. */
  private suspend fun stream(q: FtsQuery.Result.Query, from: Long, to: Long, onRow: (Long) -> Boolean) {
    when {
      q.match != null -> index.matchingIds(q.match, from, to, alsoCjk = q.cjk, onRow = onRow)
      q.cjk != null -> index.matchingIds(q.cjk, from, to, cjk = true, onRow = onRow)
    }
  }

  /**
   * The order to show matching books in (indexes into the candidates). By relevance: books holding one of the best
   * [RANK_LIMIT] passages by BM25 come first, by their best passage; the rest follow by [rankBooks]. A query that hit the
   * examine cap, or matches more than [MAX_RANKED_MATCHES] passages, is too common for BM25 to tell books apart (and costly
   * to rank), and is ordered by [rankBooks] alone.
   */
  private suspend fun rankOrder(
    q: FtsQuery.Result.Query, matching: List<Int>, counts: IntArray, byIndex: List<Candidate>, ranges: ChunkRanges,
    order: SearchOrder, examined: Int, capped: Boolean,
  ): List<Int> {
    val library = matching.sortedWith(compareBy(rankBooks) { BookRank(byIndex[it].book.id, counts[it], byIndex[it].book.lastOpenedAt) })
    // A capped count means more matches than were examined, so possibly far more than BM25 can rank in time.
    if (order == SearchOrder.Library || capped || examined > MAX_RANKED_MATCHES) return library
    val span = ranges.span!!
    val top = when {
      q.match != null -> index.ranked(q.match, span.first, span.last, RANK_LIMIT)
      q.cjk != null -> index.ranked(q.cjk, span.first, span.last, RANK_LIMIT, cjk = true)
      else -> emptyList()
    }
    val best = HashMap<Int, Int>()
    top.forEachIndexed { position, id -> val i = ranges.indexOf(id); if (i >= 0 && counts[i] > 0) best.putIfAbsent(i, position) }
    return library.filter { it in best }.sortedBy { best.getValue(it) } + library.filter { it !in best }
  }

  /** The chunk and match ranges behind each of [ids], for the snippets; chunks that no longer fit their mapping are left out. */
  private suspend fun snippets(q: FtsQuery.Result.Query, ids: List<Long>): Map<Long, Pair<StoredChunk, Excerpt>> {
    if (ids.isEmpty()) return emptyMap()
    val chunks = index.chunks(ids)
    val terms = q.terms
    val inChunk = ids.associateWith { id -> chunks[id]?.let { matchRanges(it.text, terms) }.orEmpty() }
    // A phrase across a split element has no hit inside its chunk: the seam says where the part after the split is.
    val seamRanges = if (q.phrase) {
      val missing = ids.filter { inChunk.getValue(it).isEmpty() }
      if (missing.isEmpty()) emptyMap() else index.seamHits(q.match!!, missing.min(), missing.max()).mapNotNull { h -> h.rangeInChunk?.let { h.chunkId to it } }.toMap()
    } else emptyMap()
    return ids.mapNotNull { id ->
      val chunk = chunks[id] ?: return@mapNotNull null
      val hits = buildList {
        addAll(inChunk.getValue(id))
        seamRanges[id]?.let { add(it) }
        if (q.cjkRuns.isNotEmpty()) addAll(substringRanges(chunk.text, q.cjkRuns))
        // The index matched this chunk but the tokenizers disagree on it (rare characters): ask the index.
        if (isEmpty() && q.match != null) index.highlights(q.match, listOf(id))[id]?.let { addAll(highlightRanges(it)) }
      }
      val segments = runCatching { MappingCodec.decode(chunk.mapping, chunk.text.length) }.getOrNull() ?: return@mapNotNull null
      val excerpt = buildExcerpt(chunk.text, segments, chunk.href, chunk.mediaType, hits) ?: return@mapNotNull null
      id to (chunk to excerpt)
    }.toMap()
  }

  private fun snippet(state: SearchableState, chunk: StoredChunk, excerpt: Excerpt): Snippet? {
    val locator = excerpt.target.fullLocatorJson() ?: return null
    val target = IndexTarget(state.bookId, state.mtime, state.sizeBytes, locator, excerpt.target.highlight, chunk.progression)
    return Snippet(chunk.seq, chunk.chapter, excerpt.spans, target)
  }

  /**
   * Whether so many chunks hold a term starting with [prefix] that merging them would blow the time budget (more than
   * [maxPrefixDocuments] in total, counted from the index vocabulary, which stops reading as soon as the limit is passed).
   */
  private suspend fun isCommon(prefix: String, cjk: Boolean): Boolean =
    index.termDocumentsExceed(prefix, prefixRangeEnd(prefix), maxPrefixDocuments.toLong(), cjk)

  /** The next page of [bookId]'s matches after chunk [afterSeq] (-1 for the first), unaffected by the library examine cap. */
  suspend fun page(query: FtsQuery.Result.Query, bookId: Long, afterSeq: Int = -1): BookTextPage {
    val book = db.search().searchableBook(bookId) ?: return BookTextPage(emptyList(), null, IndexGap.None)
    val c = candidates(listOf(book), indexDb.states().searchable().filter { it.bookId == bookId }).firstOrNull()
      ?: return BookTextPage(emptyList(), null, indexDb.states().of(bookId)?.gap ?: IndexGap.None)
    val from = c.first + afterSeq + 1
    if (from > c.last) return BookTextPage(emptyList(), null, c.state.gap)
    val ids = ArrayList<Long>()
    stream(query, from, c.last) { id -> ids += id; ids.size <= pageSize }
    if (query.phrase) {
      val end = if (ids.size > pageSize) ids.last() else c.last
      val seen = ids.toHashSet()
      index.seamHits(query.match!!, from, end).filter { it.crossesSplit && it.chunkId !in seen }.forEach { ids += it.chunkId }
      ids.sort()
    }
    val page = ids.take(pageSize)
    val built = snippets(query, page)
    val snippets = page.mapNotNull { id -> built[id]?.let { snippet(c.state, it.first, it.second) } }
    return BookTextPage(snippets, if (ids.size > pageSize) (page.last() - c.first).toInt() else null, c.state.gap)
  }

  companion object {
    /** The most books one library search returns; more matches are reported as a count. */
    const val MAX_RESULT_BOOKS = 40
    const val SNIPPETS_PER_BOOK = 5
    const val PAGE_SIZE = 20

    /** Filters allowing at most this many books are searched book by book; broader ones stream the whole library. */
    const val PER_BOOK_SEARCH_MAX = 40

    /** A broad filter stops after reading this many times the examine cap of matches and reports the result incomplete. */
    const val FILTERED_SCAN_FACTOR = 8

    /** How many of the best passages by BM25 decide the relevance order. */
    const val RANK_LIMIT = 300

    /** Above this many matching passages a word is too common for BM25 to help, and the library order is used. */
    const val MAX_RANKED_MATCHES = 50_000
  }
}

private val IndexStateEntity.gap: IndexGap get() = IndexGap.of(truncated, unreadableResources > 0)
private val SearchableState.gap: IndexGap get() = IndexGap.of(truncated, unreadableResources > 0)
