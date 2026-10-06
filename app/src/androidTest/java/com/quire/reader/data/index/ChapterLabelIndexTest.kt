package com.quire.reader.data.index

import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.DbTestCase
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.reader.PublicationLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chapter labels of search snippets, end to end: a generated EPUB is read by Readium, indexed, and searched; each snippet
 * must carry the table-of-contents chapter of the element where its match begins.
 */
class ChapterLabelIndexTest : DbTestCase() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val epubDir = File(target.cacheDir, "db-tests/chapter-epubs").apply { mkdirs() }

  @After fun cleanUp() {
    scope.cancel()
    epubDir.deleteRecursively()
  }

  private inner class Indexed(val db: QuireDatabase, val index: IndexDatabase, val bookId: Long) {
    private fun query(input: String) = FtsQuery.parse(input) as FtsQuery.Result.Query

    /** The chapter labels of every snippet for [input], in reading order. */
    fun chapters(input: String): List<String> = runBlocking { TextSearcher(db, index).page(query(input), bookId) }.snippets.map { it.chapter }

    fun chapterOf(word: String): String = chapters(word).single()

    /** The text of every stored chunk, in reading order. */
    fun chunkTexts(): List<String> = index.chunkTexts(bookId)
  }

  private fun index(name: String, resources: List<FixtureResource>, toc: List<FixtureToc>): Indexed {
    val file = EpubFixtures.write(File(epubDir, "$name.epub"), resources, toc)
    val db = open()
    val index = openIndex()
    val book = runBlocking {
      val entity = bookEntity(folder(db), name).copy(path = file.absolutePath, mtime = file.lastModified(), sizeBytes = file.length())
      entity.copy(id = db.books().save(entity, emptyList()))
    }
    val indexer = LibraryIndexer(target, db, index, PublicationLoader(target), SettingsStore(target), scope)
    assertEquals(BatchResult(processed = 1, stop = BatchStop.Drained), runBlocking { indexer.runBatch(System.currentTimeMillis() + 60_000) })
    return Indexed(db, index, book.id)
  }

  @Test fun `chapters sharing one file are labelled from their anchors wherever the anchor sits`() {
    val book = index(
      "shared",
      listOf(
        FixtureResource(
          "book.xhtml",
          """<p>Frontmatterword opens the file.</p>
             <a id="c1"></a><h2>The first</h2><p>Kiwione grows in the first chapter.</p>
             <h2><a id="c2">2</a></h2><p>Kiwitwo grows in the second chapter.</p>
             <div id="c3"><h2>The third</h2><p>Kiwithree grows in the third chapter.</p></div>
             <p><a id="c4"></a>Kiwifour opens a chapter inside its first paragraph.</p>""",
        ),
      ),
      listOf(
        FixtureToc("book.xhtml", "c1", "Chapter One"), FixtureToc("book.xhtml", "c2", "Chapter Two"),
        FixtureToc("book.xhtml", "c3", "Chapter Three"), FixtureToc("book.xhtml", "c4", "Chapter Four"),
      ),
    )
    assertEquals("", book.chapterOf("frontmatterword"))
    assertEquals("Chapter One", book.chapterOf("kiwione"))
    assertEquals("Chapter Two", book.chapterOf("kiwitwo"))
    assertEquals("Chapter Three", book.chapterOf("kiwithree"))
    assertEquals("Chapter Four", book.chapterOf("kiwifour"))
  }

  @Test fun `one chapter per file is labelled by its file and a file the contents skip continues the previous chapter`() {
    val book = index(
      "perfile",
      listOf(
        FixtureResource("cover.xhtml", "<p>Dedicationword.</p>"),
        FixtureResource("a.xhtml", "<h2>A</h2><p>Plumone here.</p>"),
        FixtureResource("b.xhtml", "<h2>B</h2><p>Plumtwo here.</p>"),
        FixtureResource("interlude.xhtml", "<p>Plumbetween here.</p>"),
        FixtureResource("c.xhtml", "<h2>C</h2><p>Plumthree here.</p>"),
      ),
      listOf(FixtureToc("a.xhtml", null, "Alpha"), FixtureToc("b.xhtml", null, "Beta"), FixtureToc("c.xhtml", null, "Gamma")),
    )
    assertEquals("", book.chapterOf("dedicationword"))
    assertEquals("Alpha", book.chapterOf("plumone"))
    assertEquals("Beta", book.chapterOf("plumtwo"))
    assertEquals("Beta", book.chapterOf("plumbetween"))
    assertEquals("Gamma", book.chapterOf("plumthree"))
  }

  @Test fun `a chunk that would straddle a chapter boundary is split so each match keeps its own chapter`() {
    // No heading tags and short paragraphs: without a break at the boundary the last words of one chapter and the first
    // words of the next would sit in one chunk labelled with the first chapter.
    val book = index(
      "straddle",
      listOf(
        FixtureResource(
          "book.xhtml",
          """<p>Some opening words.</p><p>Endwordone closes the first chapter.</p>
             <a id="two"></a><p>Startwordtwo opens the second chapter.</p><p>More text of the second.</p>""",
        ),
      ),
      listOf(FixtureToc("book.xhtml", "one", "Chapter One"), FixtureToc("book.xhtml", "two", "Chapter Two")),
    )
    // "one" is not an anchor in the file, so the opening words belong to no chapter yet.
    assertEquals("", book.chapterOf("endwordone"))
    assertEquals("Chapter Two", book.chapterOf("startwordtwo"))
    val chunks = book.chunkTexts()
    assertEquals(2, chunks.size)
    assertTrue(chunks[1].startsWith("Startwordtwo opens"))
  }

  @Test fun `a phrase that runs across a chapter boundary is not found because chunks never span chapters`() {
    val book = index(
      "phrase",
      listOf(
        FixtureResource(
          "book.xhtml",
          """<a id="one"></a><p>The first chapter ends with silver lanterns</p>
             <a id="two"></a><p>swinging above the second chapter begins.</p>""",
        ),
      ),
      listOf(FixtureToc("book.xhtml", "one", "Chapter One"), FixtureToc("book.xhtml", "two", "Chapter Two")),
    )
    // Without overlap, a phrase is found only inside one chunk, and a chapter always starts a new one (documented in the README).
    assertEquals(emptyList<String>(), book.chapters("\"silver lanterns swinging\""))
    assertEquals(listOf("Chapter One"), book.chapters("\"first chapter ends with silver lanterns\""))
    assertEquals("Chapter Two", book.chapterOf("swinging"))
  }

  @Test fun `nested contents label text by the deepest chapter that has begun`() {
    val book = index(
      "nested",
      listOf(
        FixtureResource("part1.xhtml", "<p>Parttitleword page.</p>"),
        FixtureResource("ch1.xhtml", "<p>Epigraphword of the first.</p><h2 id=\"c1\">1</h2><p>Figone body.</p>"),
        FixtureResource("ch2.xhtml", "<h2 id=\"c2\">2</h2><p>Figtwo body.</p><h2 id=\"c3\">3</h2><p>Figthree body.</p>"),
        FixtureResource("part2.xhtml", "<p>Secondparttitleword page.</p>"),
        FixtureResource("ch4.xhtml", "<h2>4</h2><p>Figfour body.</p>"),
      ),
      listOf(
        FixtureToc(
          "part1.xhtml", null, "Part One",
          listOf(FixtureToc("ch1.xhtml", "c1", "Chapter 1"), FixtureToc("ch2.xhtml", "c2", "Chapter 2"), FixtureToc("ch2.xhtml", "c3", "Chapter 3")),
        ),
        FixtureToc("part2.xhtml", null, "Part Two", listOf(FixtureToc("ch4.xhtml", null, "Chapter 4"))),
      ),
    )
    assertEquals("Part One", book.chapterOf("parttitleword"))
    assertEquals("Part One", book.chapterOf("epigraphword"))
    assertEquals("Chapter 1", book.chapterOf("figone"))
    assertEquals("Chapter 2", book.chapterOf("figtwo"))
    assertEquals("Chapter 3", book.chapterOf("figthree"))
    assertEquals("Part Two", book.chapterOf("secondparttitleword"))
    assertEquals("Chapter 4", book.chapterOf("figfour"))
  }

  @Test fun `chapters in a resource with a self-closing title are labelled from their anchors like any other`() {
    // The filler takes the titled resource past the 2 KB where jsoup starts swallowing the start of a `<title/>` document into the title.
    val filler = (1..200).joinToString("") { "<p>Filler line $it keeps this file past two kilobytes.</p>" }
    val book = index(
      "mixed-title",
      listOf(
        FixtureResource("plain.xhtml", """<h2 id="one">One</h2><p>Ferns grow here.</p><h2 id="two">Two</h2><p>Mosses grow there.</p>"""),
        FixtureResource("titled.xhtml", """<h2 id="three">Three</h2><p>Lichens cling on.</p><h2 id="four">Four</h2><p>Liverworts spread out.</p>$filler""", head = "<title/>"),
      ),
      listOf(
        FixtureToc("plain.xhtml", "one", "One"), FixtureToc("plain.xhtml", "two", "Two"),
        FixtureToc("titled.xhtml", "three", "Three"), FixtureToc("titled.xhtml", "four", "Four"),
      ),
    )
    assertEquals("One", book.chapterOf("ferns"))
    assertEquals("Two", book.chapterOf("mosses"))
    assertEquals("Three", book.chapterOf("lichens"))
    assertEquals("Four", book.chapterOf("liverworts"))
  }
}
