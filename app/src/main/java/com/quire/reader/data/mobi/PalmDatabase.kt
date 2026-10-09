package com.quire.reader.data.mobi

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** A MOBI file that cannot be read: not a MOBI, damaged, or locked with DRM. */
class MobiException(message: String) : Exception(message)

/**
 * The Palm database a MOBI file is stored in: a 78-byte header, then a table of record offsets. Records are read on
 * demand, so a book's images are only loaded when the converter asks for them.
 */
internal class PalmDatabase private constructor(private val raf: RandomAccessFile, private val offsets: LongArray, private val length: Long) : Closeable {
  val recordCount: Int get() = offsets.size

  fun record(index: Int): ByteArray {
    if (index !in offsets.indices) throw MobiException("record $index out of range")
    val start = offsets[index]
    val end = if (index + 1 < offsets.size) offsets[index + 1] else length
    val size = end - start
    if (size < 0 || size > MAX_RECORD_BYTES) throw MobiException("record $index is damaged")
    return ByteArray(size.toInt()).also { synchronized(raf) { raf.seek(start); raf.readFully(it) } }
  }

  override fun close() = raf.close()

  companion object {
    /** Comfortably above any real record (text records are 4 KiB, images rarely pass a few MiB). */
    private const val MAX_RECORD_BYTES = 64L * 1024 * 1024
    private const val HEADER_BYTES = 78

    /** Whether [file] starts like a MOBI (type `BOOK`, creator `MOBI`), whatever its name. */
    fun isMobi(file: File): Boolean = runCatching {
      RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < HEADER_BYTES) return false
        val tag = ByteArray(8)
        raf.seek(60)
        raf.readFully(tag)
        String(tag, Charsets.ISO_8859_1) == "BOOKMOBI"
      }
    }.getOrDefault(false)

    fun open(file: File): PalmDatabase {
      val raf = RandomAccessFile(file, "r")
      try {
        val length = raf.length()
        if (length < HEADER_BYTES) throw MobiException("too short to be a MOBI")
        val header = ByteArray(HEADER_BYTES)
        raf.readFully(header)
        if (String(header, 60, 8, Charsets.ISO_8859_1) != "BOOKMOBI") throw MobiException("not a MOBI file")
        val count = header.u16(76)
        if (count < 2) throw MobiException("no records")
        val table = ByteArray(count * 8)
        raf.readFully(table)
        val offsets = LongArray(count) { table.u32(it * 8) }
        for (i in offsets.indices) {
          if (offsets[i] < HEADER_BYTES + table.size || offsets[i] > length || (i > 0 && offsets[i] < offsets[i - 1])) throw MobiException("record table is damaged")
        }
        return PalmDatabase(raf, offsets, length)
      } catch (e: Exception) {
        raf.close()
        throw e
      }
    }
  }
}

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
internal fun ByteArray.u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
internal fun ByteArray.u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

/** A big-endian 32-bit field, or [NULL_INDEX] when [at] lies past [limit] (a header too short to carry it). */
internal fun ByteArray.u32OrNull(at: Int, limit: Int = size): Long = if (at + 4 <= minOf(limit, size)) u32(at) else NULL_INDEX

/** The value MOBI uses for "no such record". */
internal const val NULL_INDEX = 0xFFFFFFFFL
