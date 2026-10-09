package com.quire.reader.data.mobi

import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.reader.PublicationLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.publication.services.positions
import java.io.File

/** MOBI and AZW3 books opened the way the reader opens them: converted, then parsed and iterated by Readium. */
@OptIn(ExperimentalReadiumApi::class)
class MobiPublicationTest {
  private val target = InstrumentationRegistry.getInstrumentation().targetContext
  private val dir = File(target.cacheDir, "mobi-tests").apply { mkdirs() }

  @After fun cleanUp() { dir.deleteRecursively() }

  /** The unit-test fixtures (`src/test/resources/mobi`), which the instrumented tests share. */
  private fun fixture(name: String): File =
    File(dir, name).also { f -> checkNotNull(javaClass.getResourceAsStream("/mobi/$name")) { "missing fixture $name" }.use { f.outputStream().use(it::copyTo) } }

  @Test fun `Readium opens every converted fixture with its metadata, contents, cover and text`() = runBlocking {
    val loader = PublicationLoader(target)
    for (name in listOf("mobi6.mobi", "mobi6-uncompressed.mobi", "joint.mobi", "kf8.azw3")) {
      val pub = loader.open(fixture(name)).getOrThrow()
      try {
        assertEquals(name, "Fixture Book", pub.metadata.title)
        assertEquals(name, listOf("Ada Writer", "Bo Second"), pub.metadata.authors.map { it.name })
        assertTrue(name, pub.readingOrder.size >= 3)
        assertEquals(name, "Chapter One", pub.tableOfContents.first().title)
        assertTrue(name, pub.tableOfContents.flatMap { listOf(it) + it.children }.any { it.title == "Section One Point One" })
        assertNotNull(name, pub.cover())
        assertTrue(name, pub.positions().isNotEmpty())
        val text = buildString {
          val it = pub.content()!!.iterator()
          while (true) { val e = it.nextOrNull() ?: break; (e as? Content.TextElement)?.let { t -> append(t.segments.joinToString("") { s -> s.text }).append("\n") } }
        }
        assertTrue(name, text.contains("It was the best of times, café naïve"))
        assertTrue(name, text.contains("Paragraph 119 of the long chapter"))
      } finally {
        pub.close()
      }
    }
  }

  @Test fun `a second open reuses the converted copy`() = runBlocking {
    val loader = PublicationLoader(target)
    val book = fixture("kf8.azw3")
    loader.open(book).getOrThrow().close()
    val copies = File(target.cacheDir, "converted").listFiles().orEmpty().filter { it.name.endsWith(".epub") }.map { it.name to it.length() }
    loader.open(book).getOrThrow().close()
    assertEquals(copies, File(target.cacheDir, "converted").listFiles().orEmpty().filter { it.name.endsWith(".epub") }.map { it.name to it.length() })
  }

  @Test fun `a damaged MOBI fails to open instead of crashing`() = runBlocking {
    val bad = File(dir, "bad.mobi").apply { writeBytes(ByteArray(4096) { 1 }) }
    assertTrue(PublicationLoader(target).open(bad).isFailure)
  }
}
