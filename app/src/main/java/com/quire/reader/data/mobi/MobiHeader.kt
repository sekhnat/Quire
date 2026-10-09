package com.quire.reader.data.mobi

import java.nio.charset.Charset

/**
 * The header record of one MOBI section: the PalmDOC header, the MOBI header and its EXTH metadata. A joint file
 * (MOBI 6 for old Kindles plus KF8 after a `BOUNDARY` record) has two; [start] is the record this one sits in, and every
 * record index below is absolute (already offset by [start]) or [NULL_INDEX].
 */
internal class MobiHeader(record: ByteArray, val start: Int) {
  val compression: Int = record.u16(0)
  val textLength: Long = record.u32(4)
  val textRecordCount: Int = record.u16(8)
  val encryption: Int = record.u16(12)
  private val headerLength: Int
  val version: Int
  val charset: Charset
  val title: String
  val firstResource: Long
  val huffRecord: Long
  val huffCount: Int
  val extraFlags: Int
  val ncxIndex: Long
  val fragmentIndex: Long
  val skeletonIndex: Long
  val fdstIndex: Long
  val exth: Exth

  init {
    if (record.size < 24 || String(record, 16, 4, Charsets.ISO_8859_1) != "MOBI") throw MobiException("no MOBI header")
    headerLength = record.u32(20).toInt()
    val end = 16 + headerLength
    fun field(at: Int) = record.u32OrNull(at, end)
    fun index(at: Int) = field(at).let { if (it == NULL_INDEX) it else it + start }
    charset = when (field(28)) { 65001L -> Charsets.UTF_8; else -> CP1252 }
    version = field(36).let { if (it == NULL_INDEX) 0 else it.toInt() }
    firstResource = index(0x6C)
    huffRecord = index(0x70)
    huffCount = field(0x74).let { if (it == NULL_INDEX) 0 else it.toInt() }
    val exthFlags = field(0x80)
    extraFlags = if (version >= 5 && end >= 0xF4) record.u16(0xF2) else 0
    ncxIndex = index(0xF4)
    val kf8 = version >= 8 && end >= 0xF8 + 16
    fragmentIndex = if (kf8) index(0xF8) else NULL_INDEX
    skeletonIndex = if (kf8) index(0xFC) else NULL_INDEX
    // A one-flow book may carry a garbage FDST index; it is only meaningful with more than one flow.
    fdstIndex = if (kf8 && field(0xC4).let { it != NULL_INDEX && it > 1 }) index(0xC0) else NULL_INDEX
    exth = if (exthFlags != NULL_INDEX && exthFlags and 0x40L != 0L) Exth.parse(record, end, charset) else Exth(emptyMap())
    val nameOffset = field(0x54)
    val nameLength = field(0x58)
    title = exth.string(Exth.UPDATED_TITLE)
      ?: if (nameOffset != NULL_INDEX && nameLength != NULL_INDEX && nameOffset + nameLength <= record.size) {
        String(record, nameOffset.toInt(), nameLength.toInt(), charset).trim()
      } else ""
  }

  val isKf8: Boolean get() = version >= 8 && skeletonIndex != NULL_INDEX && fragmentIndex != NULL_INDEX

  companion object {
    val CP1252: Charset = Charset.forName("windows-1252")
  }
}

/** EXTH metadata records by type; a type may repeat (one record per author or subject). */
internal class Exth(private val records: Map<Int, List<ByteArray>>, private val charset: Charset = Charsets.UTF_8) {
  fun strings(type: Int): List<String> = records[type].orEmpty().map { String(it, charset).trim() }.filter { it.isNotEmpty() }
  fun string(type: Int): String? = strings(type).firstOrNull()
  fun int(type: Int): Long? = records[type]?.firstOrNull()?.takeIf { it.size == 4 }?.u32(0)

  companion object {
    const val AUTHOR = 100
    const val PUBLISHER = 101
    const val DESCRIPTION = 103
    const val ISBN = 104
    const val SUBJECT = 105
    const val PUBLISHED = 106
    const val ASIN = 113
    const val KF8_BOUNDARY = 121
    const val FIXED_LAYOUT = 122
    const val KF8_COVER_URI = 129
    const val COVER_OFFSET = 201
    const val UPDATED_TITLE = 503
    const val ASIN_ALT = 504
    const val LANGUAGE = 524
    const val PAGE_DIRECTION = 527

    fun parse(record: ByteArray, at: Int, charset: Charset): Exth {
      if (at + 12 > record.size || String(record, at, 4, Charsets.ISO_8859_1) != "EXTH") return Exth(emptyMap())
      val count = record.u32(at + 8)
      val out = HashMap<Int, MutableList<ByteArray>>()
      var pos = at + 12
      for (i in 0 until count) {
        if (pos + 8 > record.size) break
        val type = record.u32(pos).toInt()
        val len = record.u32(pos + 4).toInt()
        if (len < 8 || pos + len > record.size) break
        out.getOrPut(type) { ArrayList() } += record.copyOfRange(pos + 8, pos + len)
        pos += len
      }
      return Exth(out, charset)
    }
  }
}
