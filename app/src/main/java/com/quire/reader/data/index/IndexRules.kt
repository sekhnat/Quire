package com.quire.reader.data.index

/** The file signature an index attempt was made against. */
data class IndexSignature(val mtime: Long, val sizeBytes: Long)

/**
 * True when a book needs (re)indexing: it has no terminal index state, or the file changed since the attempt.
 * Unchanged books stay settled whether the attempt succeeded, failed or found no text.
 */
fun needsIndexing(bookMtime: Long, bookSize: Long, state: IndexSignature?): Boolean =
  state == null || state.mtime != bookMtime || state.sizeBytes != bookSize

/**
 * At most this many matching passages are examined per library query. Finding who owns a match costs about 5 µs per
 * row, so the cap keeps very common queries ("the") near the 100 ms target. Counts stay exact below it.
 * It favours the books indexed first, because the engine visits those rows first. To be tuned against measurements.
 */
const val MAX_COUNTED_PASSAGES = 5000

/** A matching-passage count that may be a lower bound: shown "N+" when [isCapped]. */
data class PassageCount(val value: Int, val isCapped: Boolean) {
  val label: String get() = if (isCapped) "$value+" else "$value"

  companion object {
    /** Count for one book when its query examined [examined] matching passages in all, out of at most [cap]. */
    fun of(value: Int, examined: Int, cap: Int = MAX_COUNTED_PASSAGES): PassageCount = PassageCount(value, examined >= cap)
  }
}

/** What ranks one book's result against others. [lastOpenedAt] is 0 when the book was never opened. */
data class BookRank(val bookId: Long, val passages: Int, val lastOpenedAt: Long)

/** Most passages first, then most recently opened, then lowest id: a total order, so results never shuffle. */
val rankBooks: Comparator<BookRank> =
  compareByDescending<BookRank> { it.passages }.thenByDescending { it.lastOpenedAt }.thenBy { it.bookId }

/**
 * A final word prefix whose terms appear in more than this many chunks in total is searched as the exact word instead.
 * FTS4 reads and merges the whole doclist of every term with the prefix before returning a row, which the counting cap
 * cannot bound: about 0.13 µs per document on the emulator, so a prefix at this limit costs about 26 ms to merge plus
 * about 8 ms to measure (reading counts from `fts4aux` costs about 0.04 µs per document and stops once the limit is
 * passed). That keeps a typed query near 85 ms warm with the 5000-passage cap and 40 snippet-bearing books.
 * Measured on 505k chunks; to be tuned at scale verification.
 */
const val MAX_PREFIX_DOCUMENTS = 200_000

/** [word] as the index stores it: lower case with accents removed (SQLite's `unicode61` folding, to the extent the JVM matches it). */
fun foldedTerm(word: String): String =
  java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)

/** The prefix's term-range end: sorts after every term that starts with [prefix]. */
fun prefixRangeEnd(prefix: String): String = prefix + "\uDBFF\uDFFF"
