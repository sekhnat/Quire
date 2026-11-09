package com.quire.reader.data.scan

import com.quire.reader.data.mobi.MobiBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class WordCounterTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test fun `words are runs of letters and digits`() {
    assertEquals(0, WordTally.ofText(""))
    assertEquals(0, WordTally.ofText("  — … !? "))
    assertEquals(4, WordTally.ofText("It was a  dark"))
    assertEquals(3, WordTally.ofText("one,two;three"))
    assertEquals(2, WordTally.ofText("Chapter 12"))
  }

  @Test fun `apostrophes and hyphens inside a word keep it one word`() {
    assertEquals(1, WordTally.ofText("don't"))
    assertEquals(1, WordTally.ofText("don’t"))
    assertEquals(1, WordTally.ofText("well-known"))
    assertEquals(2, WordTally.ofText("rock -- roll"))
    assertEquals(2, WordTally.ofText("rock--roll"))
    assertEquals(2, WordTally.ofText("'Tis said'"))
    assertEquals(1, WordTally.ofText("Dickens'"))
  }

  @Test fun `a point or comma inside a number keeps it one word, but not between words`() {
    assertEquals(1, WordTally.ofText("3.14"))
    assertEquals(1, WordTally.ofText("1,000,000"))
    assertEquals(2, WordTally.ofText("in 1813."))
    assertEquals(2, WordTally.ofText("end.Next"))
    assertEquals(2, WordTally.ofText("3.a"))
  }

  @Test fun `accents, other scripts and combining marks stay inside words`() {
    assertEquals(2, WordTally.ofText("Café crème"))
    assertEquals(2, WordTally.ofText("Café crème"))
    assertEquals(3, WordTally.ofText("Война и мир"))
    assertEquals(2, WordTally.ofText("नमस्ते दुनिया"))
    assertEquals(1, WordTally.ofText("soft­hyphen"))
  }

  @Test fun `each Chinese character and kana counts as a word`() {
    assertEquals(3, WordTally.ofText("红楼梦。")) // the full stop is punctuation
    assertEquals(4, WordTally.ofText("吾輩は猫"))
    assertEquals(6, WordTally.ofText("Hello 世界 and 東京"))
    assertEquals(2, WordTally.ofText("𠀋𠀌")) // outside the Basic Multilingual Plane
  }

  @Test fun `markup is not counted and only block tags end a word`() {
    assertEquals(2, WordTally.ofHtml("<p>Hello <em>world</em></p>"))
    assertEquals(2, WordTally.ofHtml("<p>one</p><p>two</p>"))
    assertEquals(2, WordTally.ofHtml("one<br/>two"))
    assertEquals(1, WordTally.ofHtml("<p><span class=\"dropcap\">T</span>he</p>"))
    assertEquals(2, WordTally.ofHtml("<a href=\"x.html#a b\" title='many words here'>two words</a>"))
    assertEquals(2, WordTally.ofHtml("<h1>Title</h1>Body"))
  }

  @Test fun `head, scripts, styles and comments are left out`() {
    val html = """
      <?xml version="1.0" encoding="utf-8"?>
      <!DOCTYPE html>
      <html xmlns="http://www.w3.org/1999/xhtml">
      <head><title>Not counted</title><style>p { margin: 0 } /* none */</style></head>
      <body>
        <!-- a comment with words -->
        <script type="text/javascript">var notCounted = "a b c";</script>
        <p>Only these four words.</p>
        <svg:svg xmlns:svg="http://www.w3.org/2000/svg"><svg:style>.a{}</svg:style></svg:svg>
      </body>
      </html>
    """.trimIndent()
    assertEquals(4, WordTally.ofHtml(html))
  }

  @Test fun `character references are read as the characters they stand for`() {
    assertEquals(2, WordTally.ofHtml("Fish&amp;chips"))
    assertEquals(1, WordTally.ofHtml("don&rsquo;t"))
    assertEquals(1, WordTally.ofHtml("don&#8217;t"))
    assertEquals(1, WordTally.ofHtml("don&#x2019;t"))
    assertEquals(2, WordTally.ofHtml("two&nbsp;words"))
    assertEquals(2, WordTally.ofHtml("two&#160;words"))
    assertEquals(1, WordTally.ofHtml("caf&eacute;"))
    assertEquals(1, WordTally.ofHtml("soft&shy;hyphen"))
    assertEquals(2, WordTally.ofHtml("a & b"))
    assertEquals(3, WordTally.ofHtml("a &amp b"))
  }

  @Test fun `a stray angle bracket in text is not markup`() {
    assertEquals(2, WordTally.ofHtml("1 < 2"))
  }

  @Test fun `an EPUB is counted through its spine, in order, leaving out what the spine does not list`() {
    val epub = epub(
      "OEBPS/content.opf" to opf(
        manifest = """
          <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
          <item id="c1" href="Text/chapter%201.xhtml" media-type="application/xhtml+xml"/>
          <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
          <item id="css" href="style.css" media-type="text/css"/>
        """,
        spine = """<itemref idref="c1"/><itemref idref="c2" linear="no"/><itemref idref="css"/>""",
      ),
      "OEBPS/nav.xhtml" to xhtml("Contents Chapter one Chapter two"),
      "OEBPS/Text/chapter 1.xhtml" to xhtml("It is a truth universally acknowledged."),
      "OEBPS/Text/c2.xhtml" to xhtml("Footnotes count too."),
      "OEBPS/style.css" to "p { text-indent: 1em }",
    )
    ZipFile(epub).use { assertEquals(listOf("OEBPS/Text/chapter 1.xhtml", "OEBPS/Text/c2.xhtml"), WordCounter.spine(it)) }
    assertEquals(9, WordCounter.count(epub))
  }

  @Test fun `an EPUB without a usable package document counts every HTML file`() {
    val epub = epub("a.xhtml" to xhtml("one two"), "b/c.html" to xhtml("three"), "notes.txt" to "not counted")
    assertEquals(3, WordCounter.count(epub))
  }

  @Test fun `UTF-16 content documents are decoded`() {
    val doc = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xhtml("Ünïcode text here").toByteArray(Charsets.UTF_16LE)
    val epub = File(tmp.root, "utf16.epub")
    ZipOutputStream(epub.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("a.xhtml")); zip.write(doc); zip.closeEntry()
    }
    assertEquals(3, WordCounter.count(epub))
  }

  @Test fun `a file that is not a book has no count`() {
    val junk = File(tmp.root, "junk.epub").apply { writeText("not a zip") }
    assertNull(WordCounter.count(junk))
    assertNull(WordCounter.count(File(tmp.root, "missing.epub")))
  }

  @Test fun `hrefs resolve against the package document's folder`() {
    assertEquals("OEBPS/Text/a.xhtml", WordCounter.resolve("OEBPS", "Text/a.xhtml"))
    assertEquals("Text/a b.xhtml", WordCounter.resolve("OEBPS", "../Text/a%20b.xhtml"))
    assertEquals("a+b.xhtml", WordCounter.resolve("", "./a+b.xhtml#frag"))
  }

  @Test fun `MOBI and AZW3 books are counted from their text, close to the source and to their converted EPUB`() {
    val source = WordTally.ofHtml(String(resource("source.html"), Charsets.UTF_8))
    for (name in listOf("mobi6.mobi", "mobi6-uncompressed.mobi", "joint.mobi", "kf8.azw3")) {
      val file = File(tmp.root, name).apply { writeBytes(resource(name)) }
      val words = WordCounter.count(file)!!
      // Calibre adds a few words of its own (the title, a table of contents), and none of the text is lost.
      assertTrue("$name: $words words, source has $source", words in source..source + 30)
      // The same book opened as the EPUB it is converted to shows the same count.
      val epub = File(tmp.root, "$name.epub").apply { outputStream().use { out -> MobiBook.open(file).use { it.writeEpub(out) } } }
      assertEquals(name, words, WordCounter.count(epub))
    }
  }

  private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/mobi/$name")) { "missing fixture $name" }.use { it.readBytes() }

  private fun epub(vararg entries: Pair<String, String>): File {
    val file = File(tmp.root, "book${entries.size}-${System.nanoTime()}.epub")
    ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
      if (entries.any { it.first.endsWith(".opf") }) {
        zip.putNextEntry(ZipEntry("META-INF/container.xml"))
        zip.write(
          """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="${entries.first { it.first.endsWith(".opf") }.first}" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
        )
        zip.closeEntry()
      }
      for ((name, text) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
    }
    return file
  }

  private fun opf(manifest: String, spine: String) =
    """<?xml version="1.0" encoding="utf-8"?>
      <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test</dc:title><dc:identifier id="id">x</dc:identifier></metadata>
        <manifest>$manifest</manifest>
        <spine>$spine</spine>
      </package>"""

  private fun xhtml(body: String) =
    """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Ignored title</title></head><body><p>$body</p></body></html>"""
}
