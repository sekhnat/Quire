package com.quire.reader.data.index

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Says where a stretch of a chunk's text came from: chars [charStart, charEnd) of the chunk text belong to one source
 * element, which sits at [progression] through its resource (null when Readium gave none). A segment ends where the next
 * one starts, so the space that joins two elements belongs to the first; the last segment ends with the chunk.
 */
data class MappingSegment(val charStart: Int, val charEnd: Int, val progression: Double?)

/**
 * The binary form of a chunk's segments, stored in `chunk.mapping`: a version byte, the segment count, then per segment its
 * char start as a delta from the previous start and its quantised progression as a zigzag delta from the previous one, all
 * as unsigned LEB128 varints. Segment ends are implied (see [MappingSegment]). Typically 3–5 bytes a segment.
 *
 * The locator of a segment is not stored: it is the chunk's href and media type plus the segment's progression, which is
 * everything the indexer ever kept of it (see [slimLocatorJson]).
 */
object MappingCodec {
  const val VERSION = 2

  /** Progression is stored in steps of 1/[SCALE]: finer than any page, and two bytes at most. */
  const val SCALE = 65_535

  /** The quantised form of [progression]; -1 for none. */
  fun quantise(progression: Double?): Int = progression?.let { (it.coerceIn(0.0, 1.0) * SCALE).roundToInt() } ?: -1

  /** [progression] as it reads back from storage, so a chunk can be compared with its decoded mapping. */
  fun stored(progression: Double?): Double? = dequantise(quantise(progression))

  private fun dequantise(q: Int): Double? = if (q < 0) null else q.toDouble() / SCALE

  fun encode(segments: List<MappingSegment>): ByteArray {
    val out = ByteArrayOutputStream(2 + segments.size * 4)
    out.write(VERSION)
    writeVarint(out, segments.size.toLong())
    var start = 0
    var q = 0
    for (s in segments) {
      require(s.charStart >= start) { "segments out of order" }
      writeVarint(out, (s.charStart - start).toLong())
      val next = quantise(s.progression)
      writeVarint(out, zigzag(next - q))
      start = s.charStart
      q = next
    }
    return out.toByteArray()
  }

  /** The segments of a chunk whose text is [textLength] chars long. Throws [IllegalArgumentException] for bytes this object did not produce. */
  fun decode(blob: ByteArray, textLength: Int): List<MappingSegment> {
    val r = Reader(blob)
    require(r.byte() == VERSION) { "unknown mapping version" }
    val count = r.varint().toInt()
    require(count in 0..textLength + 1) { "bad segment count" }
    val starts = IntArray(count)
    val progressions = arrayOfNulls<Double>(count)
    var start = 0L
    var q = 0L
    for (i in 0 until count) {
      start += r.varint()
      q += unzigzag(r.varint())
      require(start <= textLength && q in -1..SCALE.toLong()) { "segment out of range" }
      starts[i] = start.toInt()
      progressions[i] = dequantise(q.toInt())
    }
    require(r.atEnd()) { "trailing data in mapping" }
    return List(count) { i -> MappingSegment(starts[i], if (i + 1 < count) starts[i + 1] else textLength, progressions[i]) }
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

  private fun zigzag(v: Int): Long = ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL

  private fun unzigzag(v: Long): Long = (v ushr 1) xor -(v and 1)

  private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
    var v = value
    while (v >= 0x80) {
      out.write(((v and 0x7F) or 0x80).toInt())
      v = v ushr 7
    }
    out.write(v.toInt())
  }

  private class Reader(private val b: ByteArray) {
    private var i = 0

    fun atEnd() = i == b.size

    fun byte(): Int {
      require(i < b.size) { "truncated mapping" }
      return b[i++].toInt() and 0xFF
    }

    fun varint(): Long {
      var result = 0L
      var shift = 0
      while (true) {
        val v = byte()
        result = result or ((v and 0x7F).toLong() shl shift)
        if (v and 0x80 == 0) return result
        shift += 7
        require(shift < 35) { "varint too long" }
      }
    }
  }
}

/**
 * The locator the indexer keeps for an element: its resource, the resource's media type and the element's progression
 * through it. The stored text is the source of the highlight and navigation finds the passage by that text, so nothing
 * else is needed. Matches what Readium's `Locator.toJSON()` writes for those three fields.
 */
fun slimLocatorJson(href: String, mediaType: String?, progression: Double?): String = buildString {
  append("{\"href\":").append(MappingCodec.quote(href))
  if (mediaType != null) append(",\"type\":").append(MappingCodec.quote(mediaType))
  append(",\"locations\":{")
  if (progression != null) append("\"progression\":").append(progression)
  append("}}")
}

/**
 * Where an FTS match sits in the book, with text cut from the stored (whitespace-normalised) source text so a
 * `Locator.Text` can be built from it: [locatorJson] is the slim locator of the element containing the match start;
 * [before], [highlight] and [after] come from that element's part of the chunk only. A match that runs into the next
 * element is cut short at the element boundary, which is enough for Readium to find the passage. [chunkCharStart] is the
 * match start in the chunk text.
 */
data class MatchTarget(
  val locatorJson: String,
  val before: String,
  val highlight: String,
  val after: String,
  val chunkCharStart: Int,
)

/**
 * Resolves one match, chars [matchStart, matchEnd) of the chunk text, to the element it starts in. The chunk's [href] and
 * [mediaType] complete the element's locator. Returns null when the match does not start inside a mapped segment (a stale
 * or damaged row) or does not start on a character.
 */
fun resolveMatch(
  chunkText: String,
  segments: List<MappingSegment>,
  href: String,
  mediaType: String?,
  matchStart: Int,
  matchEnd: Int,
  contextChars: Int = 50,
): MatchTarget? {
  if (matchStart < 0 || matchEnd > chunkText.length || matchEnd < matchStart) return null
  if (matchStart < chunkText.length && Character.isLowSurrogate(chunkText[matchStart])) return null
  val segment = segments.firstOrNull { matchStart >= it.charStart && matchStart < it.charEnd } ?: return null
  // The space that joins this element to the next is stored with it, but is not part of the element's own text.
  var segEnd = minOf(segment.charEnd, chunkText.length)
  while (segEnd > segment.charStart && chunkText[segEnd - 1].isWhitespace()) segEnd--
  if (matchStart >= segEnd) return null
  val end = matchEnd.coerceAtMost(segEnd)

  var beforeStart = maxOf(segment.charStart, matchStart - contextChars)
  if (beforeStart > segment.charStart && Character.isLowSurrogate(chunkText[beforeStart])) beforeStart--
  var afterEnd = minOf(segEnd, end + contextChars)
  if (afterEnd < segEnd && Character.isLowSurrogate(chunkText[afterEnd])) afterEnd++
  return MatchTarget(
    locatorJson = slimLocatorJson(href, mediaType, segment.progression),
    before = chunkText.substring(beforeStart, matchStart),
    highlight = chunkText.substring(matchStart, end),
    after = chunkText.substring(end, afterEnd),
    chunkCharStart = matchStart,
  )
}
