package com.quire.reader.data.mobi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ConvertedBooksTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun fixture(name: String, as_: String = name): File =
    File(tmp.newFolder(), as_).also { f -> checkNotNull(javaClass.getResourceAsStream("/mobi/$name")).use { f.outputStream().use(it::copyTo) } }

  @Test fun `converts once and reuses the copy`() {
    val cache = ConvertedBooks(tmp.newFolder("cache"))
    val book = fixture("kf8.azw3")
    val first = cache.epubFor(book)
    ZipFile(first).use { assertTrue(it.getEntry("OEBPS/content.opf") != null) }
    first.setLastModified(1_000_000L)
    val again = cache.epubFor(book)
    assertEquals(first, again)
    // Used again: it moves to the front of the least-recently-used order.
    assertTrue(again.lastModified() > 1_000_000L)
  }

  @Test fun `a changed book gets a fresh copy and the old one goes`() {
    val dir = tmp.newFolder("cache")
    val cache = ConvertedBooks(dir)
    val book = fixture("mobi6.mobi")
    val first = cache.epubFor(book)
    book.setLastModified(book.lastModified() - 60_000)
    val second = cache.epubFor(book)
    assertNotEquals(first, second)
    assertFalse(first.exists())
    assertEquals(listOf(second.name), dir.list()!!.toList())
  }

  @Test fun `keeps the most recently used copies within its budget`() {
    val dir = tmp.newFolder("cache")
    val a = fixture("mobi6.mobi", "a.mobi")
    val b = fixture("kf8.azw3", "b.azw3")
    val c = fixture("joint.mobi", "c.mobi")
    // Room for about two copies.
    val budget = ConvertedBooks(dir, Long.MAX_VALUE).let { probe -> listOf(b, c).sumOf { probe.epubFor(it).length() } + 1 }
    dir.listFiles()!!.forEach { it.delete() }
    val cache = ConvertedBooks(dir, budget)
    val ca = cache.epubFor(a).also { it.setLastModified(1_000_000L) }
    val cb = cache.epubFor(b).also { it.setLastModified(2_000_000L) }
    val cc = cache.epubFor(c)
    assertTrue(cc.exists())
    assertTrue(cb.exists())
    assertFalse("the least recently used copy is evicted", ca.exists())
  }

  @Test fun `a one-off copy stays out of the cache`() {
    val dir = tmp.newFolder("cache")
    val cache = ConvertedBooks(dir)
    val book = fixture("kf8.azw3")
    assertEquals(null, cache.cached(book))
    val once = cache.temporary(book)
    ZipFile(once).use { assertTrue(it.getEntry("OEBPS/content.opf") != null) }
    // Not a cached copy: the next read still finds none, and the reader's copies are not counted against it.
    assertEquals(null, cache.cached(book))
    assertEquals(emptyList<String>(), dir.listFiles()!!.filter { it.isFile }.map { it.name })
    once.delete()
    val kept = cache.epubFor(book)
    assertEquals(kept, cache.cached(book))
  }

  @Test fun `a one-off conversion that fails leaves nothing behind`() {
    val dir = tmp.newFolder("cache")
    val bad = File(tmp.root, "bad.azw3").apply { writeBytes(ByteArray(200) { 7 }) }
    try {
      ConvertedBooks(dir).temporary(bad)
      error("converted a damaged book")
    } catch (_: MobiException) {
    }
    assertEquals(emptyList<String>(), dir.walkTopDown().filter { it.isFile }.map { it.name }.toList())
  }

  @Test fun `a book that cannot be converted leaves nothing behind`() {
    val dir = tmp.newFolder("cache")
    val bad = File(tmp.root, "bad.mobi").apply { writeBytes(ByteArray(200) { 7 }) }
    try {
      ConvertedBooks(dir).epubFor(bad)
      error("converted a damaged book")
    } catch (_: MobiException) {
    }
    assertEquals(emptyList<String>(), dir.list()!!.toList())
  }
}
