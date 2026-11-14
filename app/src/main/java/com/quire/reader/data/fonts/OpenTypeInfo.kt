package com.quire.reader.data.fonts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/** What Quire needs to know about a font file to offer it as a reading font. */
data class OpenTypeInfo(
  /** The typographic family (name ID 16), else the family (ID 1). */
  val family: String,
  val italic: Boolean,
  /** The weight, or the range a variable font's `wght` axis covers. */
  val weights: IntRange,
  /** "ttf" for TrueType outlines, "otf" for CFF. */
  val extension: String,
)

/** Why a file was not taken. */
enum class FontRejection(val reason: String) {
  Collection("font collections"),
  Woff("web fonts"),
  TooLarge("over 25 MB"),
  Unreadable("not a font Quire can read"),
}

/**
 * A small, pure reader for TrueType and OpenType files: the sfnt header, then the `name`, `OS/2` and `fvar` tables. It
 * reads only what it needs and checks every offset against the file, so a damaged or hostile file is rejected, never a
 * crash.
 */
object OpenTypeReader {
  const val MAX_BYTES = 25 * 1024 * 1024

  fun read(bytes: ByteArray): Result<OpenTypeInfo> = try {
    Result.success(parse(bytes))
  } catch (e: FontException) {
    Result.failure(e)
  } catch (e: RuntimeException) {
    // Out-of-bounds reads on a truncated table end up here.
    Result.failure(FontException(FontRejection.Unreadable))
  }

  class FontException(val rejection: FontRejection) : Exception(rejection.reason)

  private fun parse(bytes: ByteArray): OpenTypeInfo {
    if (bytes.size > MAX_BYTES) throw FontException(FontRejection.TooLarge)
    if (bytes.size < 12) throw FontException(FontRejection.Unreadable)
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val extension = when (b.getInt(0)) {
      0x00010000, TRUE -> "ttf"
      OTTO -> "otf"
      TTCF -> throw FontException(FontRejection.Collection)
      WOFF, WOFF2 -> throw FontException(FontRejection.Woff)
      else -> throw FontException(FontRejection.Unreadable)
    }
    val numTables = b.getShort(4).toInt() and 0xFFFF
    if (numTables == 0 || 12 + numTables * 16 > bytes.size) throw FontException(FontRejection.Unreadable)
    val tables = HashMap<String, IntRange>()
    for (i in 0 until numTables) {
      val rec = 12 + i * 16
      val tag = String(bytes, rec, 4, Charsets.ISO_8859_1)
      val offset = b.getInt(rec + 8)
      val length = b.getInt(rec + 12)
      if (offset < 0 || length < 0 || offset.toLong() + length > bytes.size) throw FontException(FontRejection.Unreadable)
      tables[tag] = offset until offset + length
    }
    val name = tables["name"] ?: throw FontException(FontRejection.Unreadable)
    val family = familyName(b, name) ?: throw FontException(FontRejection.Unreadable)

    var italic = false
    var weight = 400
    tables["OS/2"]?.let { os2 ->
      if (os2.last - os2.first + 1 >= 64) {
        weight = (b.getShort(os2.first + 4).toInt() and 0xFFFF).coerceIn(1, 1000)
        italic = (b.getShort(os2.first + 62).toInt() and 0x1) != 0
      }
    }
    tables["head"]?.let { head -> if (head.last - head.first + 1 >= 46 && (b.getShort(head.first + 44).toInt() and 0x2) != 0) italic = true }
    val weights = tables["fvar"]?.let { wghtAxis(b, it) } ?: (weight..weight)
    return OpenTypeInfo(family, italic, weights, extension)
  }

  /** The `wght` axis range of an `fvar` table, or null when the font has no weight axis. */
  private fun wghtAxis(b: ByteBuffer, fvar: IntRange): IntRange? {
    val base = fvar.first
    val axesOffset = b.getShort(base + 4).toInt() and 0xFFFF
    val axisCount = b.getShort(base + 8).toInt() and 0xFFFF
    val axisSize = b.getShort(base + 10).toInt() and 0xFFFF
    if (axisSize < 20) return null
    for (i in 0 until axisCount) {
      val at = base + axesOffset + i * axisSize
      if (at + 20 > fvar.last + 1) return null
      if (b.getInt(at) == WGHT) {
        val min = b.getInt(at + 4) shr 16
        val max = b.getInt(at + 12) shr 16
        return min.coerceIn(1, 1000)..max.coerceIn(1, 1000)
      }
    }
    return null
  }

  /** Name ID 16, else 1: Windows Unicode English first, then any Windows Unicode, then Mac Roman. */
  private fun familyName(b: ByteBuffer, table: IntRange): String? {
    val base = table.first
    val count = b.getShort(base + 2).toInt() and 0xFFFF
    val strings = base + (b.getShort(base + 4).toInt() and 0xFFFF)
    data class Rec(val platform: Int, val encoding: Int, val language: Int, val id: Int, val text: String)
    val recs = (0 until count).mapNotNull { i ->
      val r = base + 6 + i * 12
      if (r + 12 > table.last + 1) return@mapNotNull null
      val platform = b.getShort(r).toInt() and 0xFFFF
      val encoding = b.getShort(r + 2).toInt() and 0xFFFF
      val language = b.getShort(r + 4).toInt() and 0xFFFF
      val id = b.getShort(r + 6).toInt() and 0xFFFF
      if (id != 1 && id != 16) return@mapNotNull null
      val length = b.getShort(r + 8).toInt() and 0xFFFF
      val offset = strings + (b.getShort(r + 10).toInt() and 0xFFFF)
      if (offset + length > b.limit()) return@mapNotNull null
      val raw = ByteArray(length).also { b.duplicate().apply { position(offset) }.get(it) }
      val text = when {
        platform == 3 || platform == 0 -> String(raw, Charsets.UTF_16BE)
        platform == 1 && encoding == 0 -> String(raw, Charset.forName("x-MacRoman"))
        else -> return@mapNotNull null
      }.trim().filter { !it.isISOControl() }
      if (text.isEmpty()) null else Rec(platform, encoding, language, id, text)
    }
    for (id in listOf(16, 1)) {
      val withId = recs.filter { it.id == id }
      (withId.firstOrNull { it.platform == 3 && it.language == 0x409 } ?: withId.firstOrNull { it.platform == 3 || it.platform == 0 } ?: withId.firstOrNull())
        ?.let { return it.text.take(MAX_NAME) }
    }
    return null
  }

  private const val MAX_NAME = 80
  private const val TRUE = 0x74727565 // 'true'
  private const val OTTO = 0x4F54544F // 'OTTO'
  private const val TTCF = 0x74746366 // 'ttcf'
  private const val WOFF = 0x774F4646 // 'wOFF'
  private const val WOFF2 = 0x774F4632 // 'wOF2'
  private const val WGHT = 0x77676874 // 'wght'
}
