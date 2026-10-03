package com.quire.reader.data.index

/**
 * One text element of the book in reading order, as the indexer reads it from Readium.
 * [text] is the element's (whitespace-normalised) text; [locatorJson] locates the element; [progression] is its position
 * in the whole publication (0..1); [chapter] is the table-of-contents label it falls under.
 */
data class SourceElement(
  val href: String,
  val text: String,
  /** True for `h1`..`h6` elements; a heading always starts a new chunk. */
  val headingStart: Boolean,
  val locatorJson: String,
  val progression: Double,
  val chapter: String,
  /** True for the first element under a chapter that begins inside its resource; it always starts a new chunk, so a chunk never spans two chapters. */
  val chapterStart: Boolean = false,
)

/**
 * One searchable row. [text] is the chunk's own (primary) text followed by up to [TextChunker.CONTEXT_TOKENS] tokens of
 * the text that follows it in the same resource, so a phrase starting in the primary text always fits in one row.
 *
 * Ownership: an FTS match belongs to this chunk only if its first token starts before [primaryEndByte]; otherwise it is
 * a copy of a match that the next chunk owns. [tokenStart] until [tokenEnd] number the primary tokens across the book.
 */
data class IndexChunk(
  val seq: Int,
  val chapter: String,
  val href: String,
  val text: String,
  val primaryEndByte: Int,
  val tokenStart: Int,
  val tokenEnd: Int,
  val segments: List<MappingSegment>,
  val progression: Double,
) {
  /** [segments] as stored in `text_chunk.mapping`. */
  val mappingJson: String = MappingCodec.encode(segments)

  /** What this row costs against the per-book cap: stored text plus mapping, in UTF-8 bytes. */
  val storedBytes: Int = text.utf8Length() + mappingJson.utf8Length()
}

/** The finished chunks of a book, and whether the per-book cap cut the book short. */
data class ChunkingResult(val chunks: List<IndexChunk>, val truncated: Boolean)

/**
 * Cuts a book's text elements into searchable chunks. Feed elements in reading order to [add] and call [finish] at the end;
 * chunks come back as soon as they are complete. Dropping the instance abandons the book without side effects.
 * Use one instance per book.
 *
 * Primary chunks target [minChars]..[maxChars] chars: small neighbouring elements merge, a heading or the start of a chapter starts a new chunk, a
 * long element is split between tokens (never inside one) with each part keeping its source range. Chunks never span
 * hrefs, nor does their context. Once the next chunk would push the stored bytes of the book past [maxStoredBytes],
 * chunking stops at that chunk boundary and [truncated] becomes true.
 *
 * A resource is buffered until its last element arrives, because its final chunks need the tokens that follow them.
 */
class TextChunker(
  private val maxStoredBytes: Long = MAX_STORED_BYTES,
  private val minChars: Int = MIN_PRIMARY_CHARS,
  private val maxChars: Int = MAX_PRIMARY_CHARS,
  private val contextTokens: Int = CONTEXT_TOKENS,
) {
  var truncated = false
    private set

  /** Stored bytes of all chunks returned so far. */
  var storedBytes = 0L
    private set

  private var href: String? = null
  private val pending = ArrayList<SourceElement>()
  private var nextSeq = 0
  private var nextToken = 0

  /** Takes the next element; returns the chunks it completed (usually none). Elements after truncation are ignored. */
  fun add(element: SourceElement): List<IndexChunk> {
    if (truncated || element.text.isBlank()) return emptyList()
    val done = if (href != null && element.href != href) flush() else emptyList()
    if (truncated) return done
    href = element.href
    pending += element
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
    val ranges = primaryRanges(elements, starts, tokens)

    val out = ArrayList<IndexChunk>()
    for ((first, end) in ranges) {
      val chunk = buildChunk(elements, starts, text, tokens, first, end)
      if (storedBytes + chunk.storedBytes > maxStoredBytes) {
        truncated = true
        break
      }
      storedBytes += chunk.storedBytes
      nextSeq++
      nextToken += end - first
      out += chunk
    }
    return out
  }

  /** Token ranges [first, end) of each chunk's primary text, packed at element boundaries where possible. */
  private fun primaryRanges(elements: List<SourceElement>, starts: IntArray, tokens: List<Token>): List<Pair<Int, Int>> {
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
      if (end > first && length(first, end) >= minChars) {
        close(); first = elFirst; end = elFirst
        if (length(first, elEnd) <= maxChars) { end = elEnd; continue }
      }
      // Too long for one chunk (or the open chunk is too small to close): fill chunks token by token.
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
  ): IndexChunk {
    val startChar = tokens[first].startChar
    val primaryEndChar = tokens[end - 1].endChar
    val contextEndToken = minOf(end + contextTokens, tokens.size)
    val endChar = tokens[contextEndToken - 1].endChar
    val chunkText = text.substring(startChar, endChar)
    val bytes = ByteCharMap(chunkText)

    var owner = starts.indexOfLast { it <= startChar }
    val firstElement = owner
    val segments = ArrayList<MappingSegment>()
    while (owner < elements.size && starts[owner] < endChar) {
      val elementStart = starts[owner]
      val from = maxOf(elementStart, startChar)
      val to = minOf(elementStart + elements[owner].text.length, endChar)
      if (to > from) {
        segments += MappingSegment(bytes.byteOf(from - startChar), bytes.byteOf(to - startChar), from - elementStart, elements[owner].locatorJson)
      }
      owner++
    }
    return IndexChunk(
      seq = nextSeq,
      chapter = elements[firstElement].chapter,
      href = elements[firstElement].href,
      text = chunkText,
      primaryEndByte = bytes.byteOf(primaryEndChar - startChar),
      tokenStart = nextToken,
      tokenEnd = nextToken + (end - first),
      segments = segments,
      progression = elements[firstElement].progression,
    )
  }

  companion object {
    const val MIN_PRIMARY_CHARS = 600
    const val MAX_PRIMARY_CHARS = 900

    /** A few tokens beyond `FtsQuery.MAX_TOKENS - 1`, because this tokenizer and SQLite's differ on rare characters. */
    const val CONTEXT_TOKENS = FtsQuery.MAX_TOKENS - 1 + 4

    /** Per-book cap on chunk text plus serialized mapping, UTF-8 bytes, repeated context included. */
    const val MAX_STORED_BYTES = 6L * 1024 * 1024

    /** Chunks a whole book in one go. */
    fun chunk(elements: Iterable<SourceElement>, maxStoredBytes: Long = MAX_STORED_BYTES): ChunkingResult {
      val chunker = TextChunker(maxStoredBytes)
      val chunks = ArrayList<IndexChunk>()
      for (element in elements) chunks += chunker.add(element)
      chunks += chunker.finish()
      return ChunkingResult(chunks, chunker.truncated)
    }
  }
}
