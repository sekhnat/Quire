package com.quire.reader.data.index

import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.reader.PublicationLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import java.io.File

/** What the indexer reads from a publication, compared with what Readium's own content service yields. */
@OptIn(ExperimentalReadiumApi::class)
class IndexContentTest {
  private val target = InstrumentationRegistry.getInstrumentation().targetContext
  private val dir = File(target.cacheDir, "db-tests/content-epubs").apply { mkdirs() }

  @After fun cleanUp() { dir.deleteRecursively() }

  private data class Seen(val href: String, val text: String, val css: String?, val progression: Double?)

  private suspend fun Content.Iterator.drain(): List<Seen> = buildList {
    while (true) {
      val element = nextOrNull() ?: break
      val text = element as? Content.TextElement ?: continue
      add(Seen(text.locator.href.toString(), text.segments.joinToString("") { it.text }, text.locator.locations.otherLocations["cssSelector"] as? String, text.locator.locations.totalProgression))
    }
  }

  private fun <T> withPublication(file: File, block: suspend (Publication) -> T): T = runBlocking {
    val publication = PublicationLoader(target).open(file).getOrThrow()
    try { block(publication) } finally { publication.close() }
  }

  /** Three resources of sixty paragraphs (about 5 KB each): jsoup only misreads `<title/>` in documents of more than about 2 KB. */
  private fun book(name: String, head: String? = null, corrupt: Set<String> = emptySet()): File {
    val resources = (0 until 3).map { i ->
      FixtureResource("c$i.xhtml", "<h2 id=\"h$i\">Chapter $i</h2>" + (0 until 60).joinToString("") { "<p class=\"p$it\">Paragraph $it of chapter $i about the lighthouse.</p>" }, head)
    }
    return EpubFixtures.write(File(dir, "$name.epub"), resources, resources.mapIndexed { i, r -> FixtureToc(r.name, null, "Chapter $i") }, corrupt)
  }

  @Test fun `a healthy book yields exactly what the Readium content service yields`() = withPublication(book("healthy")) { pub ->
    val expected = pub.content()!!.iterator().drain()
    val content = IndexContent(pub)
    assertEquals(expected, content.iterator.drain())
    assertEquals(3, content.tallies.size)
    assertTrue(content.tallies.none { it.readFailed })
    assertTrue(content.tallies.all { it.bytes > 0 && it.yieldedChars > 0 && !isSparse(it.bytes, it.yieldedChars) })
  }

  @Test fun `a self-closing title no longer hides the body`() {
    val closed = withPublication(book("closed", head = "<title>T</title>")) { pub -> IndexContent(pub).iterator.drain().map { it.text } }
    withPublication(book("self-closing", head = "<title/>")) { pub ->
      val readium = pub.content()!!.iterator().drain()
      assertTrue(
        "Readium alone loses most of the paragraphs",
        readium.count { it.text.contains("Paragraph") } < closed.count { it.contains("Paragraph") },
      )
      val content = IndexContent(pub)
      assertEquals(closed, content.iterator.drain().map { it.text })
      assertTrue(content.tallies.none { it.readFailed || isSparse(it.bytes, it.yieldedChars) })
    }
  }

  @Test fun `a resource that cannot be read is tallied as such and the others still yield`() = withPublication(book("corrupt", corrupt = setOf("c1.xhtml"))) { pub ->
    val content = IndexContent(pub)
    val hrefs = content.iterator.drain().map { it.href }.toSet()
    assertEquals(listOf(false, true, false), content.tallies.map { it.readFailed })
    assertTrue(hrefs.none { it.endsWith("c1.xhtml") })
    assertTrue(hrefs.any { it.endsWith("c0.xhtml") } && hrefs.any { it.endsWith("c2.xhtml") })
  }
}
