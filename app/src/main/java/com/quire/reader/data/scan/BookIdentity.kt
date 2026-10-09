package com.quire.reader.data.scan

import com.quire.reader.data.mobi.MobiBook
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
  /**
   * The EPUB's own `unique-identifier`, or a MOBI's ASIN or ISBN. Weak: tools reuse placeholder ids and editions share
   * ISBNs.
   */
  val epubUid: String?,
  /** File size plus a hash of the file's end (all of it for a MOBI); see [fingerprint]. Equal for byte-identical copies. */
  val fingerprint: String?,
) {
  companion object {
    /** Bytes hashed from the end of the file. */
    const val TAIL_BYTES = 64 * 1024

    /** Reads every key of [book]. Never throws; a key that cannot be read is null. */
    fun read(book: File): BookIdentity =
      if (BookFormats.isMobi(book)) BookIdentity(calibreUuid(book), mobiUid(book), fullFingerprint(book))
      else BookIdentity(calibreUuid(book), epubUid(book), fingerprint(book))

    /**
     * The uuid of the Calibre `metadata.opf` next to [book], when [book] is the only book in its folder (counting a book
     * kept in several formats once): a `metadata.opf` beside several books is not Calibre's (it keeps one book per
     * folder) and must not give them all one identity.
     */
    fun calibreUuid(book: File): String? = runCatching {
      val dir = book.parentFile ?: return null
      val opf = File(dir, "metadata.opf")
      if (!opf.isFile) return null
      val books = BookFormats.preferred(dir.listFiles { f -> f.isFile }.orEmpty().asList())
      if (books.size != 1) return null
      opf.inputStream().use(OpfParser::identifiers)?.uuid
    }.getOrNull()

    /** A MOBI's ASIN, else its ISBN, from its EXTH header. */
    fun mobiUid(book: File): String? = runCatching { MobiBook.open(book).use { it.metadata.uniqueId?.take(MAX_ID_LENGTH) } }.getOrNull()

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

    /**
     * `size:sha1` of the whole file. A MOBI's tail holds images and indexes, its text sits in the middle, and no table at
     * the end sums up the rest, so only the whole file tells two versions apart.
     */
    fun fullFingerprint(file: File): String? = runCatching {
      val digest = MessageDigest.getInstance("SHA-1")
      val size = file.length()
      file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
      }
      "$size:" + digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private const val MAX_ID_LENGTH = 256
  }
}
