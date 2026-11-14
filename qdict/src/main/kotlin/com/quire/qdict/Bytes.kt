package com.quire.qdict

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A damaged or unexpected `.qdict` file. Readers turn every malformed input into this, never into a crash. */
class QdictException(message: String) : Exception(message)

/** A growing byte buffer with the format's varints and strings. */
internal class Out {
  private val buf = ByteArrayOutputStream()
  val size: Int get() = buf.size()

  fun byte(v: Int) = buf.write(v)
  fun bytes(b: ByteArray) = buf.write(b)

  fun varint(value: Int) {
    require(value >= 0)
    var v = value
    while (v >= 0x80) { buf.write((v and 0x7F) or 0x80); v = v ushr 7 }
    buf.write(v)
  }

  fun string(s: String) { val b = s.toByteArray(Charsets.UTF_8); varint(b.size); bytes(b) }
  fun u32(v: Int) { byte(v ushr 24); byte(v ushr 16 and 0xFF); byte(v ushr 8 and 0xFF); byte(v and 0xFF) }
  fun toByteArray(): ByteArray = buf.toByteArray()
}

/** Reads what [Out] writes, checking every length against the data. */
internal class In(private val b: ByteArray, var pos: Int = 0, private val end: Int = b.size) {
  val atEnd: Boolean get() = pos >= end

  fun byte(): Int { if (pos >= end) throw QdictException("truncated"); return b[pos++].toInt() and 0xFF }

  fun varint(): Int {
    var result = 0
    var shift = 0
    while (true) {
      val x = byte()
      result = result or ((x and 0x7F) shl shift)
      if (x < 0x80) break
      shift += 7
      if (shift > 28) throw QdictException("varint too long")
    }
    if (result < 0) throw QdictException("negative varint")
    return result
  }

  fun bytes(n: Int): ByteArray {
    if (n < 0 || n > end - pos) throw QdictException("truncated")
    return b.copyOfRange(pos, pos + n).also { pos += n }
  }

  fun string(): String = String(bytes(varint()), Charsets.UTF_8)

  fun u32(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
}

/** How blocks are compressed. Only [None] and [Deflate] are read by the app; the converter measures the others. */
enum class Codec(val id: Int) {
  None(0),
  /** Raw Deflate at level 9, with a shared preset dictionary when the file has one. */
  Deflate(1);

  companion object {
    fun of(id: Int): Codec = entries.firstOrNull { it.id == id } ?: throw QdictException("unknown codec $id")
  }
}

internal object Blocks {
  fun compress(codec: Codec, data: ByteArray, preset: ByteArray?): ByteArray = when (codec) {
    Codec.None -> data
    Codec.Deflate -> {
      val d = Deflater(Deflater.BEST_COMPRESSION, true)
      try {
        if (preset != null) d.setDictionary(preset)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val chunk = ByteArray(16 * 1024)
        while (!d.finished()) out.write(chunk, 0, d.deflate(chunk))
        out.toByteArray()
      } finally {
        d.end()
      }
    }
  }

  /** Decompresses a block of [rawSize] bytes; anything that does not come out at exactly that size is damaged. */
  fun decompress(codec: Codec, data: ByteArray, rawSize: Int, preset: ByteArray?): ByteArray = when (codec) {
    Codec.None -> data.also { if (it.size != rawSize) throw QdictException("block size mismatch") }
    Codec.Deflate -> {
      val inf = Inflater(true)
      try {
        if (preset != null) inf.setDictionary(preset)
        // Raw Inflater needs one extra byte past the end to finish cleanly.
        inf.setInput(data + 0)
        val out = ByteArray(rawSize)
        var n = 0
        while (n < rawSize) {
          val got = inf.inflate(out, n, rawSize - n)
          if (got == 0) {
            if (inf.needsDictionary() || inf.needsInput() || inf.finished()) break
          }
          n += got
        }
        if (n != rawSize) throw QdictException("block inflated to $n bytes, expected $rawSize")
        out
      } catch (e: java.util.zip.DataFormatException) {
        throw QdictException("damaged block: ${e.message}")
      } finally {
        inf.end()
      }
    }
  }
}
