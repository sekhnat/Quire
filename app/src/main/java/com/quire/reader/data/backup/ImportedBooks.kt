package com.quire.reader.data.backup

import java.io.File
import java.io.InputStream

/**
 * How a restore decides that a book it brings back is already in the imported-books folder. Only identical bytes count:
 * two different EPUBs can share a name and a size, and a book is never deleted on a guess.
 */
internal object ImportedBooks {
  private const val BUFFER = 1 shl 16

  /** True when [a] and [b] hold the same bytes. Files of different lengths are never read; equal ones are read to the first difference. */
  fun sameContents(a: File, b: File): Boolean {
    if (a.length() != b.length()) return false
    a.inputStream().use { inA ->
      b.inputStream().use { inB ->
        val bufA = ByteArray(BUFFER)
        val bufB = ByteArray(BUFFER)
        while (true) {
          val n = fill(inA, bufA)
          if (n != fill(inB, bufB)) return false
          if (n == 0) return true
          if (!bufA.contentEquals(bufB)) return false
        }
      }
    }
  }

  /** Reads until [buffer] is full or the stream ends; returns how much was read. */
  private fun fill(input: InputStream, buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
      val n = input.read(buffer, total, buffer.size - total)
      if (n < 0) break
      total += n
    }
    return total
  }

  /**
   * Puts [incoming] into [dir] as [name], or as `name (2)`, `name (3)`… when that is taken by a different book. Returns
   * where it went, or null when one of those names already holds the same bytes; [incoming] is then deleted. Checking
   * every numbered name keeps a repeated restore of the same backup from adding another copy each time.
   */
  fun placeIncoming(dir: File, name: String, incoming: File): File? {
    val first = File(dir, name)
    var n = 1
    while (true) {
      val candidate = if (n == 1) first else numbered(first, " ($n)")
      when {
        !candidate.exists() -> {
          if (!incoming.renameTo(candidate)) throw BackupException("Couldn't copy $name")
          return candidate
        }
        candidate.isFile && sameContents(candidate, incoming) -> { incoming.delete(); return null }
      }
      n++
    }
  }

  /** [file]'s name with [suffix] before its extension: `Emma.epub` → `Emma (2).epub`. */
  fun numbered(file: File, suffix: String): File {
    val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
    return File(file.parentFile, "${file.nameWithoutExtension}$suffix$ext")
  }
}
