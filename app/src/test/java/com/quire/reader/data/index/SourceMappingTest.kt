package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceMappingTest {
  private fun el(text: String, locator: String) = SourceElement("ch1.xhtml", text, false, locator, 0.0, "One")

  private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

  /** Byte offset and size of the first [word] in the chunk, as FTS `offsets()` would report them. */
  private fun offsets(c: IndexChunk, word: String): Pair<Int, Int> = bytes(c.text.substring(0, c.text.indexOf(word))) to bytes(word)

  private val one = el("Alpha beta gamma.", """{"e":1}""")
  private val two = el("Delta épsilon zeta.", """{"e":2}""")
  private val chunk = TextChunker.chunk(listOf(one, two)).chunks.single()

  @Test fun `a mapping survives storage even with quotes, backslashes, control characters and Unicode in the locator`() {
    val segments = listOf(
      MappingSegment(0, 10, 0, """{"href":"a\b.xhtml","text":{"highlight":"say \"hi\"\n\ttab"}}"""),
      MappingSegment(11, 40, 7, "日本語 \uD835\uDC9C \u0001 \u001f"),
      MappingSegment(41, 42, 0, ""),
    )
    assertEquals(segments, MappingCodec.decode(MappingCodec.encode(segments)))
    assertEquals(emptyList<MappingSegment>(), MappingCodec.decode(MappingCodec.encode(emptyList())))
  }

  @Test fun `damaged mapping text is refused rather than half-read`() {
    for (bad in listOf("", "[[1,2,3]]", "[[1,2,3,\"x\"]", "[[1,2,3,\"x\"]]]", "{}", "[[1,2,3,\"\\q\"]]")) {
      assertThrows(bad, IllegalArgumentException::class.java) { MappingCodec.decode(bad) }
    }
  }

  @Test fun `a match resolves to its element with text cut from the stored source`() {
    val (start, size) = offsets(chunk, "épsilon")
    val t = resolveMatch(chunk.text, chunk.segments, start, size)!!
    assertEquals(two.locatorJson, t.locatorJson)
    assertEquals("Delta ", t.before)
    assertEquals("épsilon", t.highlight)
    assertEquals(" zeta", t.after) // the chunk ends at its last token, so the final full stop is not stored
    assertEquals(6, t.elementCharStart)
    assertEquals(chunk.text.indexOf("épsilon"), t.chunkCharStart)
  }

  @Test fun `before and after are limited to the context asked for and stay inside the element`() {
    val (start, size) = offsets(chunk, "beta")
    val t = resolveMatch(chunk.text, chunk.segments, start, size, contextChars = 3)!!
    assertEquals(one.locatorJson, t.locatorJson)
    assertEquals("ha ", t.before)
    assertEquals("beta", t.highlight)
    assertEquals(" ga", t.after)

    val last = offsets(chunk, "gamma")
    assertEquals("gamma.", resolveMatch(chunk.text, chunk.segments, last.first, last.second)!!.let { it.highlight + it.after })
  }

  @Test fun `a match that runs into the next element is cut at the element it starts in`() {
    val start = bytes(chunk.text.substring(0, chunk.text.indexOf("gamma")))
    val end = bytes(chunk.text.substring(0, chunk.text.indexOf("Delta") + "Delta".length))
    val t = resolveMatch(chunk.text, chunk.segments, start, end - start)!!
    assertEquals(one.locatorJson, t.locatorJson)
    assertEquals("gamma.", t.highlight)
    assertEquals("", t.after)
  }

  @Test fun `context is never cut through a surrogate pair`() {
    val e = el("\uD835\uDC9C\uD835\uDC9C\uD835\uDC9C\uD835\uDC9C match", """{"e":3}""")
    val c = TextChunker.chunk(listOf(e)).chunks.single()
    val (start, size) = offsets(c, "match")
    val t = resolveMatch(c.text, c.segments, start, size, contextChars = 2)!!
    assertEquals("\uD835\uDC9C ", t.before)
  }

  @Test fun `offsets that are not on a character inside a mapped segment resolve to nothing`() {
    val (start, size) = offsets(chunk, "épsilon")
    assertNotNull(resolveMatch(chunk.text, chunk.segments, start, size))
    assertNull("inside the two-byte é", resolveMatch(chunk.text, chunk.segments, start + 1, size - 1))
    val gap = bytes(chunk.text.substring(0, chunk.text.indexOf("Delta") - 1)) // the space joining the elements
    assertNull(resolveMatch(chunk.text, chunk.segments, gap, 1))
    assertNull(resolveMatch(chunk.text, chunk.segments, bytes(chunk.text) + 5, 1))
  }
}
