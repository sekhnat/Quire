package com.quire.reader.bench.legacy

import com.quire.reader.data.index.Token
import com.quire.reader.data.index.Tokenizer

/** One matched token in a chunk's text: the byte range FTS `offsets()` reports (a quadruple is column, term, offset, size). */
data class ByteMatch(val byteStart: Int, val byteSize: Int) {
  val byteEnd: Int get() = byteStart + byteSize
}

/** The matches in an `offsets()` string, in the order SQLite lists them. Anything that is not whole quadruples of numbers is dropped. */
fun parseOffsets(offsets: String): List<ByteMatch> {
  val numbers = offsets.split(' ').mapNotNull { it.toIntOrNull() }
  return (0 until numbers.size / 4).map { ByteMatch(numbers[it * 4 + 2], numbers[it * 4 + 3]) }
}

/**
 * The byte offset at which the first match in an `offsets()` string starts, or null when it has none. The first one is
 * the lowest, so it alone decides which chunk owns the match: a chunk owns it only if this is before its `primaryEndByte`.
 */
fun firstMatchByte(offsets: String): Int? {
  var field = 0
  var start = 0
  for (i in 0..offsets.length) {
    if (i < offsets.length && offsets[i] != ' ') continue
    if (field == 2) return offsets.substring(start, i).toIntOrNull()
    field++
    start = i + 1
  }
  return null
}

/** A stretch of an excerpt, either plain text or a highlighted match. */
data class ExcerptSpan(val text: String, val hit: Boolean)

/** A search result's excerpt as alternating spans, and where its first match sits in the book. */
data class Excerpt(val spans: List<ExcerptSpan>, val target: MatchTarget)

/** How much text to show around a match, and how far to look for a word boundary to cut at. */
private const val EXCERPT_BEFORE = 70
private const val EXCERPT_AFTER = 110
private const val BOUNDARY_SEARCH = 15
/** How long an excerpt may grow when pulling a later match into view (twice one window). */
private const val MAX_EXCERPT = 2 * (EXCERPT_BEFORE + EXCERPT_AFTER)

/**
 * Cuts a short excerpt from a chunk's stored text around its first match and marks every match inside it.
 * A later match — a second query term — that falls just past the window is pulled in ([MAX_EXCERPT] bounds the
 * growth), so a multi-term search shows more than the word that happened to come first.
 * Matches are given as [offsets], the `offsets()` string for this chunk. Adjacent matches (the words of a phrase,
 * or neighbouring query words) are merged into one highlight, and the first one becomes the locator target's highlight.
 *
 * Everything is cut from the stored text by char index; the byte offsets are converted first, never used as indexes.
 * Returns null when the offsets do not fit the text or no mapped segment contains the first match, which means the
 * row no longer agrees with its index.
 */
fun buildExcerpt(chunkText: String, segments: List<MappingSegment>, offsets: String): Excerpt? {
  val map = ByteCharMap(chunkText)
  val hits = mergeAdjacent(chunkText, parseOffsets(offsets).sortedBy { it.byteStart }.mapNotNull { m ->
    val start = map.charIndexOrNull(m.byteStart) ?: return@mapNotNull null
    val end = map.charIndexOrNull(m.byteEnd) ?: return@mapNotNull null
    if (end > start) start until end else null
  })
  val first = hits.firstOrNull() ?: return null
  val target = resolveMatch(chunkText, segments, map.byteOf(first.first), map.byteOf(first.last + 1) - map.byteOf(first.first)) ?: return null

  val start = cutStart(chunkText, first.first - EXCERPT_BEFORE)
  var end = cutEnd(chunkText, first.last + 1 + EXCERPT_AFTER)
  val beyond = hits.firstOrNull { it.first >= end }
  if (beyond != null) {
    val extended = cutEnd(chunkText, beyond.last + 1 + EXCERPT_AFTER)
    if (extended - start <= MAX_EXCERPT) end = extended
  }
  val spans = ArrayList<ExcerptSpan>()
  var at = start
  for (hit in hits) {
    if (hit.first >= end) break
    if (hit.last < start) continue
    val from = maxOf(hit.first, start)
    val to = minOf(hit.last + 1, end)
    if (from > at) spans += ExcerptSpan(chunkText.substring(at, from), hit = false)
    spans += ExcerptSpan(chunkText.substring(from, to), hit = true)
    at = to
  }
  if (at < end) spans += ExcerptSpan(chunkText.substring(at, end), hit = false)
  if (start > 0) spans[0] = spans[0].let { it.copy(text = "…" + it.text) }
  if (end < chunkText.length) spans[spans.lastIndex] = spans.last().let { it.copy(text = it.text + "…") }
  return Excerpt(spans, target)
}

/** Joins ranges that touch, overlap, or have only spaces and punctuation between them. */
private fun mergeAdjacent(text: String, ranges: List<IntRange>): List<IntRange> {
  val out = ArrayList<IntRange>()
  for (r in ranges) {
    val last = out.lastOrNull()
    if (last != null && (r.first <= last.last + 1 || Tokenizer.tokenize(text.substring(last.last + 1, r.first)).isEmpty())) {
      out[out.lastIndex] = last.first..maxOf(last.last, r.last)
    } else {
      out += r
    }
  }
  return out
}

/** The index to start an excerpt at, moved forward to the start of a word when one is near, and never inside a surrogate pair. */
private fun cutStart(text: String, wanted: Int): Int {
  if (wanted <= 0) return 0
  val limit = minOf(text.length, wanted + BOUNDARY_SEARCH)
  var i = wanted
  while (i < limit) {
    if (text[i - 1].isWhitespace() && !text[i].isWhitespace()) return i
    i++
  }
  return if (Character.isLowSurrogate(text[wanted])) wanted - 1 else wanted
}

/** The index to end an excerpt at (exclusive), moved back to the end of a word when one is near, and never inside a surrogate pair. */
private fun cutEnd(text: String, wanted: Int): Int {
  if (wanted >= text.length) return text.length
  val limit = maxOf(1, wanted - BOUNDARY_SEARCH)
  var i = wanted
  while (i > limit) {
    if (!text[i - 1].isWhitespace() && text[i].isWhitespace()) return i
    i--
  }
  return if (Character.isLowSurrogate(text[wanted])) wanted + 1 else wanted
}

/**
 * The JSON of the full locator for this match: the stored slim locator of the element holding it, plus the `text` object
 * Readium finds the passage with. Empty parts are left out. Returns null when the stored locator is not a JSON object.
 */
fun MatchTarget.fullLocatorJson(): String? {
  val slim = locatorJson.trim()
  if (!slim.startsWith('{') || !slim.endsWith('}')) return null
  val text = buildList {
    if (before.isNotEmpty()) add("\"before\":" + MappingCodec.quote(before))
    add("\"highlight\":" + MappingCodec.quote(highlight))
    if (after.isNotEmpty()) add("\"after\":" + MappingCodec.quote(after))
  }.joinToString(",", "{", "}")
  val body = slim.substring(1, slim.length - 1).trim()
  return "{" + (if (body.isEmpty()) "" else "$body,") + "\"text\":" + text + "}"
}
