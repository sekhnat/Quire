package com.quire.reader.data.mobi

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs converted content as an EPUB 3: `mimetype` first and stored, then the package, a nav document and the files. */
internal object EpubWriter {
  private const val DIR = "OEBPS/"
  /** A fixed time, so converting a book twice writes the same bytes. */
  private const val ENTRY_TIME = 315_532_800_000L

  fun write(content: EpubContent, out: OutputStream) {
    val book = content.book
    val cover = book.coverResource()
    val resources = book.resources.used
    val nav = nav(content)
    ZipOutputStream(out).use { zip ->
      zip.put("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII), stored = true)
      zip.put("META-INF/container.xml", CONTAINER.toByteArray(Charsets.UTF_8))
      zip.put(DIR + "content.opf", opf(content, resources, cover?.href).toByteArray(Charsets.UTF_8))
      zip.put(DIR + NAV, nav.toByteArray(Charsets.UTF_8))
      // Images are compressed already; deflating them again only costs time.
      for (f in content.documents + content.styles + resources) zip.put(DIR + f.href, f.bytes, stored = f.mediaType.startsWith("image/") && !f.mediaType.contains("svg"))
    }
  }

  private fun ZipOutputStream.put(name: String, bytes: ByteArray, stored: Boolean = false) {
    val entry = ZipEntry(name).apply {
      time = ENTRY_TIME
      if (stored) {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        crc = CRC32().apply { update(bytes) }.value
      }
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
  }

  private fun opf(content: EpubContent, resources: List<EpubFile>, coverHref: String?): String {
    val book = content.book
    val m = book.metadata
    val x = Xhtml::escape
    val items = (content.documents + content.styles + resources).withIndex()
    return buildString {
      append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
      append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\"")
      if (book.fixedLayout) append(" prefix=\"rendition: http://www.idpf.org/vocab/rendition/#\"")
      append(">\n<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
      append("<dc:identifier id=\"uid\">").append(x(book.identifier)).append("</dc:identifier>\n")
      m.isbn?.takeIf { it != book.identifier }?.let { append("<dc:identifier>urn:isbn:").append(x(it)).append("</dc:identifier>\n") }
      append("<dc:title>").append(x(m.title.ifEmpty { "Untitled" })).append("</dc:title>\n")
      m.authors.forEach { append("<dc:creator>").append(x(it)).append("</dc:creator>\n") }
      append("<dc:language>").append(x(m.language ?: "und")).append("</dc:language>\n")
      m.publisher?.let { append("<dc:publisher>").append(x(it)).append("</dc:publisher>\n") }
      m.description?.let { append("<dc:description>").append(x(it)).append("</dc:description>\n") }
      m.subjects.forEach { append("<dc:subject>").append(x(it)).append("</dc:subject>\n") }
      m.published?.let { append("<dc:date>").append(x(it)).append("</dc:date>\n") }
      append("<meta property=\"dcterms:modified\">2000-01-01T00:00:00Z</meta>\n")
      if (coverHref != null) append("<meta name=\"cover\" content=\"cover-image\"/>\n")
      if (book.fixedLayout) append("<meta property=\"rendition:layout\">pre-paginated</meta>\n")
      append("</metadata>\n<manifest>\n")
      append("<item id=\"nav\" href=\"").append(NAV).append("\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
      for ((i, f) in items) {
        val id = if (f.href == coverHref) "cover-image" else "item$i"
        append("<item id=\"").append(id).append("\" href=\"").append(x(f.href)).append("\" media-type=\"").append(f.mediaType).append('"')
        val props = buildList {
          if (f.href == coverHref) add("cover-image")
          if (f.mediaType == Mobi6Converter.MEDIA_XHTML && f.bytes.containsSvg()) add("svg")
        }
        if (props.isNotEmpty()) append(" properties=\"").append(props.joinToString(" ")).append('"')
        append("/>\n")
      }
      append("</manifest>\n<spine")
      if (book.rightToLeft) append(" page-progression-direction=\"rtl\"")
      append(">\n")
      for ((i, f) in items) if (f.mediaType == Mobi6Converter.MEDIA_XHTML) append("<itemref idref=\"item").append(i).append("\"/>\n")
      append("</spine>\n</package>\n")
    }
  }

  private fun nav(content: EpubContent): String = buildString {
    append(Xhtml.DECLARATION)
    append("<html xmlns=\"").append(Xhtml.NS).append("\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>Contents</title></head><body>")
    append("<nav epub:type=\"toc\" id=\"toc\"><ol>")
    fun entries(nodes: List<TocNode>) {
      for (n in nodes) {
        append("<li><a href=\"").append(Xhtml.escape(n.href)).append("\">").append(Xhtml.escape(n.label)).append("</a>")
        if (n.children.isNotEmpty()) { append("<ol>"); entries(n.children); append("</ol>") }
        append("</li>")
      }
    }
    entries(content.toc)
    append("</ol></nav></body></html>")
  }

  private fun ByteArray.containsSvg(): Boolean = String(this, Charsets.ISO_8859_1).contains("<svg")

  private const val NAV = "nav.xhtml"
  private const val CONTAINER = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""
}
