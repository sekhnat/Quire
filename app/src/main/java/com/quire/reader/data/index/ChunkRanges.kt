package com.quire.reader.data.index

/** The chunk ids [first]..[last] of one book; a book's chunks are written in one transaction, so its ids are contiguous. */
data class BookChunks(val bookId: Long, val first: Long, val last: Long)

/**
 * Finds the book a matching chunk belongs to from its id alone, so counting never joins against the chunk table. Books
 * not given here (stale, filtered out, removed) own no ids, and their chunks are skipped.
 */
class ChunkRanges(books: Collection<BookChunks>) {
  private val sorted = books.filter { it.last >= it.first }.sortedBy { it.first }
  private val firsts = LongArray(sorted.size) { sorted[it].first }
  private val lasts = LongArray(sorted.size) { sorted[it].last }
  private val ids = LongArray(sorted.size) { sorted[it].bookId }

  val size: Int get() = sorted.size
  val isEmpty: Boolean get() = sorted.isEmpty()
  val ranges: List<BookChunks> get() = sorted

  /** The lowest and highest id that any book here owns, or null when there are none. */
  val span: LongRange? get() = if (sorted.isEmpty()) null else firsts.first()..lasts.max()

  /** The index of the book owning [chunkId] in [ranges], or -1. */
  fun indexOf(chunkId: Long): Int {
    var lo = 0
    var hi = firsts.size - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      when {
        firsts[mid] > chunkId -> hi = mid - 1
        lasts[mid] < chunkId -> lo = mid + 1
        else -> return mid
      }
    }
    return -1
  }

  /** The book owning [chunkId], or null. */
  fun bookOf(chunkId: Long): Long? = indexOf(chunkId).let { if (it < 0) null else ids[it] }
}
