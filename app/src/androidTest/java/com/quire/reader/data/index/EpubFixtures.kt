package com.quire.reader.data.index

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** One XHTML chapter of a generated test EPUB; [title] is its table-of-contents label and [body] the markup inside `<body>`. */
class FixtureChapter(val title: String, val body: String)

/** An XHTML file of a generated test EPUB, in reading order; [body] is the markup inside `<body>`, and [head], when given, the markup inside `<head>` (default: a `<title>`). */
class FixtureResource(val name: String, val body: String, val head: String? = null, val mediaType: String = "application/xhtml+xml", val inSpine: Boolean = true, val bytes: ByteArray? = null)

/** A table-of-contents line pointing at [resource] (and the anchor [fragment] in it), with [children] nested under it. */
class FixtureToc(val resource: String, val fragment: String?, val title: String, val children: List<FixtureToc> = emptyList())

/** Builds small but valid EPUB 3 files for tests. */
object EpubFixtures {
  /** Paragraphs of plain text under a heading, the shape of an ordinary book chapter. */
  fun chapter(title: String, vararg paragraphs: String) =
    FixtureChapter(title, "<h2>$title</h2>" + paragraphs.joinToString("") { "<p>$it</p>" })

  fun write(file: File, chapters: List<FixtureChapter>): File =
    write(file, chapters.mapIndexed { i, c -> FixtureResource("c$i.xhtml", c.body) }, chapters.mapIndexed { i, c -> FixtureToc("c$i.xhtml", null, c.title) })

  /** An EPUB of [resources] in reading order whose contents are [toc] (nested as given); the resources named in [corrupt] cannot be read. */
  fun write(file: File, resources: List<FixtureResource>, toc: List<FixtureToc>, corrupt: Set<String> = emptySet()): File {
    file.parentFile?.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
      // The mimetype entry must come first and be stored uncompressed.
      val mimetype = "application/epub+zip".toByteArray()
      zip.putNextEntry(ZipEntry("mimetype").apply {
        method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mimetype) }.value
      })
      zip.write(mimetype); zip.closeEntry()
      zip.entry("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
      val manifest = resources.joinToString("") { r ->
        val id = "res-" + r.name.replace(Regex("[^A-Za-z0-9_-]"), "-")
        """<item id="$id" href="${r.name}" media-type="${r.mediaType}"/>"""
      }
      val spine = resources.filter { it.inSpine }.joinToString("") { r ->
        val id = "res-" + r.name.replace(Regex("[^A-Za-z0-9_-]"), "-")
        """<itemref idref="$id"/>"""
      }
      zip.entry(
        "OEBPS/content.opf",
        """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:fixture</dc:identifier><dc:title>Fixture</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""",
      )
      zip.entry("OEBPS/nav.xhtml", xhtml("<title>Contents</title>", """<nav xmlns:epub="http://www.idpf.org/2007/ops" epub:type="toc">${tocList(toc)}</nav>"""))
      resources.forEach { r ->
        if (r.bytes != null) {
          zip.putNextEntry(ZipEntry("OEBPS/${r.name}")); zip.write(r.bytes); zip.closeEntry()
        } else {
          zip.entry("OEBPS/${r.name}", xhtml(r.head ?: "<title>${r.name}</title>", r.body))
        }
      }
    }
    corrupt.forEach { corruptEntry(file, "OEBPS/$it") }
    return file
  }

  private fun tocList(entries: List<FixtureToc>): String =
    "<ol>" + entries.joinToString("") { e ->
      val href = e.resource + (e.fragment?.let { "#$it" } ?: "")
      """<li><a href="$href">${e.title}</a>${if (e.children.isEmpty()) "" else tocList(e.children)}</li>"""
    } + "</ol>"

  /** A file that is not a zip archive at all. */
  fun writeCorrupt(file: File): File = file.apply { parentFile?.mkdirs(); writeBytes(ByteArray(2_048) { (it * 31).toByte() }) }

  // ── continuous-scroll fixture ─────────────────────────────────────────────

  /** Distinctive text of a generated scroll chapter's [paragraph], for assertions about which chapter is on screen. */
  fun scrollParagraph(chapter: Int, paragraph: Int) = "Chapter $chapter paragraph $paragraph of the scroll fixture, watched by the quiet heron of the west field $chapter-$paragraph."

  /** Marker heading text of scroll chapter [chapter]. */
  fun scrollHeading(chapter: Int) = "Scroll Chapter $chapter"

  /**
   * The deterministic reflowable book used by the continuous-scroll tests. More resources than the old
   * three-chapter window (`windowHalf = 1`), and every geometry/style trap the scroll engine must survive:
   *
   *  - c0: short chapter (a single paragraph) — the next chapter must start at its actual content height.
   *  - c1: long chapter (60 paragraphs) with viewport-sized images (`style height:100vh`) that must not
   *        impose viewport-height minimums on neighbours, plus `#target-a` for a first fragment.
   *  - c2: long chapter with `#target-b`, a second TOC fragment in the *same* resource as c1's `#target-a`
   *        would not be distinct — so c2 carries the duplicate-ID cross-chapter case instead: an element
   *        with `id="shared"`, which c4 also has. Styles conflict with c3's on purpose (`body { color }`).
   *  - c3: publisher-style root sizing: `html, body { height: 100%; min-height: 100% }` (percentage and
   *        minimum heights must not expand the chapter to a viewport) and a relative `style.css` link.
   *  - c4: another `id="shared"` plus a relative `<img src="img/pic.png">` (asset lives at `OEBPS/img/pic.png`)
   *        and a relative link to c0 (`chapter-0.xhtml`), resolved against the resource's own base.
   *  - c5: closing chapter, also short.
   *
   * TOC covers a distant chapter (c5) and two distinct fragments (c1 `#target-a`, c2 `#target-b`).
   */
  fun writeScrollBook(file: File): File {
    val chapters = buildList {
      add(
        FixtureResource(
          "c0.xhtml",
          """<h2 id="h0">${scrollHeading(0)}</h2><p>${scrollParagraph(0, 1)}</p>""",
          "<title>Scroll 0</title>",
        )
      )
      add(
        FixtureResource(
          "c1.xhtml",
          """<h2 id="h1">${scrollHeading(1)}</h2>""" +
            "<p id=\"target-a\">Target A lives in the first long chapter of the scroll fixture.</p>" +
            (1..20).joinToString("") { "<p>${scrollParagraph(1, it)}</p>" } +
            """<img id="tall1" src="img/viewport1.png" alt="viewport sized" style="height: 100vh; width: auto; display: block;"/>""" +
            (21..40).joinToString("") { "<p>${scrollParagraph(1, it)}</p>" } +
            """<img id="tall2" src="img/viewport2.png" alt="viewport sized" style="height: 100vh; width: auto; display: block;"/>""" +
            (41..60).joinToString("") { "<p>${scrollParagraph(1, it)}</p>" } +
            "<p id=\"target-deep\">Deep target A sits near the end of the first long chapter.</p>",
          "<title>Scroll 1</title>",
        )
      )
      add(
        FixtureResource(
          "c2.xhtml",
          """<h2 id="h2">${scrollHeading(2)}</h2>""" +
            "<p id=\"target-b\">Target B lives in the second long chapter of the scroll fixture.</p>" +
            (1..30).joinToString("") { "<p>${scrollParagraph(2, it)}</p>" } +
            "<p id=\"shared\">The shared id of chapter two, which must not be confused with chapter four's.</p>" +
            (31..60).joinToString("") { "<p>${scrollParagraph(2, it)}</p>" },
          "<title>Scroll 2</title><style>body { color: #123456; }</style>",
        )
      )
      add(
        FixtureResource(
          "c3.xhtml",
          """<h2 id="h3">${scrollHeading(3)}</h2>""" +
            (1..25).joinToString("") { "<p>${scrollParagraph(3, it)}</p>" } +
            "<p>The relative stylesheet of chapter three sets its own body color.</p>",
          "<title>Scroll 3</title><link rel=\"stylesheet\" type=\"text/css\" href=\"c3.css\"/><style>html, body { height: 100%; min-height: 100%; }</style>",
        )
      )
      add(
        FixtureResource(
          "c4.xhtml",
          """<h2 id="h4">${scrollHeading(4)}</h2>""" +
            "<p id=\"shared\">The shared id of chapter four, which must not be confused with chapter two's.</p>" +
            (1..20).joinToString("") { "<p>${scrollParagraph(4, it)}</p>" } +
            """<p><img id="pic" src="img/pic.png" alt="relative picture"/> The picture above comes from a relative path.</p>""" +
            """<p><a id="rel-link" href="c0.xhtml">Back to the first chapter</a> via a relative link.</p>""" +
            (21..25).joinToString("") { "<p>${scrollParagraph(4, it)}</p>" },
          "<title>Scroll 4</title>",
        )
      )
      add(
        FixtureResource(
          "c5.xhtml",
          """<h2 id="h5">${scrollHeading(5)}</h2><p id="end">${scrollParagraph(5, 1)}</p>""",
          "<title>Scroll 5</title>",
        )
      )
    }
    val style = FixtureResource(
      "c3.css",
      body = "",
      mediaType = "text/css",
      inSpine = false,
      bytes = "body { color: #654321; }".toByteArray(),
    )
    fun image(name: String, color: String) = FixtureResource(
      name,
      body = "",
      mediaType = "image/png",
      inSpine = false,
      bytes = png(color),
    )
    val toc = listOf(
      FixtureToc("c0.xhtml", null, scrollHeading(0)),
      FixtureToc("c1.xhtml", "target-a", "Target A"),
      FixtureToc("c1.xhtml", "target-deep", "Deep target A"),
      FixtureToc("c2.xhtml", "target-b", "Target B"),
      FixtureToc("c3.xhtml", null, scrollHeading(3)),
      FixtureToc("c4.xhtml", null, scrollHeading(4)),
      FixtureToc("c5.xhtml", null, scrollHeading(5)),
    )
    return write(
      file,
      chapters + style + image("img/viewport1.png", "#ff0000") + image("img/viewport2.png", "#00ff00") + image("img/pic.png", "#0000ff"),
      toc,
    )
  }

  // ── bounded-scroll fixture ───────────────────────────────────────────────

  fun longHeading(chapter: Int) = "Long Chapter $chapter"

  /** Chapters differ in length (20 to 59 paragraphs), so no height estimate is right for all of them. */
  private fun longParagraphCount(chapter: Int) = 20 + (chapter * 13) % 40

  /** The chapter of [writeLongBook] that holds `#far-target`, deep in its text. */
  const val LONG_TARGET_CHAPTER = 33

  /**
   * A plain reflowable book with far more chapters than the scroll surface keeps live at once, each a few
   * screens tall and of a different length. Chapter [LONG_TARGET_CHAPTER] carries `#far-target`; the TOC lists every chapter and that target.
   */
  fun writeLongBook(file: File, chapters: Int = 40): File {
    val resources = (0 until chapters).map { c ->
      FixtureResource(
        "l$c.xhtml",
        """<h2 id="h$c">${longHeading(c)}</h2>""" +
          (1..longParagraphCount(c)).joinToString("") { p ->
            val target = if (c == LONG_TARGET_CHAPTER && p == 35) " id=\"far-target\"" else ""
            "<p$target>Chapter $c paragraph $p of the long fixture, where the quiet heron of the west field $c-$p keeps watch over the reeds and the slow water.</p>"
          },
        "<title>Long $c</title>",
      )
    }
    val toc = (0 until chapters).map { FixtureToc("l$it.xhtml", null, longHeading(it)) } +
      FixtureToc("l$LONG_TARGET_CHAPTER.xhtml", "far-target", "Far target")
    return write(file, resources, toc)
  }

  /** A solid [color] PNG of 24×24 px, built by hand (`java.awt` is unavailable on-device). */
  private fun png(color: String): ByteArray {
    // #rrggbb -> three channel bytes.
    val hex = color.removePrefix("#")
    val r = hex.substring(0, 2).toInt(16).toByte()
    val g = hex.substring(2, 4).toInt(16).toByte()
    val b = hex.substring(4, 6).toInt(16).toByte()
    val w = 24; val h = 24
    // Raw scanlines: each row starts with filter byte 0.
    val raw = ByteArray(h * (1 + w * 3))
    var at = 0
    for (y in 0 until h) {
      raw[at++] = 0
      for (x in 0 until w) { raw[at++] = r; raw[at++] = g; raw[at++] = b }
    }
    val idat = java.io.ByteArrayOutputStream().also { s ->
      java.util.zip.DeflaterOutputStream(s).use { it.write(raw) }
    }.toByteArray()

    val out = java.io.ByteArrayOutputStream()
    fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    fun chunk(type: String, data: ByteArray) {
      out.write(be32(data.size))
      val body = type.toByteArray(Charsets.US_ASCII) + data
      out.write(body)
      out.write(be32(java.util.zip.CRC32().apply { update(body) }.value.toInt()))
    }
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    val ihdr = be32(w) + be32(h) + byteArrayOf(8.toByte(), 2.toByte(), 0, 0, 0) // 8-bit depth, truecolor
    chunk("IHDR", ihdr)
    chunk("IDAT", idat)
    chunk("IEND", ByteArray(0))
    return out.toByteArray()
  }

  private fun xhtml(head: String, body: String) =
    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head>$head</head><body>$body</body></html>"""

  /**
   * Overwrites the compressed bytes of [entryName] in place: the archive and its directory stay valid, but reading that
   * entry fails, because 0xFF starts a deflate block of the reserved type, which inflaters reject.
   */
  private fun corruptEntry(file: File, entryName: String) {
    val size = ZipFile(file).use { zip -> requireNotNull(zip.getEntry(entryName)) { "no entry $entryName" }.compressedSize.toInt() }
    val bytes = file.readBytes()
    val name = entryName.toByteArray()
    val header = (0 until bytes.size - 30 - name.size).first { i ->
      bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0x04.toByte() &&
        u16(bytes, i + 26) == name.size && bytes.copyOfRange(i + 30, i + 30 + name.size).contentEquals(name)
    }
    val data = header + 30 + name.size + u16(bytes, header + 28)
    bytes.fill(0xFF.toByte(), data, data + size)
    file.writeBytes(bytes)
  }

  private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

  private fun ZipOutputStream.entry(name: String, content: String) {
    putNextEntry(ZipEntry(name)); write(content.toByteArray()); closeEntry()
  }
}
