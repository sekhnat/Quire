package com.quire.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ChapterLookupTest {
  /** The lookup as it was before it used a precomputed order: the order rebuilt per call, searched with indexOf per entry. */
  private fun original(order: List<String>, files: List<String>, progressions: List<Double>, hereFile: String, inFile: Double): Int {
    val here = order.indexOf(hereFile)
    var found = -1
    files.forEachIndexed { i, file ->
      val at = order.indexOf(file)
      if (at in 0 until here || (at == here && progressions[i] <= inFile + ReaderSession.PROGRESSION_SLACK)) found = i
    }
    return found
  }

  private fun lookup(order: List<String>, files: List<String>, progressions: List<Double>, hereFile: String, inFile: Double) =
    ReaderSession.lastChapterBefore(files.size, { files[it] }, { progressions[it] }, ReaderSession.firstIndexByName(order), hereFile, inFile)

  @Test fun `the first index of a repeated name wins`() {
    assertEquals(mapOf("a" to 0, "b" to 1, "c" to 3), ReaderSession.firstIndexByName(listOf("a", "b", "a", "c", "b")))
    assertEquals(emptyMap<String, Int>(), ReaderSession.firstIndexByName(emptyList()))
  }

  @Test fun `chapters in earlier files count, and in this file only those that have started`() {
    val order = listOf("c1.xhtml", "c2.xhtml", "c3.xhtml")
    val files = listOf("c1.xhtml", "c2.xhtml", "c2.xhtml", "c3.xhtml")
    val progress = listOf(0.0, 0.0, 0.5, 0.0)
    assertEquals(1, lookup(order, files, progress, "c2.xhtml", 0.25))
    assertEquals(2, lookup(order, files, progress, "c2.xhtml", 0.5))
    assertEquals(3, lookup(order, files, progress, "c3.xhtml", 0.0))
    assertEquals(0, lookup(order, files, progress, "c1.xhtml", 0.9))
  }

  @Test fun `a chapter within the slack of the reading point has started`() {
    assertEquals(1, lookup(listOf("a"), listOf("a", "a"), listOf(0.0, 0.5003), "a", 0.5))
    assertEquals(0, lookup(listOf("a"), listOf("a", "a"), listOf(0.0, 0.502), "a", 0.5))
  }

  @Test fun `files the reading order does not name behave as before`() {
    assertEquals(original(listOf("a"), listOf("x", "a"), listOf(0.0, 0.0), "y", 0.0), lookup(listOf("a"), listOf("x", "a"), listOf(0.0, 0.0), "y", 0.0))
    assertEquals(-1, lookup(emptyList(), emptyList(), emptyList(), "a", 0.0))
  }

  @Test fun `it agrees with the lookup it replaced on random books`() {
    val random = Random(7)
    repeat(300) {
      val order = List(random.nextInt(1, 40)) { "f${random.nextInt(0, 45)}.xhtml" }
      val files = List(random.nextInt(0, 60)) { "f${random.nextInt(0, 50)}.xhtml" }
      val progress = List(files.size) { random.nextInt(0, 4) / 4.0 }
      val here = "f${random.nextInt(0, 50)}.xhtml"
      val inFile = random.nextInt(0, 5) / 4.0
      assertEquals("order=$order files=$files here=$here@$inFile", original(order, files, progress, here, inFile), lookup(order, files, progress, here, inFile))
    }
  }
}
