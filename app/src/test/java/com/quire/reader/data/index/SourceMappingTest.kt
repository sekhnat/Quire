package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SourceMappingTest {
  private fun el(text: String, progression: Double?) = SourceElement("ch1.xhtml", text, false, "application/xhtml+xml", progression, 0.0, "One")

  private val one = el("Alpha beta gamma.", 0.25)
  private val two = el("Delta épsilon zeta.", 0.5)
  private val chunk = TextChunker.chunk(listOf(one, two)).chunks.single()

  private fun at(c: IndexChunk, word: String) = c.text.indexOf(word).let { it to it + word.length }

  private fun resolve(c: IndexChunk, word: String, context: Int = 50) = at(c, word).let { (s, e) -> resolveMatch(c.text, c.segments, c.href, c.mediaType, s, e, context) }

  @Test fun `a mapping round-trips, with segment ends implied by the next start and the text length`() {
    val segments = listOf(MappingSegment(0, 10, 0.0), MappingSegment(10, 41, null), MappingSegment(41, 42, 1.0), MappingSegment(42, 900, MappingCodec.stored(0.123456)))
    assertEquals(segments, MappingCodec.decode(MappingCodec.encode(segments), 900))
    assertEquals(emptyList<MappingSegment>(), MappingCodec.decode(MappingCodec.encode(emptyList()), 0))
  }

  @Test fun `progression is kept to one part in 65535 and missing progression stays missing`() {
    for (p in listOf(0.0, 1.0, 0.5, 1e-9, 0.999999, 0.3333333)) {
      val back = MappingCodec.stored(p)!!
      assertTrue("$p -> $back", kotlin.math.abs(back - p) <= 0.5 / MappingCodec.SCALE)
    }
    assertNull(MappingCodec.stored(null))
    assertEquals(MappingCodec.stored(1.0), MappingCodec.stored(7.0)) // out of range is clamped, never refused
  }

  @Test fun `a typical chunk mapping costs a few bytes a segment`() {
    val rnd = Random(1)
    var at = 0
    var p = 0.0
    val segments = List(200) {
      val s = MappingSegment(at, 0, MappingCodec.stored(p))
      at += rnd.nextInt(5, 900)
      p = minOf(1.0, p + rnd.nextDouble(0.0, 0.01))
      s
    }.let { list -> list.mapIndexed { i, s -> s.copy(charEnd = if (i + 1 < list.size) list[i + 1].charStart else at) } }
    val blob = MappingCodec.encode(segments)
    assertTrue("${blob.size} bytes for ${segments.size} segments", blob.size <= 2 + segments.size * 5)
    assertEquals(segments, MappingCodec.decode(blob, at))
  }

  @Test fun `damaged mapping bytes are refused rather than half-read`() {
    val good = MappingCodec.encode(listOf(MappingSegment(0, 5, 0.5), MappingSegment(5, 10, 0.6)))
    val bad = listOf(
      byteArrayOf(), byteArrayOf(1, 0), good.copyOf(good.size - 1), good + byteArrayOf(0), byteArrayOf(2, 0x7F),
      byteArrayOf(2, 1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01, 0),
    )
    for (b in bad) assertThrows(b.joinToString(), IllegalArgumentException::class.java) { MappingCodec.decode(b, 10) }
    assertThrows(IllegalArgumentException::class.java) { MappingCodec.decode(good, 4) } // a start past the text
  }

  @Test fun `the slim locator has the fields Readium writes, and empty locations when progression is unknown`() {
    assertEquals("""{"href":"OEBPS/a b.xhtml","type":"application/xhtml+xml","locations":{"progression":0.25}}""", slimLocatorJson("OEBPS/a b.xhtml", "application/xhtml+xml", 0.25))
    assertEquals("""{"href":"x\"y.html","locations":{}}""", slimLocatorJson("x\"y.html", null, null))
  }

  @Test fun `a match resolves to its element with text cut from the stored source`() {
    val t = resolve(chunk, "épsilon")!!
    assertEquals(slimLocatorJson("ch1.xhtml", "application/xhtml+xml", MappingCodec.stored(0.5)), t.locatorJson)
    assertEquals("Delta ", t.before)
    assertEquals("épsilon", t.highlight)
    assertEquals(" zeta", t.after) // the chunk ends at its last token, so the final full stop is not stored
    assertEquals(chunk.text.indexOf("épsilon"), t.chunkCharStart)
  }

  @Test fun `before and after are limited to the context asked for and stay inside the element`() {
    val t = resolve(chunk, "beta", context = 3)!!
    assertEquals(slimLocatorJson("ch1.xhtml", "application/xhtml+xml", MappingCodec.stored(0.25)), t.locatorJson)
    assertEquals("ha ", t.before)
    assertEquals("beta", t.highlight)
    assertEquals(" ga", t.after)
    // the space joining this element to the next is stored with it but is never part of the target
    assertEquals("gamma.", resolve(chunk, "gamma")!!.let { it.highlight + it.after })
  }

  @Test fun `a match that runs into the next element is cut at the element it starts in`() {
    val start = chunk.text.indexOf("gamma")
    val end = chunk.text.indexOf("Delta") + "Delta".length
    val t = resolveMatch(chunk.text, chunk.segments, chunk.href, chunk.mediaType, start, end)!!
    assertEquals("gamma.", t.highlight)
    assertEquals("", t.after)
  }

  @Test fun `context is never cut through a surrogate pair`() {
    val c = TextChunker.chunk(listOf(el("𝒜𝒜𝒜𝒜 match", 0.1))).chunks.single()
    assertEquals("𝒜 ", resolve(c, "match", context = 2)!!.before)
  }

  @Test fun `a match that is not on a character inside a mapped segment resolves to nothing`() {
    assertNotNull(resolve(chunk, "épsilon"))
    val gap = chunk.text.indexOf("Delta") - 1 // the space joining the elements
    assertNull(resolveMatch(chunk.text, chunk.segments, chunk.href, null, gap, gap + 1))
    assertNull(resolveMatch(chunk.text, chunk.segments, chunk.href, null, chunk.text.length + 5, chunk.text.length + 6))
    assertNull(resolveMatch(chunk.text, emptyList(), chunk.href, null, 0, 3))
    val c = TextChunker.chunk(listOf(el("𝒜 match", 0.1))).chunks.single()
    assertNull("inside a surrogate pair", resolveMatch(c.text, c.segments, c.href, null, 1, 3))
  }
}
