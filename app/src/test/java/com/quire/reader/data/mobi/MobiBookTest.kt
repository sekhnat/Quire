package com.quire.reader.data.mobi

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Converts the Calibre-made fixtures (see `resources/mobi/README.md`) and checks the EPUB that comes out. */
class MobiBookTest {
  @get:Rule val tmp = TemporaryFolder()

  private val fixtures = listOf("mobi6.mobi", "mobi6-uncompressed.mobi", "joint.mobi", "kf8.azw3")

  private fun fixture(name: String): File =
    File(tmp.root, name).also { f -> checkNotNull(javaClass.getResourceAsStream("/mobi/$name")) { "missing fixture $name" }.use { f.outputStream().use(it::copyTo) } }

  /** The converted EPUB's entries, in order. */
  private class Epub(val names: List<String>, val entries: Map<String, ByteArray>, val firstMethod: Int) {
    fun text(name: String) = String(entries.getValue(name), Charsets.UTF_8)
    val opf get() = Jsoup.parse(text("OEBPS/content.opf"), "", Parser.xmlParser())
    val spine: List<String> get() {
      val opf = opf
      return opf.select("spine > itemref").map { ref -> "OEBPS/" + opf.selectFirst("manifest > item[id=${ref.attr("idref")}]")!!.attr("href") }
    }
    fun doc(name: String) = Jsoup.parse(text(name), "", Parser.xmlParser())
  }

  private fun convert(name: String): Epub {
    val bytes = ByteArrayOutputStream().also { out -> MobiBook.open(fixture(name)).use { it.writeEpub(out) } }.toByteArray()
    val names = ArrayList<String>()
    val entries = LinkedHashMap<String, ByteArray>()
    var firstMethod = -1
    ZipInputStream(bytes.inputStream()).use { zip ->
      while (true) {
        val e = zip.nextEntry ?: break
        if (names.isEmpty()) firstMethod = e.method
        names += e.name
        entries[e.name] = zip.readBytes()
      }
    }
    return Epub(names, entries, firstMethod)
  }

  @Test fun `reads metadata from the EXTH header`() {
    for (name in fixtures) {
      MobiBook.open(fixture(name)).use { book ->
        val m = book.metadata
        assertEquals(name, "Fixture Book", m.title)
        assertEquals(name, listOf("Ada Writer", "Bo Second"), m.authors)
        assertEquals(name, "Quire Press", m.publisher)
        assertEquals(name, "A short description.", m.description)
        assertEquals(name, listOf("Alpha", "Beta"), m.subjects)
        assertEquals(name, "fr", m.language)
        assertEquals(name, "9780000000002", m.isbn)
        assertTrue(name, m.published!!.startsWith("2001-02-03"))
        assertNotNull(name, m.asin)
        assertEquals(name, m.asin, m.uniqueId)
        assertEquals(name, name != "mobi6.mobi" && name != "mobi6-uncompressed.mobi", book.isKf8)
      }
    }
  }

  @Test fun `finds the cover image`() {
    for (name in fixtures) {
      val cover = MobiBook.open(fixture(name)).use { it.coverImage() }
      assertNotNull(name, cover)
      // Calibre writes MOBI 6 images as JPEG and keeps the PNG in AZW3.
      assertEquals(name, if (name == "kf8.azw3") "image/png" else "image/jpeg", ResourceTable.imageType(cover!!)?.second)
    }
  }

  @Test fun `writes a well-formed EPUB with mimetype first and stored`() {
    for (name in fixtures) {
      val epub = convert(name)
      assertEquals(name, "mimetype", epub.names.first())
      assertEquals(name, ZipEntry.STORED, epub.firstMethod)
      assertEquals(name, "application/epub+zip", epub.text("mimetype"))
      assertTrue(name, "META-INF/container.xml" in epub.entries)
      for ((entry, bytes) in epub.entries) {
        if (entry.endsWith(".xhtml") || entry.endsWith(".opf") || entry.endsWith(".xml")) {
          assertTrue("$name: $entry is not well-formed", Xhtml.isWellFormed(String(bytes, Charsets.UTF_8)))
        }
      }
      val opf = epub.opf
      assertEquals(name, "Fixture Book", opf.selectFirst("dc|title")!!.text())
      assertEquals(name, listOf("Ada Writer", "Bo Second"), opf.select("dc|creator").map { it.text() })
      assertEquals(name, "fr", opf.selectFirst("dc|language")!!.text())
      // Every manifest item is in the archive, and the cover is marked for Readium.
      for (item in opf.select("manifest > item")) assertTrue("$name: ${item.attr("href")}", "OEBPS/" + item.attr("href") in epub.entries)
      assertEquals(name, "cover-image", opf.selectFirst("manifest > item[properties~=cover-image]")!!.attr("id"))
      assertTrue(name, epub.spine.size >= 3)
    }
  }

  @Test fun `keeps all of the text, multibyte characters included`() {
    for (name in fixtures) {
      val epub = convert(name)
      val text = epub.spine.joinToString("\n") { epub.doc(it).text() }
      for (expected in listOf("It was the best of times, café naïve — “quoted”.", "中文字符 also appear here.", "Paragraph 0 of the long chapter: déjà vu, Ærøskøbing",
        "Paragraph 119 of the long chapter", "This is the link target paragraph.", "A nested section with a note.")) {
        assertTrue("$name lacks: $expected", text.contains(expected))
      }
      for (i in 0 until 120) assertTrue("$name lacks paragraph $i", text.contains("Paragraph $i of the long chapter: déjà vu, Ærøskøbing, “smart quotes” and 中文 text"))
    }
  }

  @Test fun `internal links point at their targets`() {
    for (name in fixtures) {
      val epub = convert(name)
      val link = epub.spine.firstNotNullOf { doc -> epub.doc(doc).select("a[href]").firstOrNull { it.text() == "the target in chapter three" }?.let { doc to it } }
      assertTrue(name, target(epub, link.first, link.second.attr("href")).startsWith("This is the link target paragraph."))
      val back = epub.spine.firstNotNullOf { doc -> epub.doc(doc).select("a[href]").firstOrNull { it.text() == "chapter one" }?.let { doc to it } }
      assertTrue(name, target(epub, back.first, back.second.attr("href")).startsWith("Chapter One"))
    }
  }

  @Test fun `table of contents keeps the book's nesting and points at the chapters`() {
    for (name in fixtures) {
      val epub = convert(name)
      val nav = epub.doc("OEBPS/nav.xhtml")
      val top = nav.select("nav > ol > li")
      if (name.startsWith("mobi6")) {
        // Calibre writes a flat NCX for MOBI 6, every entry at depth 0, in reading order.
        assertEquals(name, listOf("Chapter One", "Section One Point One", "Chapter Two", "Chapter Three"), top.map { it.child(0).text() })
      } else {
        assertEquals(name, listOf("Chapter One", "Chapter Two", "Chapter Three"), top.map { it.child(0).text() })
        assertEquals(name, listOf("Section One Point One"), top[0].select("> ol > li > a").map { it.text() })
      }
      val labels = nav.select("a").associate { it.text() to it.attr("href") }
      assertTrue(name, target(epub, "OEBPS/nav.xhtml", labels.getValue("Chapter One")).startsWith("Chapter One"))
      assertTrue(name, target(epub, "OEBPS/nav.xhtml", labels.getValue("Chapter Two")).startsWith("Chapter Two"))
      assertTrue(name, target(epub, "OEBPS/nav.xhtml", labels.getValue("Chapter Three")).startsWith("Chapter Three"))
      assertTrue(name, target(epub, "OEBPS/nav.xhtml", labels.getValue("Section One Point One")).startsWith("Section One Point One"))
    }
  }

  @Test fun `images are packed and referenced`() {
    for (name in fixtures) {
      val epub = convert(name)
      val images = epub.spine.flatMap { doc -> epub.doc(doc).select("img").map { it.attr("src") } }
      assertTrue(name, images.isNotEmpty())
      for (src in images) assertTrue("$name: $src", "OEBPS/$src" in epub.entries)
      assertTrue(name, images.all { ResourceTable.imageType(epub.entries.getValue("OEBPS/$it")) != null })
    }
  }

  @Test fun `KF8 keeps its stylesheets`() {
    for (name in listOf("joint.mobi", "kf8.azw3")) {
      val epub = convert(name)
      val css = epub.entries.keys.filter { it.endsWith(".css") }
      assertTrue(name, css.isNotEmpty())
      val first = epub.doc(epub.spine.first { epub.text(it).contains("best of times") })
      val linked = first.select("link[rel=stylesheet]").map { "OEBPS/" + it.attr("href") }
      assertTrue(name, linked.isNotEmpty() && linked.all { it in css })
      assertTrue(name, css.any { epub.text(it).contains(".note") || epub.text(it).contains("font-style: italic") })
    }
  }

  @Test fun `converting twice writes the same bytes`() {
    for (name in fixtures) {
      val a = ByteArrayOutputStream().also { out -> MobiBook.open(fixture(name)).use { it.writeEpub(out) } }.toByteArray()
      val b = ByteArrayOutputStream().also { out -> MobiBook.open(fixture(name)).use { it.writeEpub(out) } }.toByteArray()
      assertArrayEquals(name, a, b)
    }
  }

  @Test fun `refuses a DRM-protected book`() {
    val file = fixture("kf8.azw3")
    RandomAccessFile(file, "rw").use { raf ->
      raf.seek(78)
      val record0 = raf.readInt().toLong()
      raf.seek(record0 + 12)
      raf.writeShort(2)
    }
    try {
      MobiBook.open(file).close()
      fail("opened a DRM book")
    } catch (e: MobiException) {
      assertTrue(e.message!!.contains("DRM"))
    }
  }

  @Test fun `refuses a file that is not a MOBI`() {
    val file = File(tmp.root, "fake.mobi").apply { writeText("not a book at all, but long enough to have a header ".repeat(4)) }
    assertTrue(!MobiBook.isMobi(file))
    try {
      MobiBook.open(file).close()
      fail("opened a non-MOBI")
    } catch (_: MobiException) {
    }
  }

  /**
   * The text an href leads to: the element with the fragment's id (or the following content, for an empty anchor), or
   * the start of the document when there is no fragment.
   */
  private fun target(epub: Epub, from: String, href: String): String {
    val file = href.substringBefore('#').let { if (it.isEmpty()) from else from.substringBeforeLast('/') + "/" + it }
    val doc = epub.doc(file)
    val id = href.substringAfter('#', "")
    if (id.isEmpty()) return doc.selectFirst("body")!!.text()
    val el = doc.getElementById(id) ?: error("$href: no element $id in $file")
    return followingText(el)
  }

  private fun followingText(el: Element): String {
    if (el.text().isNotBlank()) return el.text()
    val out = StringBuilder()
    var seen = false
    el.ownerDocument()!!.traverse { node, _ -> if (node === el) seen = true else if (seen && node is TextNode) out.append(node.text()) }
    return out.toString().trim()
  }
}
