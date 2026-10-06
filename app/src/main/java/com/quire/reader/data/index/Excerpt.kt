package com.quire.reader.data.index

/**
 * The matches marked in FTS `highlight()` output, as char ranges of the text without the markers ([TextChunker.HIGHLIGHT_OPEN]
 * and [TextChunker.HIGHLIGHT_CLOSE], which indexed text never contains). An unclosed final match runs to the end.
 */
fun highlightRanges(marked: String): List<IntRange> {
  val out = ArrayList<IntRange>()
  var removed = 0
  var open = -1
  for (i in marked.indices) {
    when (marked[i]) {
      TextChunker.HIGHLIGHT_OPEN -> { open = i - removed; removed++ }
      TextChunker.HIGHLIGHT_CLOSE -> {
        val end = i - removed
        if (open >= 0 && end > open) out += open until end
        open = -1
        removed++
      }
    }
  }
  if (open >= 0 && marked.length - removed > open) out += open until marked.length - removed
  return out
}

/**
 * Where [terms] match in [text], as char ranges in order: the words of each term in consecutive tokens, case and accents
 * folded as the index folds them, the last word of a term a prefix when it says so. This is what FTS5's `highlight()` would
 * mark, without asking the index: that costs about a millisecond a chunk, against microseconds here. Tokens are found by
 * [Tokenizer], which agrees with the index's tokenizer except on rare characters, so a caller that finds nothing here for a
 * chunk the index says matches should ask the index.
 */
fun matchRanges(text: String, terms: List<MatchTerm>): List<IntRange> {
  if (terms.isEmpty()) return emptyList()
  val want = terms.map { t -> t.words.map(::foldWord) }
  val tokens = Tokenizer.tokenize(text)
  val out = ArrayList<IntRange>()
  for ((ti, term) in terms.withIndex()) {
    val words = want[ti]
    val last = words.lastIndex
    var i = 0
    while (i + last < tokens.size) {
      var ok = true
      for (k in 0..last) {
        val t = tokens[i + k]
        if (!tokenIs(text, t.startChar, t.endChar, words[k], prefix = k == last && term.prefix)) { ok = false; break }
      }
      if (ok) out += tokens[i].startChar until tokens[i + last].endChar
      i++
    }
  }
  return out.sortedBy { it.first }
}

/** Whether chars [start, end) of [text], folded, equal [want] (already folded) or, for a [prefix], start with it. Plain ASCII tokens are compared in place. */
private fun tokenIs(text: String, start: Int, end: Int, want: String, prefix: Boolean): Boolean {
  val length = end - start
  var ascii = true
  for (i in start until end) if (text[i].code >= 0x80) { ascii = false; break }
  if (ascii) return if (prefix) length >= want.length && text.regionMatches(start, want, 0, want.length, ignoreCase = true) else length == want.length && text.regionMatches(start, want, 0, length, ignoreCase = true)
  val folded = foldedTerm(text.substring(start, end))
  return if (prefix) folded.startsWith(want) else folded == want
}

/** [word] as the index folds it; plain ASCII, nearly all text, skips the normaliser. */
private fun foldWord(word: String): String {
  for (c in word) if (c.code >= 0x80) return foldedTerm(word)
  return word.lowercase(java.util.Locale.ROOT)
}

/** Every place in [text] where one of the CJK [runs] occurs, as char ranges; for excerpts of the CJK index, which has no `highlight()`. */
fun substringRanges(text: String, runs: List<String>): List<IntRange> = buildList {
  for (run in runs) {
    var at = text.indexOf(run)
    while (at >= 0) {
      add(at until at + run.length)
      at = text.indexOf(run, at + run.length)
    }
  }
}.sortedBy { it.first }

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
 * Matches are [hits], char ranges of [chunkText] in any order. Adjacent matches (the words of a phrase, or neighbouring
 * query words) are merged into one highlight, and the first one becomes the locator target's highlight; [href] and
 * [mediaType] are the chunk's resource, which completes the target's locator.
 *
 * Returns null when no hit fits the text or no mapped segment contains the first one, which means the row no longer agrees
 * with its index.
 */
fun buildExcerpt(chunkText: String, segments: List<MappingSegment>, href: String, mediaType: String?, hits: List<IntRange>): Excerpt? {
  val merged = mergeAdjacent(chunkText, hits.filter { it.first >= 0 && it.last < chunkText.length && !it.isEmpty() }.sortedBy { it.first })
  val first = merged.firstOrNull() ?: return null
  val target = resolveMatch(chunkText, segments, href, mediaType, first.first, first.last + 1) ?: return null

  val start = cutStart(chunkText, first.first - EXCERPT_BEFORE)
  var end = cutEnd(chunkText, first.last + 1 + EXCERPT_AFTER)
  val beyond = merged.firstOrNull { it.first >= end }
  if (beyond != null) {
    val extended = cutEnd(chunkText, beyond.last + 1 + EXCERPT_AFTER)
    if (extended - start <= MAX_EXCERPT) end = extended
  }
  val spans = ArrayList<ExcerptSpan>()
  var at = start
  for (hit in merged) {
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
