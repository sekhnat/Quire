package com.quire.reader.data.mobi

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Inflater

/** What a MOBI says about itself in its EXTH records, in place of an OPF. */
data class MobiMetadata(
  val title: String,
  val authors: List<String>,
  val publisher: String?,
  /** May hold HTML. */
  val description: String?,
  val subjects: List<String>,
  /** As written, usually ISO 8601. */
  val published: String?,
  val language: String?,
  val isbn: String?,
  val asin: String?,
) {
  /** The book's own identifier, as the converted EPUB's unique identifier carries it; null when it has none. */
  val uniqueId: String? get() = asin ?: isbn
}

/**
 * A DRM-free Mobipocket book: MOBI 6, KF8 (AZW3), or a joint file carrying both, in which case the KF8 half is read.
 * [metadata] and [coverImage] come straight from the header; [writeEpub] converts the whole book so Readium can open it.
 */
class MobiBook private constructor(private val db: PalmDatabase, private val mobi6: MobiHeader, private val kf8: MobiHeader?) : Closeable {
  private val main = kf8 ?: mobi6

  /** Whether the book is read from a KF8 section (AZW3, or the newer half of a joint MOBI). */
  val isKf8: Boolean get() = kf8 != null

  val metadata: MobiMetadata = run {
    val exth = main.exth
    MobiMetadata(
      title = main.title.ifEmpty { mobi6.title },
      authors = exth.strings(Exth.AUTHOR).flatMap { it.split('&') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
      publisher = exth.string(Exth.PUBLISHER),
      description = exth.string(Exth.DESCRIPTION),
      subjects = exth.strings(Exth.SUBJECT).distinct(),
      published = exth.string(Exth.PUBLISHED),
      language = exth.string(Exth.LANGUAGE),
      isbn = exth.string(Exth.ISBN),
      asin = exth.string(Exth.ASIN) ?: exth.string(Exth.ASIN_ALT),
    )
  }

  /**
   * The record `kindle:embed:0001` and `recindex="00001"` name. A joint file keeps its images once, in the MOBI 6 half,
   * where its KF8 header does not point; there they are found through the MOBI 6 header.
   */
  private val resourceBase: Long =
    if (kf8 != null && kf8.start > 0 && mobi6.firstResource != NULL_INDEX && mobi6.firstResource < kf8.start) mobi6.firstResource else main.firstResource

  internal val resources = ResourceTable(db, resourceBase)

  /** The cover image's bytes (JPEG, PNG, GIF or BMP), or null when the book names none. */
  fun coverImage(): ByteArray? = coverResource()?.bytes

  internal fun coverResource(): EpubFile? {
    val offset = main.exth.int(Exth.COVER_OFFSET) ?: mobi6.exth.int(Exth.COVER_OFFSET)
    val fromOffset = offset?.takeIf { it != NULL_INDEX && it < Int.MAX_VALUE }?.let { resources.image(it.toInt() + 1) }
    if (fromOffset != null) return fromOffset
    val uri = main.exth.string(Exth.KF8_COVER_URI) ?: return null
    return Regex("kindle:embed:([0-9A-Va-v]+)").find(uri)?.groupValues?.get(1)?.toIntOrNull(32)?.let(resources::image)
  }

  internal val fixedLayout: Boolean get() = main.exth.string(Exth.FIXED_LAYOUT).equals("true", ignoreCase = true)
  internal val rightToLeft: Boolean get() = main.exth.string(Exth.PAGE_DIRECTION).equals("rtl", ignoreCase = true)

  /** The identifier the converted EPUB carries: the book's own, or one derived from its header so it is stable. */
  internal val identifier: String by lazy {
    metadata.uniqueId ?: ("urn:quire:mobi:" + MessageDigest.getInstance("SHA-1").digest(db.record(main.start)).joinToString("") { "%02x".format(it) })
  }

  /** Converts the book into an EPUB written to [out]. A joint file whose KF8 half cannot be read falls back to its MOBI 6 half. */
  fun writeEpub(out: OutputStream) {
    val kf8 = kf8
    val content = when {
      kf8 == null -> Mobi6Converter(this, db, mobi6).convert()
      kf8 === mobi6 -> Kf8Converter(this, db, kf8).convert()
      else -> runCatching { Kf8Converter(this, db, kf8).convert() }.getOrElse { Mobi6Converter(this, db, mobi6).convert() }
    }
    EpubWriter.write(content, out)
  }

  /** Text bytes in the book: a size measure comparable to an EPUB's HTML bytes. */
  val textLength: Long get() = main.textLength

  override fun close() = db.close()

  companion object {
    /**
     * Bumped whenever conversion changes what it writes, so converted copies made by an older version are made again.
     * Saved reading positions, highlights and the search index name the converted documents (`OEBPS/part0003.xhtml`), so
     * a change must keep how the text is split into documents and how they are named.
     */
    const val CONVERTER_VERSION = 2

    fun isMobi(file: File): Boolean = PalmDatabase.isMobi(file)

    /** Opens [file]; throws [MobiException] when it is not a readable MOBI (damaged, or protected by DRM). */
    fun open(file: File): MobiBook {
      val db = PalmDatabase.open(file)
      try {
        val first = MobiHeader(db.record(0), 0)
        if (first.encryption != 0) throw MobiException("protected by DRM")
        val kf8 = when {
          first.isKf8 -> first
          else -> first.exth.int(Exth.KF8_BOUNDARY)?.toInt()?.takeIf { it in 1 until db.recordCount }?.let { at ->
            val boundary = db.record(at - 1)
            if (boundary.size >= 8 && String(boundary, 0, 8, Charsets.ISO_8859_1) == "BOUNDARY") {
              runCatching { MobiHeader(db.record(at), at) }.getOrNull()?.takeIf { it.isKf8 && it.encryption == 0 }
            } else null
          }
        }
        return MobiBook(db, first, kf8)
      } catch (e: Exception) {
        db.close()
        throw e as? MobiException ?: MobiException(e.message ?: "unreadable")
      }
    }
  }
}

/** A file in the converted EPUB, by its path inside the content folder; [bytes] may be read from the book each time. */
internal class EpubFile(val href: String, val mediaType: String, private val content: () -> ByteArray) {
  constructor(href: String, mediaType: String, bytes: ByteArray) : this(href, mediaType, { bytes })

  val bytes: ByteArray get() = content()
}

/**
 * The resource records of a book (images and fonts), looked at only when the text refers to them. Their bytes are read
 * again when the EPUB is written, so a book full of large images is never held in memory all at once.
 */
internal class ResourceTable(private val db: PalmDatabase, private val base: Long) {
  private val loaded = LinkedHashMap<Int, EpubFile?>()

  /** Everything loaded so far that turned out to be a usable resource, in the order it was first asked for. */
  val used: List<EpubFile> get() = synchronized(loaded) { loaded.values.filterNotNull() }

  /** The [n]th resource (1-based), or null when there is none or it is neither an image nor a font. */
  fun get(n: Int): EpubFile? = synchronized(loaded) {
    if (n in loaded) loaded[n] else load(n).also { loaded[n] = it }
  }

  fun image(n: Int): EpubFile? = get(n)?.takeIf { it.mediaType.startsWith("image/") }

  private fun load(n: Int): EpubFile? {
    if (base == NULL_INDEX || n < 1) return null
    val index = base + n - 1
    if (index >= db.recordCount) return null
    val record = index.toInt()
    val data = runCatching { db.record(record) }.getOrNull() ?: return null
    imageType(data)?.let { (ext, mime) -> return EpubFile("image%05d.%s".format(n, ext), mime) { db.record(record) } }
    if (data.size > 24 && String(data, 0, 4, Charsets.ISO_8859_1) == "FONT") {
      val font = runCatching { decodeFont(data) }.getOrNull() ?: return null
      val ext = when (String(font, 0, 4, Charsets.ISO_8859_1)) { "OTTO" -> "otf"; else -> "ttf" }
      return EpubFile("font%05d.%s".format(n, ext), if (ext == "otf") "font/otf" else "font/ttf") { decodeFont(db.record(record)) ?: ByteArray(0) }
    }
    return null
  }

  companion object {
    fun imageType(data: ByteArray): Pair<String, String>? = when {
      data.size < 4 -> null
      data.u8(0) == 0xFF && data.u8(1) == 0xD8 && data.u8(2) == 0xFF -> "jpg" to "image/jpeg"
      data.u8(0) == 0x89 && String(data, 1, 3, Charsets.ISO_8859_1) == "PNG" -> "png" to "image/png"
      String(data, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "gif" to "image/gif"
      String(data, 0, 2, Charsets.ISO_8859_1) == "BM" && data.size > 54 -> "bmp" to "image/bmp"
      else -> null
    }

    /** A `FONT` record: optionally XOR-obfuscated over its first 1040 bytes, then optionally zlib-compressed. */
    fun decodeFont(data: ByteArray): ByteArray? {
      val size = data.u32(4).toInt()
      val flags = data.u32(8).toInt()
      val start = data.u32(12).toInt()
      val keyLength = data.u32(16).toInt()
      val keyStart = data.u32(20).toInt()
      if (start !in 24..data.size) return null
      var font = data.copyOfRange(start, data.size)
      if (flags and 0b10 != 0) {
        if (keyLength <= 0 || keyStart + keyLength > data.size) return null
        for (i in 0 until minOf(1040, font.size)) font[i] = (font[i].toInt() xor data[keyStart + i % keyLength].toInt()).toByte()
      }
      if (flags and 0b1 != 0) {
        if (size !in 1..(64 * 1024 * 1024)) return null
        val inflater = Inflater()
        try {
          inflater.setInput(font)
          val out = ByteArray(size)
          var n = 0
          while (n < size && !inflater.finished()) {
            val got = inflater.inflate(out, n, size - n)
            if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            n += got
          }
          font = out.copyOf(n)
        } finally {
          inflater.end()
        }
      }
      return font.takeIf { it.size >= 4 }
    }
  }
}

/** A table-of-contents entry of the converted EPUB. */
internal class TocNode(val label: String, val href: String, val children: List<TocNode>)

/** Everything the converted EPUB holds. */
internal class EpubContent(
  val book: MobiBook,
  val documents: List<EpubFile>,
  val styles: List<EpubFile>,
  val toc: List<TocNode>,
)

/**
 * Builds the table of contents from a book's NCX index: [resolve] turns an entry into an href, entries it cannot place are
 * left out (their children move up to their parent), and nesting follows the parent tag, or the depth when there is none.
 */
internal fun buildToc(index: MobiIndex, resolve: (IndexEntry) -> String?): List<TocNode> {
  class Node(val label: String, val href: String?, val children: MutableList<Node> = ArrayList())
  val nodes = index.entries.map { Node(index.string(it.value(3))?.trim().orEmpty(), resolve(it)) }
  val roots = ArrayList<Node>()
  val stack = ArrayList<Pair<Int, Int>>() // (depth, entry index)
  for ((i, entry) in index.entries.withIndex()) {
    val depth = entry.value(4)?.toInt() ?: 0
    while (stack.isNotEmpty() && stack.last().first >= depth) stack.removeAt(stack.lastIndex)
    val parent = entry.value(21)?.toInt()?.takeIf { it in 0 until i } ?: stack.lastOrNull()?.second
    (if (parent != null) nodes[parent].children else roots) += nodes[i]
    stack += depth to i
  }
  fun flatten(list: List<Node>): List<TocNode> = list.flatMap { n ->
    val children = flatten(n.children)
    if (n.href == null) children else listOf(TocNode(n.label.ifEmpty { "Untitled" }, n.href, children))
  }
  return flatten(roots)
}
