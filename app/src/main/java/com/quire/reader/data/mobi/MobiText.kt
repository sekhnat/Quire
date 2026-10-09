package com.quire.reader.data.mobi

import java.io.ByteArrayOutputStream

/** The decompressed text of a MOBI section: every text record, its trailing entries stripped, joined. */
internal fun readText(db: PalmDatabase, header: MobiHeader): ByteArray {
  if (header.encryption != 0) throw MobiException("protected by DRM")
  val decode: (ByteArray, Int) -> ByteArray = when (header.compression) {
    1 -> { data, len -> data.copyOf(len) }
    2 -> PalmDoc::decompress
    17480 -> {
      if (header.huffRecord == NULL_INDEX || header.huffCount < 2) throw MobiException("missing Huffman tables")
      val first = header.huffRecord.toInt()
      HuffCdic(db.record(first), (first + 1 until first + header.huffCount).map(db::record))::decompress
    }
    else -> throw MobiException("unknown compression ${header.compression}")
  }
  val out = ByteArrayOutputStream(header.textLength.coerceIn(0, MAX_TEXT_BYTES.toLong()).toInt())
  for (i in 1..header.textRecordCount) {
    val record = db.record(header.start + i)
    out.write(decode(record, record.size - trailingSize(record, header.extraFlags)))
    if (out.size() > MAX_TEXT_BYTES) throw MobiException("text too large")
  }
  return out.toByteArray()
}

/** Comfortably above any real book; a damaged length or record count must not exhaust memory. */
private const val MAX_TEXT_BYTES = 256 * 1024 * 1024

/**
 * Bytes at the end of a text record that are not text: one entry per bit set above bit 0 of [extraFlags], each ending in
 * its own size, then (bit 0) the bytes of a multibyte character that continues in the next record.
 */
internal fun trailingSize(record: ByteArray, extraFlags: Int): Int {
  var size = record.size
  var num = 0
  var flags = extraFlags ushr 1
  while (flags != 0) {
    if (flags and 1 != 0 && size - num > 0) {
      // The entry's size is a backward variable-width integer ending at its last byte.
      var end = size - num
      var shift = 0
      var value = 0
      while (end > 0) {
        val b = record.u8(end - 1)
        value = value or ((b and 0x7F) shl shift)
        shift += 7
        end--
        if (b and 0x80 != 0 || shift >= 28) break
      }
      num += value
    }
    flags = flags ushr 1
  }
  if (extraFlags and 1 != 0 && size - num > 0) num += (record.u8(size - num - 1) and 0x3) + 1
  return num.coerceIn(0, size)
}

/** PalmDOC's LZ77 variant. */
internal object PalmDoc {
  fun decompress(data: ByteArray, length: Int): ByteArray {
    // Nothing expands more than fivefold (a two-byte back-reference copies at most ten bytes).
    val buf = ByteArray(length * 5 + 16)
    var o = 0
    fun put(b: Int) {
      if (o == buf.size) throw MobiException("PalmDOC record expands too far")
      buf[o++] = b.toByte()
    }
    var i = 0
    while (i < length) {
      val c = data.u8(i++)
      when {
        c in 1..8 -> repeat(c) { if (i < length) put(data.u8(i++)) }
        c < 0x80 -> put(c)
        c >= 0xC0 -> { put(' '.code); put(c xor 0x80) }
        else -> {
          if (i >= length) break
          val pair = (c shl 8) or data.u8(i++)
          val distance = (pair ushr 3) and 0x7FF
          val n = (pair and 7) + 3
          if (distance == 0 || distance > o) continue
          repeat(n) { put(buf[o - distance].toInt()) }
        }
      }
    }
    return buf.copyOf(o)
  }
}

/** Mobipocket's Huffman coding against CDIC phrase dictionaries, used by some Kindle-store books. */
internal class HuffCdic(huff: ByteArray, cdics: List<ByteArray>) {
  private class Code(val length: Int, val terminal: Boolean, val max: Long)

  private val dict1: Array<Code>
  private val minCode = LongArray(33)
  private val maxCode = LongArray(33)
  private val phrases = ArrayList<ByteArray?>()
  private val literal = ArrayList<Boolean>()

  init {
    if (huff.size < 24 || String(huff, 0, 8, Charsets.ISO_8859_1) != "HUFF\u0000\u0000\u0000\u0018") throw MobiException("invalid HUFF record")
    val off1 = huff.u32(8).toInt()
    val off2 = huff.u32(12).toInt()
    if (off1 + 256 * 4 > huff.size || off2 + 64 * 4 > huff.size) throw MobiException("invalid HUFF record")
    dict1 = Array(256) {
      val v = huff.u32(off1 + it * 4)
      val length = (v and 0x1F).toInt()
      if (length == 0) throw MobiException("invalid HUFF record")
      Code(length, v and 0x80 != 0L, (((v ushr 8) + 1) shl (32 - length)) - 1)
    }
    for (len in 1..32) {
      minCode[len] = huff.u32(off2 + (len - 1) * 8) shl (32 - len)
      maxCode[len] = ((huff.u32(off2 + (len - 1) * 8 + 4) + 1) shl (32 - len)) - 1
    }
    for (cdic in cdics) {
      if (cdic.size < 16 || String(cdic, 0, 8, Charsets.ISO_8859_1) != "CDIC\u0000\u0000\u0000\u0010") throw MobiException("invalid CDIC record")
      val total = cdic.u32(8)
      val bits = cdic.u32(12).toInt()
      val n = minOf(1L shl bits, total - phrases.size).toInt()
      for (k in 0 until n) {
        val off = cdic.u16(16 + k * 2)
        val header = cdic.u16(16 + off)
        val len = header and 0x7FFF
        phrases += cdic.copyOfRange(18 + off, minOf(cdic.size, 18 + off + len))
        literal += header and 0x8000 != 0
      }
    }
  }

  fun decompress(data: ByteArray, length: Int): ByteArray = ByteArrayOutputStream(length * 4).also { unpack(data, length, it, 0) }.toByteArray()

  private fun unpack(data: ByteArray, length: Int, out: ByteArrayOutputStream, depth: Int) {
    if (depth > 32) throw MobiException("CDIC phrases nest too deep")
    // Codes are read 64 bits at a time, 32 bits apart, so the last read can run up to 12 bytes past the data.
    val src = data.copyOf(length + 16)
    var bitsLeft = length.toLong() * 8
    var pos = 0
    var x = src.u64(pos)
    var n = 32
    while (true) {
      if (n <= 0) {
        pos += 4
        x = src.u64(pos)
        n += 32
      }
      val code = (x ushr n) and 0xFFFFFFFFL
      val entry = dict1[(code ushr 24).toInt()]
      var len = entry.length
      var max = entry.max
      if (!entry.terminal) {
        while (len < 32 && code < minCode[len]) len++
        max = maxCode[len]
      }
      n -= len
      bitsLeft -= len
      if (bitsLeft < 0) break
      val r = ((max - code) ushr (32 - len)).toInt()
      if (r !in phrases.indices) throw MobiException("invalid Huffman code")
      var phrase = phrases[r] ?: throw MobiException("CDIC phrase refers to itself")
      if (!literal[r]) {
        phrases[r] = null
        val expanded = ByteArrayOutputStream()
        unpack(phrase, phrase.size, expanded, depth + 1)
        phrase = expanded.toByteArray()
        phrases[r] = phrase
        literal[r] = true
      }
      out.write(phrase)
    }
  }

  private fun ByteArray.u64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)
}
