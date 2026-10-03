package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptTest {
  private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

  private fun el(text: String, locator: String = """{"href":"ch1.xhtml","locations":{"progression":0.5}}""") =
    SourceElement("ch1.xhtml", text, false, locator, 0.0, "One")

  /** `offsets()` text for [words] found in [text], one quadruple each, in the order given. */
  private fun offsets(text: String, vararg words: String): String =
    words.mapIndexed { term, w -> "0 $term ${bytes(text.substring(0, text.indexOf(w)))} ${bytes(w)}" }.joinToString(" ")

  private fun List<ExcerptSpan>.hits() = filter { it.hit }.map { it.text }

  private fun List<ExcerptSpan>.joined() = joinToString("") { it.text }

  private val oneChunk = TextChunker.chunk(listOf(el("The old house stood on a hill above Pemberley village and watched the road."))).chunks.single()

  @Test fun `offsets are read as quadruples and anything else is dropped`() {
    assertEquals(listOf(ByteMatch(16, 5), ByteMatch(23, 8)), parseOffsets("0 0 16 5 0 1 23 8"))
    assertEquals(listOf(ByteMatch(3, 4)), parseOffsets("0 0 3 4 0 1 9"))
    assertEquals(emptyList<ByteMatch>(), parseOffsets(""))
    assertEquals(emptyList<ByteMatch>(), parseOffsets("not offsets"))
  }

  @Test fun `the first match offset is the third number and is absent when there are no matches`() {
    assertEquals(16, firstMatchByte("0 0 16 5 0 1 23 8"))
    assertEquals(0, firstMatchByte("0 12 0 9"))
    assertEquals(1234, firstMatchByte("0 3 1234 5"))
    assertNull(firstMatchByte(""))
    assertNull(firstMatchByte("0 0"))
    assertNull(firstMatchByte("0 0 x 5"))
  }

  @Test fun `a short text becomes one excerpt with the match highlighted and nothing cut`() {
    val excerpt = buildExcerpt(oneChunk.text, oneChunk.segments, offsets(oneChunk.text, "Pemberley"))!!
    assertEquals(oneChunk.text, excerpt.spans.joined())
    assertEquals(listOf("Pemberley"), excerpt.spans.hits())
    assertEquals("Pemberley", excerpt.target.highlight)
  }

  @Test fun `the words of a phrase are one highlight and the locator target covers the whole phrase`() {
    val text = oneChunk.text
    val excerpt = buildExcerpt(text, oneChunk.segments, offsets(text, "stood", "on"))!!
    assertEquals(listOf("stood on"), excerpt.spans.hits())
    assertEquals("stood on", excerpt.target.highlight)
    assertEquals("The old house ", excerpt.target.before)
  }

  @Test fun `separate matches stay separate highlights and each one inside the excerpt is marked`() {
    val text = oneChunk.text
    val excerpt = buildExcerpt(text, oneChunk.segments, offsets(text, "house", "hill", "road"))!!
    assertEquals(listOf("house", "hill", "road"), excerpt.spans.hits())
    assertEquals("house", excerpt.target.highlight) // the first match in the text is the target
  }

  @Test fun `matches are found by position even when they come in any order`() {
    val text = oneChunk.text
    val excerpt = buildExcerpt(text, oneChunk.segments, offsets(text, "road", "house"))!!
    assertEquals("house", excerpt.target.highlight)
    assertEquals(listOf("house", "road"), excerpt.spans.hits())
  }

  @Test fun `a later match just past the window is pulled in so the second term stays visible`() {
    val text = "alpha " + List(20) { "filler$it" }.joinToString(" ") + " omega " + List(20) { "tail$it" }.joinToString(" ")
    val chunk = TextChunker.chunk(listOf(el(text))).chunks.single()
    val excerpt = buildExcerpt(chunk.text, chunk.segments, offsets(chunk.text, "alpha", "omega"))!!
    assertEquals(listOf("alpha", "omega"), excerpt.spans.hits())
  }

  @Test fun `a later match far beyond the cap does not stretch the excerpt`() {
    val text = "alpha " + List(50) { "filler$it" }.joinToString(" ") + " omega"
    val chunk = TextChunker.chunk(listOf(el(text))).chunks.single()
    val shown = buildExcerpt(chunk.text, chunk.segments, offsets(chunk.text, "alpha", "omega"))!!.spans
    assertEquals(listOf("alpha"), shown.hits())
    assertFalse(shown.joined().contains("omega"))
  }

  @Test fun `byte offsets are converted so text after multibyte characters is highlighted correctly`() {
    val text = "Ünïcödé 日本語 and 😀 emoji then target word here"
    val chunk = TextChunker.chunk(listOf(el(text))).chunks.single()
    val excerpt = buildExcerpt(chunk.text, chunk.segments, offsets(chunk.text, "target"))!!
    assertEquals(listOf("target"), excerpt.spans.hits())
    assertEquals(chunk.text, excerpt.spans.joined())
    assertEquals("target", excerpt.target.highlight)
  }

  @Test fun `a match near the start of a long text is cut only after it`() {
    val text = List(400) { "word$it" }.joinToString(" ")
    val chunk = TextChunker.chunk(listOf(el(text))).chunks.first()
    val excerpt = buildExcerpt(chunk.text, chunk.segments, offsets(chunk.text, "word3"))!!
    val shown = excerpt.spans.joined()
    assertTrue(shown.length < 300)
    assertTrue(shown.endsWith("…"))
    assertFalse(shown.startsWith("…"))
    assertEquals(listOf("word3"), excerpt.spans.hits())
  }

  @Test fun `a match deep in a long text has ellipses on both sides and keeps whole words`() {
    val text = List(2000) { "token$it" }.joinToString(" ")
    val chunk = IndexChunk(0, "One", "ch1.xhtml", text, bytes(text), 0, 2000, listOf(MappingSegment(0, bytes(text), 0, """{"href":"ch1.xhtml"}""")), 0.0)
    val excerpt = buildExcerpt(chunk.text, chunk.segments, offsets(chunk.text, "token1000"))!!
    val shown = excerpt.spans.joined()
    assertTrue(shown.startsWith("…token") && shown.endsWith("…"))
    assertTrue(shown.removePrefix("…").removeSuffix("…").split(' ').all { w -> w.startsWith("token") && w.drop(5).all(Char::isDigit) })
    assertEquals(listOf("token1000"), excerpt.spans.hits())
  }

  @Test fun `an excerpt never splits a surrogate pair`() {
    val text = "😀".repeat(300) + " needle " + "😀".repeat(300)
    val chunk = IndexChunk(0, "One", "ch1.xhtml", text, bytes(text), 0, 1, listOf(MappingSegment(0, bytes(text), 0, "{}")), 0.0)
    val shown = buildExcerpt(chunk.text, chunk.segments, offsets(chunk.text, "needle"))!!.spans.joined()
    assertEquals("no lone surrogates", shown, String(shown.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
  }

  @Test fun `offsets that do not fit the text or no mapped segment give no excerpt`() {
    assertNull(buildExcerpt(oneChunk.text, oneChunk.segments, ""))
    assertNull(buildExcerpt(oneChunk.text, oneChunk.segments, "0 0 99999 4"))
    assertNull(buildExcerpt(oneChunk.text, emptyList(), offsets(oneChunk.text, "house")))
  }

  @Test fun `the locator target comes from the element that holds the match`() {
    val first = el("Alpha beta gamma.", """{"href":"ch1.xhtml","locations":{"progression":0.1}}""")
    val second = el("Delta epsilon zeta.", """{"href":"ch1.xhtml","locations":{"progression":0.2}}""")
    val chunk = TextChunker.chunk(listOf(first, second)).chunks.single()
    val excerpt = buildExcerpt(chunk.text, chunk.segments, offsets(chunk.text, "epsilon"))!!
    assertEquals(second.locatorJson, excerpt.target.locatorJson)
    assertEquals("Delta ", excerpt.target.before)
    assertEquals(" zeta", excerpt.target.after)
  }

  @Test fun `the full locator keeps the stored one and adds the text around the match`() {
    val target = MatchTarget("""{"href":"a.xhtml","locations":{"progression":0.5,"cssSelector":"p"}}""", "say \"hi\" ", "there\n", " 日本語", 0, 0)
    assertEquals(
      """{"href":"a.xhtml","locations":{"progression":0.5,"cssSelector":"p"},"text":{"before":"say \"hi\" ","highlight":"there\u000a","after":" 日本語"}}""",
      target.fullLocatorJson(),
    )
  }

  @Test fun `empty before and after are left out of the locator text and a bare object still works`() {
    val target = MatchTarget("{}", "", "word", "", 0, 0)
    assertEquals("""{"text":{"highlight":"word"}}""", target.fullLocatorJson())
    assertNull(MatchTarget("not json", "", "word", "", 0, 0).fullLocatorJson())
    assertNotNull(MatchTarget("""{"a":1}""", "b", "w", "a", 0, 0).fullLocatorJson())
  }
}
