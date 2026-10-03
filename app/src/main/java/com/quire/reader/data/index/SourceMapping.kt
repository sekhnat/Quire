package com.quire.reader.data.index

/**
 * Says where a stretch of a chunk's stored text came from: UTF-8 bytes [byteStart, byteEnd) of the chunk text are the
 * source element's text starting at its char [charStart], and [locatorJson] locates that element. Segments at or past the
 * chunk's `primaryEndByte` are repeated context rather than the chunk's own text.
 */
data class MappingSegment(val byteStart: Int, val byteEnd: Int, val charStart: Int, val locatorJson: String)

/** The compact JSON stored in `text_chunk.mapping`: `[[byteStart,byteEnd,charStart,"locator"],...]`. */
object MappingCodec {
  fun encode(segments: List<MappingSegment>): String {
    val sb = StringBuilder("[")
    segments.forEachIndexed { i, s ->
      if (i > 0) sb.append(',')
      sb.append('[').append(s.byteStart).append(',').append(s.byteEnd).append(',').append(s.charStart).append(',')
      appendQuoted(sb, s.locatorJson)
      sb.append(']')
    }
    return sb.append(']').toString()
  }

  /** Throws [IllegalArgumentException] for text this object did not produce. */
  fun decode(json: String): List<MappingSegment> {
    val r = Reader(json)
    val out = ArrayList<MappingSegment>()
    r.expect('[')
    if (!r.consume(']')) {
      do {
        r.expect('[')
        val byteStart = r.int(); r.expect(',')
        val byteEnd = r.int(); r.expect(',')
        val charStart = r.int(); r.expect(',')
        val locator = r.string()
        r.expect(']')
        out += MappingSegment(byteStart, byteEnd, charStart, locator)
      } while (r.consume(','))
      r.expect(']')
    }
    require(r.atEnd()) { "trailing data in mapping" }
    return out
  }

  /** [s] as a JSON string literal, quotes included. */
  fun quote(s: String): String = StringBuilder(s.length + 2).also { appendQuoted(it, s) }.toString()

  private fun appendQuoted(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) when {
      c == '"' -> sb.append("\\\"")
      c == '\\' -> sb.append("\\\\")
      c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
      else -> sb.append(c)
    }
    sb.append('"')
  }

  private class Reader(private val s: String) {
    private var i = 0

    fun atEnd() = i == s.length

    fun consume(c: Char): Boolean = (i < s.length && s[i] == c).also { if (it) i++ }

    fun expect(c: Char) = require(consume(c)) { "expected '$c' at $i" }

    fun int(): Int {
      val start = i
      while (i < s.length && s[i] in '0'..'9') i++
      require(i > start) { "expected a number at $start" }
      return s.substring(start, i).toInt()
    }

    fun string(): String {
      expect('"')
      val sb = StringBuilder()
      while (true) {
        require(i < s.length) { "unterminated string" }
        val c = s[i++]
        when (c) {
          '"' -> return sb.toString()
          '\\' -> {
            require(i < s.length) { "unterminated escape" }
            when (val e = s[i++]) {
              '"', '\\' -> sb.append(e)
              'u' -> {
                require(i + 4 <= s.length) { "short unicode escape" }
                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                i += 4
              }
              else -> throw IllegalArgumentException("bad escape \\$e")
            }
          }
          else -> sb.append(c)
        }
      }
    }
  }
}

/**
 * Where an FTS match sits in the book, with text cut from the stored (whitespace-normalised) source text so a
 * `Locator.Text` can be built from it: [locatorJson] is the element containing the match start; [before], [highlight]
 * and [after] come from that element's part of the chunk only. A match that runs into the next element is cut short at
 * the element boundary, which is enough for Readium to find the passage. [chunkCharStart] is the match start in the chunk
 * text and [elementCharStart] the same position in the source element's own text.
 */
data class MatchTarget(
  val locatorJson: String,
  val before: String,
  val highlight: String,
  val after: String,
  val chunkCharStart: Int,
  val elementCharStart: Int,
)

/**
 * Resolves one FTS match, given as the byte offset and size from `offsets()`, to the element it starts in.
 * Returns null when the offsets do not fall on character boundaries inside a mapped segment (a stale or damaged row).
 */
fun resolveMatch(
  chunkText: String,
  segments: List<MappingSegment>,
  matchByteStart: Int,
  matchByteSize: Int,
  contextChars: Int = 50,
): MatchTarget? {
  val map = ByteCharMap(chunkText)
  val segment = segments.firstOrNull { matchByteStart >= it.byteStart && matchByteStart < it.byteEnd } ?: return null
  val matchStart = map.charIndexOrNull(matchByteStart) ?: return null
  val segStart = map.charIndexOrNull(segment.byteStart) ?: return null
  val segEnd = map.charIndexOrNull(segment.byteEnd) ?: return null
  val matchEnd = map.charIndexOrNull(matchByteStart + matchByteSize)?.coerceAtMost(segEnd) ?: return null
  if (matchEnd < matchStart) return null

  var beforeStart = maxOf(segStart, matchStart - contextChars)
  if (beforeStart > segStart && Character.isLowSurrogate(chunkText[beforeStart])) beforeStart--
  var afterEnd = minOf(segEnd, matchEnd + contextChars)
  if (afterEnd < segEnd && Character.isLowSurrogate(chunkText[afterEnd])) afterEnd++
  return MatchTarget(
    locatorJson = segment.locatorJson,
    before = chunkText.substring(beforeStart, matchStart),
    highlight = chunkText.substring(matchStart, matchEnd),
    after = chunkText.substring(matchEnd, afterEnd),
    chunkCharStart = matchStart,
    elementCharStart = segment.charStart + (matchStart - segStart),
  )
}
