package com.quire.reader.data.mobi

import java.nio.charset.Charset

/** One entry of a MOBI index: its key and its tag values (tag → values, in the order they were written). */
internal class IndexEntry(val key: String, val tags: Map<Int, List<Long>>) {
  fun value(tag: Int, i: Int = 0): Long? = tags[tag]?.getOrNull(i)
}

/** A MOBI index (`INDX` records with a `TAGX` table, plus `CNCX` string records) as entries in order, and its strings. */
internal class MobiIndex(val entries: List<IndexEntry>, private val cncx: List<ByteArray>, private val charset: Charset) {
  /** The CNCX string at [offset]: record `offset / 0x10000`, then a variable-width length and the bytes. */
  fun string(offset: Long?): String? {
    if (offset == null) return null
    val record = cncx.getOrNull((offset ushr 16).toInt()) ?: return null
    val pos = (offset and 0xFFFF).toInt()
    if (pos >= record.size) return null
    val (length, consumed) = forwardVwi(record, pos)
    val start = pos + consumed
    if (length < 0 || start + length > record.size) return null
    return String(record, start, length.toInt(), charset)
  }

  private class Tag(val tag: Int, val valuesPerEntry: Int, val mask: Int, val endFlag: Boolean)

  companion object {
    /** Reads the index whose header record is [index]; an unreadable index is empty rather than fatal. */
    fun read(db: PalmDatabase, index: Long, charset: Charset): MobiIndex {
      if (index == NULL_INDEX || index >= db.recordCount) return MobiIndex(emptyList(), emptyList(), charset)
      return runCatching { parse(db, index.toInt(), charset) }.getOrElse { MobiIndex(emptyList(), emptyList(), charset) }
    }

    private fun parse(db: PalmDatabase, at: Int, charset: Charset): MobiIndex {
      val header = db.record(at)
      checkSignature(header)
      val recordCount = header.u32(24).toInt()
      val cncxCount = header.u32(52).toInt()
      // The TAGX table follows the header; its offset is the header length.
      val tagxAt = header.u32(4).toInt()
      if (tagxAt + 12 > header.size || String(header, tagxAt, 4, Charsets.ISO_8859_1) != "TAGX") throw MobiException("index without TAGX")
      val tagxEnd = tagxAt + header.u32(tagxAt + 4).toInt()
      val controlBytes = header.u32(tagxAt + 8).toInt()
      val tags = (tagxAt + 12 until minOf(tagxEnd, header.size) step 4).map { Tag(header.u8(it), header.u8(it + 1), header.u8(it + 2), header.u8(it + 3) == 1) }
      val cncx = (0 until cncxCount).mapNotNull { i -> (at + recordCount + 1 + i).takeIf { it < db.recordCount }?.let(db::record) }

      val entries = ArrayList<IndexEntry>()
      for (r in at + 1..at + recordCount) {
        if (r >= db.recordCount) break
        val data = db.record(r)
        checkSignature(data)
        val idxt = data.u32(20).toInt()
        val count = data.u32(24).toInt()
        if (idxt + 4 + count * 2 > data.size) continue
        val starts = IntArray(count + 1) { if (it < count) data.u16(idxt + 4 + it * 2) else idxt }
        for (j in 0 until count) {
          val start = starts[j]
          val end = starts[j + 1]
          if (start >= end || end > data.size) continue
          val keyLength = data.u8(start)
          if (start + 1 + keyLength > end) continue
          val key = String(data, start + 1, keyLength, Charsets.ISO_8859_1)
          entries += IndexEntry(key, tagMap(data, start + 1 + keyLength, end, controlBytes, tags))
        }
      }
      return MobiIndex(entries, cncx, charset)
    }

    private fun checkSignature(record: ByteArray) {
      if (record.size < 28 || String(record, 0, 4, Charsets.ISO_8859_1) != "INDX") throw MobiException("not an INDX record")
    }

    /** The tag values of one entry, starting at its control bytes. */
    private fun tagMap(data: ByteArray, from: Int, end: Int, controlByteCount: Int, tags: List<Tag>): Map<Int, List<Long>> {
      class Present(val tag: Int, val valueCount: Int?, val valueBytes: Long?, val valuesPerEntry: Int)
      var control = 0
      var pos = from + controlByteCount
      val present = ArrayList<Present>()
      for (t in tags) {
        if (t.endFlag) { control++; continue }
        if (from + control >= end || t.mask == 0) continue
        var value = data.u8(from + control) and t.mask
        if (value == 0) continue
        if (value == t.mask && Integer.bitCount(t.mask) > 1) {
          // Every bit of a multi-bit mask set: a byte count for the values follows instead of a value count.
          val (bytes, consumed) = forwardVwi(data, pos)
          pos += consumed
          present += Present(t.tag, null, bytes, t.valuesPerEntry)
        } else {
          var mask = t.mask
          while (mask and 1 == 0) { mask = mask ushr 1; value = value ushr 1 }
          present += Present(t.tag, value, null, t.valuesPerEntry)
        }
      }
      val out = HashMap<Int, List<Long>>()
      for (p in present) {
        val values = ArrayList<Long>()
        if (p.valueCount != null) {
          repeat(p.valueCount * p.valuesPerEntry) {
            if (pos >= end) return@repeat
            val (v, consumed) = forwardVwi(data, pos)
            pos += consumed
            values += v
          }
        } else {
          var used = 0L
          while (used < p.valueBytes!! && pos < end) {
            val (v, consumed) = forwardVwi(data, pos)
            pos += consumed
            used += consumed
            values += v
          }
        }
        out[p.tag] = values
      }
      return out
    }

    /** A forward variable-width integer: 7 bits per byte, most significant first, the last byte flagged by its top bit. */
    fun forwardVwi(data: ByteArray, at: Int): Pair<Long, Int> {
      var value = 0L
      var i = at
      while (i < data.size) {
        val b = data.u8(i++)
        value = (value shl 7) or (b and 0x7F).toLong()
        if (b and 0x80 != 0 || i - at >= 9) break
      }
      return value to (i - at)
    }
  }
}
