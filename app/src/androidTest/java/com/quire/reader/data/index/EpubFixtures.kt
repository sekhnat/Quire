package com.quire.reader.data.index

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One XHTML chapter of a generated test EPUB; [title] is its table-of-contents label and [body] the markup inside `<body>`. */
class FixtureChapter(val title: String, val body: String)

/** An XHTML file of a generated test EPUB, in reading order; [body] is the markup inside `<body>`. */
class FixtureResource(val name: String, val body: String)

/** A table-of-contents line pointing at [resource] (and the anchor [fragment] in it), with [children] nested under it. */
class FixtureToc(val resource: String, val fragment: String?, val title: String, val children: List<FixtureToc> = emptyList())

/** Builds small but valid EPUB 3 files for tests. */
object EpubFixtures {
  /** Paragraphs of plain text under a heading, the shape of an ordinary book chapter. */
  fun chapter(title: String, vararg paragraphs: String) =
    FixtureChapter(title, "<h2>$title</h2>" + paragraphs.joinToString("") { "<p>$it</p>" })

  fun write(file: File, chapters: List<FixtureChapter>): File =
    write(file, chapters.mapIndexed { i, c -> FixtureResource("c$i.xhtml", c.body) }, chapters.mapIndexed { i, c -> FixtureToc("c$i.xhtml", null, c.title) })

  /** An EPUB of [resources] in reading order whose contents are [toc] (nested as given). */
  fun write(file: File, resources: List<FixtureResource>, toc: List<FixtureToc>): File {
    file.parentFile?.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
      // The mimetype entry must come first and be stored uncompressed.
      val mimetype = "application/epub+zip".toByteArray()
      zip.putNextEntry(ZipEntry("mimetype").apply {
        method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mimetype) }.value
      })
      zip.write(mimetype); zip.closeEntry()
      zip.entry("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
      val manifest = resources.indices.joinToString("") { """<item id="c$it" href="${resources[it].name}" media-type="application/xhtml+xml"/>""" }
      val spine = resources.indices.joinToString("") { """<itemref idref="c$it"/>""" }
      zip.entry(
        "OEBPS/content.opf",
        """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:fixture</dc:identifier><dc:title>Fixture</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""",
      )
      zip.entry("OEBPS/nav.xhtml", xhtml("Contents", """<nav xmlns:epub="http://www.idpf.org/2007/ops" epub:type="toc">${tocList(toc)}</nav>"""))
      resources.forEach { zip.entry("OEBPS/${it.name}", xhtml(it.name, it.body)) }
    }
    return file
  }

  private fun tocList(entries: List<FixtureToc>): String =
    "<ol>" + entries.joinToString("") { e ->
      val href = e.resource + (e.fragment?.let { "#$it" } ?: "")
      """<li><a href="$href">${e.title}</a>${if (e.children.isEmpty()) "" else tocList(e.children)}</li>"""
    } + "</ol>"

  /** A file that is not a zip archive at all. */
  fun writeCorrupt(file: File): File = file.apply { parentFile?.mkdirs(); writeBytes(ByteArray(2_048) { (it * 31).toByte() }) }

  private fun xhtml(title: String, body: String) =
    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body>$body</body></html>"""

  private fun ZipOutputStream.entry(name: String, content: String) {
    putNextEntry(ZipEntry(name)); write(content.toByteArray()); closeEntry()
  }
}
