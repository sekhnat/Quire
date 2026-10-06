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
 * True when a book that [needsIndexing] only because its signature moved can keep its index: same size, and the file's
 * fingerprint equals the one the index was built from. The fingerprint hashes the file's tail, which for an EPUB is the zip
 * central directory holding every entry's CRC, so any change to the content changes it; a copied or touched file does not.
 */
fun canCarryIndex(bookSize: Long, bookFingerprint: String?, state: IndexSignature?, sourceFingerprint: String?): Boolean =
  state != null && state.sizeBytes == bookSize && bookFingerprint != null && bookFingerprint == sourceFingerprint

/**
 * At most this many matching passages are counted per library query; past it counts read "N+". Matches stream from the
 * index in id order at about 0.4 µs each for a word and about 1 µs for a phrase or a filtered scan, so this is the largest
 * cap that keeps every capped query of the benchmark under 100 ms (the slowest, a very common word inside a broad filter,
 * takes 84 ms; at 100,000 it takes 147 ms). It also matches the point past which relevance ranking is skipped
 * ([TextSearcher.MAX_RANKED_MATCHES]). It favours the books indexed first, because the engine visits those rows first.
 */
const val MAX_COUNTED_PASSAGES = 50_000

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
 * FTS5 merges the doclists of every term with the prefix, and for a prefix of a very common word (`the`, `th`) that costs
 * more than a second, which the counting cap cannot bound. The terms' document counts come from the index vocabulary, so
 * measuring costs about as much as the limit's worth of document reads. Every other prefix of the benchmark (`hous`, `wh`,
 * `com`, `gre`, `sta`) stays under 50 ms at the 50,000-passage cap without a prefix index, so no `prefix=` option is used.
 */
const val MAX_PREFIX_DOCUMENTS = 200_000

private val COMBINING_MARKS = Regex("\\p{M}+")

/**
 * [word] as the index stores it: lower case with accents removed (SQLite's `unicode61 remove_diacritics 2` folding, to the
 * extent the JVM matches it). Recomposed afterwards, because decomposing also splits Hangul syllables into jamo, which
 * SQLite leaves whole.
 */
fun foldedTerm(word: String): String {
  val stripped = java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
  return java.text.Normalizer.normalize(stripped, java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
}

/** The prefix's term-range end: sorts after every term that starts with [prefix]. */
fun prefixRangeEnd(prefix: String): String = prefix + "\uDBFF\uDFFF"
