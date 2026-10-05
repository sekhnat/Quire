package com.quire.reader.data.scan

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * What identifies a book apart from its path, so a file that Calibre renames or that the user moves keeps its reading
 * history. Each key is null when it could not be read.
 */
data class BookIdentity(
  /** Calibre's book uuid from the `metadata.opf` beside the file; survives title and author edits that rename the file. */
  val calibreUuid: String?,
  /** The EPUB's own `unique-identifier`. Weak: tools reuse placeholder ids and editions share ISBNs. */
  val epubUid: String?,
  /** File size plus a hash of the file's end; see [fingerprint]. Equal for byte-identical copies. */
  val fingerprint: String?,
) {
  companion object {
    /** Bytes hashed from the end of the file. */
    const val TAIL_BYTES = 64 * 1024

    /** Reads every key of [epub]. Never throws; a key that cannot be read is null. */
    fun read(epub: File): BookIdentity = BookIdentity(calibreUuid(epub), epubUid(epub), fingerprint(epub))

    /**
     * The uuid of the Calibre `metadata.opf` next to [epub], when [epub] is the only EPUB in its folder: a `metadata.opf`
     * beside several EPUBs is not Calibre's (it keeps one book per folder) and must not give them all one identity.
     */
    fun calibreUuid(epub: File): String? = runCatching {
      val dir = epub.parentFile ?: return null
      val opf = File(dir, "metadata.opf")
      if (!opf.isFile) return null
      val epubs = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.name.endsWith(".epub", ignoreCase = true) }.orEmpty()
      if (epubs.size != 1) return null
      opf.inputStream().use(OpfParser::identifiers)?.uuid
    }.getOrNull()

    /** The value of the identifier the package document's `unique-identifier` names, found through `META-INF/container.xml`. */
    fun epubUid(epub: File): String? = runCatching {
      ZipFile(epub).use { zip ->
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        val root = zip.getInputStream(container).use(OpfParser::xmlRoot) ?: return null
        val rootfiles = root.getElementsByTagNameNS("*", "rootfile")
        val opfPath = (0 until rootfiles.length).asSequence().map { (rootfiles.item(it) as org.w3c.dom.Element).getAttribute("full-path") }
          .firstOrNull { it.isNotEmpty() } ?: return null
        val opf = zip.getEntry(opfPath) ?: return null
        zip.getInputStream(opf).use(OpfParser::identifiers)?.uniqueId?.take(MAX_ID_LENGTH)
      }
    }.getOrNull()

    /**
     * `size:sha1` of the last [TAIL_BYTES] of the file. An EPUB is a ZIP, whose central directory sits at the end and
     * lists every entry's CRC-32 and sizes, so this changes whenever any entry changes, while costing a single small read.
     */
    fun fingerprint(file: File): String? = runCatching {
      RandomAccessFile(file, "r").use { raf ->
        val size = raf.length()
        val n = minOf(size, TAIL_BYTES.toLong()).toInt()
        val tail = ByteArray(n)
        raf.seek(size - n)
        raf.readFully(tail)
        "$size:" + MessageDigest.getInstance("SHA-1").digest(tail).joinToString("") { "%02x".format(it) }
      }
    }.getOrNull()

    private const val MAX_ID_LENGTH = 256
  }
}
