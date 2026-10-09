package com.quire.reader.data.mobi

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * EPUB copies of MOBI and AZW3 books, which Readium cannot open itself. A copy is made the first time a book is opened
 * (read, indexed, its notes exported), named after the source's path, size and modified time and the converter version,
 * so a changed book or a newer converter makes a fresh one. Copies are kept least recently used first within [maxBytes];
 * the directory may live in the cache, since anything missing is simply converted again.
 */
class ConvertedBooks(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
  private val locks = ConcurrentHashMap<String, Any>()

  /** The EPUB copy of [source], converting it first if there is none. Blocking; throws [MobiException] or [IOException]. */
  fun epubFor(source: File): File {
    val key = keyFor(source.absolutePath)
    val target = copyOf(source)
    synchronized(locks.getOrPut(key) { Any() }) {
      if (target.isFile) {
        target.setLastModified(System.currentTimeMillis())
        return target
      }
      if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
      val tmp = File(dir, target.name + ".tmp")
      try {
        MobiBook.open(source).use { book -> tmp.outputStream().buffered().use(book::writeEpub) }
        if (!tmp.renameTo(target)) throw IOException("cannot write ${target.name}")
      } finally {
        tmp.delete()
      }
      // Copies of an earlier version of this file, or by an earlier converter.
      dir.listFiles { f -> f.name.startsWith("$key-") && f.name != target.name && !f.name.endsWith(".tmp") }?.forEach { it.delete() }
      trim(target)
      return target
    }
  }

  /** The cached copy of [source] when there is one, without converting it or counting it as used. */
  fun cached(source: File): File? = copyOf(source).takeIf { it.isFile }

  /**
   * A fresh EPUB copy of [source] outside the cache, for a single read that should not push the books being read out of it
   * (the indexer's pass over the library). The caller deletes it. Blocking; throws [MobiException] or [IOException].
   */
  fun temporary(source: File): File {
    val once = File(dir, ONCE_DIR)
    if (!once.isDirectory && !once.mkdirs()) throw IOException("cannot create $once")
    val now = System.currentTimeMillis()
    // Copies left behind by a process that died before deleting them.
    once.listFiles().orEmpty().filter { now - it.lastModified() > STALE_TMP_MILLIS }.forEach { it.delete() }
    val target = File.createTempFile("once-", ".epub", once)
    try {
      MobiBook.open(source).use { book -> target.outputStream().buffered().use(book::writeEpub) }
      return target
    } catch (e: Exception) {
      target.delete()
      throw e
    }
  }

  private fun copyOf(source: File) =
    File(dir, "${keyFor(source.absolutePath)}-${source.length()}-${source.lastModified()}-v${MobiBook.CONVERTER_VERSION}.epub")

  /** Deletes the least recently used copies until the rest fit in [maxBytes]; [keep] always stays. */
  private fun trim(keep: File) {
    val now = System.currentTimeMillis()
    val files = dir.listFiles().orEmpty().filter { it.isFile }
    // A conversion that died with the process leaves its temporary file behind.
    files.filter { it.name.endsWith(".tmp") && now - it.lastModified() > STALE_TMP_MILLIS }.forEach { it.delete() }
    val copies = files.filter { it.name.endsWith(".epub") }.sortedByDescending { it.lastModified() }
    var total = 0L
    for (f in copies) {
      total += f.length()
      if (total > maxBytes && f != keep) { total -= f.length(); f.delete() }
    }
  }

  private fun keyFor(path: String) = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

  companion object {
    const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024
    private const val STALE_TMP_MILLIS = 60 * 60 * 1000L
    private const val ONCE_DIR = "once"
  }
}
