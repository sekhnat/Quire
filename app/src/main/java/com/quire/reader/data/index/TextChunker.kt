package com.quire.reader.data.index

/**
 * One text element of the book in reading order, as the indexer reads it from Readium.
 * [text] is the element's (whitespace-normalised) text; [href] and [mediaType] are its resource; [resourceProgression] is
 * its position in that resource (0..1, null when unknown) and [progression] its position in the whole publication (0..1);
 * [chapter] is the table-of-contents label it falls under.
 */
data class SourceElement(
  val href: String,
  val text: String,
  /** True for `h1`..`h6` elements; a heading always starts a new chunk. */
  val headingStart: Boolean,
  val mediaType: String?,
  val resourceProgression: Double?,
  val progression: Double,
  val chapter: String,
  /** True for the first element under a chapter that begins inside its resource; it always starts a new chunk, so a chunk never spans two chapters. */
  val chapterStart: Boolean = false,
)

/**
 * The text around a place where one long element was split between two chunks: up to [TextChunker.SEAM_TOKENS] tokens on
 * each side, with the split at [splitChar]. Indexed on its own, it lets a phrase that runs across the split still be found;
 * such a match counts only if it covers [splitChar], since anything else is already in one of the two chunks.
 */
data class Seam(val text: String, val splitChar: Int)

/**
 * One searchable row: a stretch of one resource's text, never overlapping another chunk. [seam] is set on the chunk that
 * starts part-way through an element (the text before the split ends the previous chunk).
 */
data class IndexChunk(
  val seq: Int,
  val chapter: String,
  val href: String,
  val mediaType: String?,
  val text: String,
  val segments: List<MappingSegment>,
  val progression: Double,
  val seam: Seam? = null,
) {
  /** [segments] as stored in `chunk.mapping`. Not part of equality, which [segments] already decides. */
  val mapping: ByteArray = MappingCodec.encode(segments)

  /** What this row costs against the per-book cap: text, mapping and seam text, in UTF-8 bytes. */
  val storedBytes: Int = text.utf8Length() + mapping.size + (seam?.text?.utf8Length() ?: 0)
}

/** The finished chunks of a book, and whether the per-book cap cut the book short. */
data class ChunkingResult(val chunks: List<IndexChunk>, val truncated: Boolean)

/**
 * Cuts a book's text elements into searchable chunks. Feed elements in reading order to [add] and call [finish] at the end;
 * chunks come back as soon as they are complete. Dropping the instance abandons the book without side effects.
 * Use one instance per book.
 *
 * Chunks target [minChars]..[maxChars] chars: neighbouring elements merge, and a heading or the start of a chapter starts
 * a new chunk. An element is split only when it alone is longer than [maxChars] (or to fill a chunk that is still under
 * [minChars]), between tokens and never inside one; each such split writes a [Seam]. A boundary between two elements
 * carries no copied text, so a phrase that runs from one element into the next is found only when both are in the same
 * chunk. Chunks never span hrefs. Once the next chunk would push the stored bytes of the book past [maxStoredBytes],
 * chunking stops at that chunk boundary and [truncated] becomes true.
 *
 * A resource is buffered until its last element arrives, because its chunks are cut over the resource as a whole.
 */
class TextChunker(
  private val maxStoredBytes: Long = MAX_STORED_BYTES,
  private val minChars: Int = MIN_CHARS,
  private val maxChars: Int = MAX_CHARS,
  private val seamTokens: Int = SEAM_TOKENS,
) {
  var truncated = false
    private set

  /** Stored bytes of all chunks returned so far. */
  var storedBytes = 0L
    private set

  private var href: String? = null
  private val pending = ArrayList<SourceElement>()
  private var nextSeq = 0

  /** Takes the next element; returns the chunks it completed (usually none). Elements after truncation are ignored. */
  fun add(element: SourceElement): List<IndexChunk> {
    if (truncated || element.text.isBlank()) return emptyList()
    val done = if (href != null && element.href != href) flush() else emptyList()
    if (truncated) return done
    href = element.href
    pending += element.copy(text = stripMarkers(element.text))
    return done
  }

  /** Returns the chunks of the last resource. */
  fun finish(): List<IndexChunk> = if (truncated) emptyList() else flush()

  private fun flush(): List<IndexChunk> {
    val elements = pending.toList()
    pending.clear()
    href = null
    return if (elements.isEmpty()) emptyList() else chunkResource(elements)
  }

  private fun chunkResource(elements: List<SourceElement>): List<IndexChunk> {
    // The resource is its elements joined by single spaces; tokens are found once over the whole of it.
    val sb = StringBuilder()
    val starts = IntArray(elements.size)
    elements.forEachIndexed { i, e ->
      if (i > 0) sb.append(' ')
      starts[i] = sb.length
      sb.append(e.text)
    }
    val text = sb.toString()
    val tokens = Tokenizer.tokenize(text)
    val ranges = chunkRanges(elements, starts, tokens)

    val out = ArrayList<IndexChunk>()
    for ((i, range) in ranges.withIndex()) {
      val (first, end) = range
      val chunk = buildChunk(elements, starts, text, tokens, first, end, previousFirst = if (i > 0) ranges[i - 1].first else 0)
      if (storedBytes + chunk.storedBytes > maxStoredBytes) {
        truncated = true
        break
      }
      storedBytes += chunk.storedBytes
      nextSeq++
      out += chunk
    }
    return out
  }

  /** Token ranges [first, end) of each chunk, packed at element boundaries where possible. */
  private fun chunkRanges(elements: List<SourceElement>, starts: IntArray, tokens: List<Token>): List<Pair<Int, Int>> {
    // elementFirstToken[i] = index of the first token at or after the start of element i.
    val elementFirstToken = IntArray(elements.size + 1)
    var t = 0
    for (i in elements.indices) {
      while (t < tokens.size && tokens[t].startChar < starts[i]) t++
      elementFirstToken[i] = t
    }
    elementFirstToken[elements.size] = tokens.size

    fun length(first: Int, endExclusive: Int) = tokens[endExclusive - 1].endChar - tokens[first].startChar

    val ranges = ArrayList<Pair<Int, Int>>() // first token, end token (exclusive)
    var first = -1
    var end = -1
    fun close() {
      ranges += first to end
    }
    for (i in elements.indices) {
      val elFirst = elementFirstToken[i]
      val elEnd = elementFirstToken[i + 1]
      if (elEnd <= elFirst) continue
      if (first < 0) { first = elFirst; end = elFirst }
      if ((elements[i].headingStart || elements[i].chapterStart) && end > first) { close(); first = elFirst; end = elFirst }
      if (length(first, elEnd) <= maxChars) { end = elEnd; continue }
      // The element does not fit in the open chunk. Start a new chunk with it, unless it is too long for any chunk and the
      // open one is still small: then it is split anyway, so it may as well fill the open chunk first.
      if (end > first && (length(elFirst, elEnd) <= maxChars || length(first, end) >= minChars)) {
        close(); first = elFirst; end = elFirst
        if (length(first, elEnd) <= maxChars) { end = elEnd; continue }
      }
      // Longer than a chunk: fill chunks token by token.
      for (tok in elFirst until elEnd) {
        if (tok > first && length(first, tok + 1) > maxChars) { close(); first = tok }
        end = tok + 1
      }
    }
    if (first >= 0 && end > first) close()
    return ranges
  }

  private fun buildChunk(
    elements: List<SourceElement>,
    starts: IntArray,
    text: String,
    tokens: List<Token>,
    first: Int,
    end: Int,
    previousFirst: Int,
  ): IndexChunk {
    val startChar = tokens[first].startChar
    val endChar = tokens[end - 1].endChar
    val chunkText = text.substring(startChar, endChar)

    var owner = starts.indexOfLast { it <= startChar }
    val firstElement = owner
    val segments = ArrayList<MappingSegment>()
    while (owner < elements.size && starts[owner] < endChar) {
      val from = maxOf(starts[owner], startChar) - startChar
      if (segments.isEmpty() || from > segments.last().charStart) {
        segments += MappingSegment(from, 0, MappingCodec.stored(elements[owner].resourceProgression))
      }
      owner++
    }
    val bounded = segments.mapIndexed { i, s -> s.copy(charEnd = if (i + 1 < segments.size) segments[i + 1].charStart else chunkText.length) }

    // A chunk that starts after the start of its first element continues an element split at its first token.
    val splitsElement = first > 0 && starts[firstElement] < startChar && tokens[first - 1].startChar >= starts[firstElement]
    val seam = if (splitsElement) seamAt(text, tokens, first, from = maxOf(previousFirst, first - seamTokens), until = minOf(end, first + seamTokens)) else null

    return IndexChunk(
      seq = nextSeq,
      chapter = elements[firstElement].chapter,
      href = elements[firstElement].href,
      mediaType = elements[firstElement].mediaType,
      text = chunkText,
      segments = bounded,
      progression = elements[firstElement].progression,
      seam = seam,
    )
  }

  /**
   * Tokens [from, until) around the split before token [split]: up to [seamTokens] either side, but no further than the two
   * chunks it separates, so a match in the seam is always one that the two chunks would hold together.
   */
  private fun seamAt(text: String, tokens: List<Token>, split: Int, from: Int, until: Int): Seam {
    val start = tokens[from].startChar
    return Seam(text.substring(start, tokens[until - 1].endChar), tokens[split].startChar - start)
  }

  companion object {
    const val MIN_CHARS = 1_000
    const val MAX_CHARS = 1_500

    /**
     * Enough tokens either side of a split for any phrase the user may type to cross it, plus a few spare because this
     * tokenizer and SQLite's could differ on rare characters.
     */
    const val SEAM_TOKENS = FtsQuery.MAX_TOKENS - 1 + 4

    /** Per-book cap on chunk text, mappings and seams, UTF-8 bytes. */
    const val MAX_STORED_BYTES = 6L * 1024 * 1024

    /** Marks the start and end of a match in FTS `highlight()` output, so they are removed from indexed text. */
    const val HIGHLIGHT_OPEN = ''
    const val HIGHLIGHT_CLOSE = ''

    private fun stripMarkers(text: String): String =
      if (text.indexOf(HIGHLIGHT_OPEN) < 0 && text.indexOf(HIGHLIGHT_CLOSE) < 0) text else text.replace(HIGHLIGHT_OPEN.toString(), "").replace(HIGHLIGHT_CLOSE.toString(), "")

    /** Chunks a whole book in one go. */
    fun chunk(
      elements: Iterable<SourceElement>,
      maxStoredBytes: Long = MAX_STORED_BYTES,
      minChars: Int = MIN_CHARS,
      maxChars: Int = MAX_CHARS,
    ): ChunkingResult {
      val chunker = TextChunker(maxStoredBytes, minChars, maxChars)
      val chunks = ArrayList<IndexChunk>()
      for (element in elements) chunks += chunker.add(element)
      chunks += chunker.finish()
      return ChunkingResult(chunks, chunker.truncated)
    }
  }
}
