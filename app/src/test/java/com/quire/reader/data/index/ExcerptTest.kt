package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptTest {
  private fun el(text: String, progression: Double = 0.5) =
    SourceElement("ch1.xhtml", text, false, "application/xhtml+xml", progression, 0.0, "One")

  /** Char ranges of the first occurrence of each of [words] in [text], in the order given. */
  private fun hits(text: String, vararg words: String): List<IntRange> = words.map { w -> text.indexOf(w).let { it until it + w.length } }

  private fun excerpt(c: IndexChunk, vararg words: String) = buildExcerpt(c.text, c.segments, c.href, c.mediaType, hits(c.text, *words))

  private fun whole(text: String) = IndexChunk(0, "One", "ch1.xhtml", "application/xhtml+xml", text, listOf(MappingSegment(0, text.length, 0.5)), 0.0)

  private fun List<ExcerptSpan>.hits() = filter { it.hit }.map { it.text }

  private fun List<ExcerptSpan>.joined() = joinToString("") { it.text }

  private val oneChunk = TextChunker.chunk(listOf(el("The old house stood on a hill above Pemberley village and watched the road."))).chunks.single()

  @Test fun `highlight markers become char ranges of the text without them`() {
    val o = TextChunker.HIGHLIGHT_OPEN
    val c = TextChunker.HIGHLIGHT_CLOSE
    assertEquals(listOf(4..8, 14..16), highlightRanges("The ${o}quick${c} and ${o}fox${c}."))
    assertEquals(listOf(0..1), highlightRanges("${o}日本${c}語"))
    assertEquals(listOf(2..3), highlightRanges("ab${o}cd"))
    assertEquals(emptyList<IntRange>(), highlightRanges("no marks"))
    assertEquals(emptyList<IntRange>(), highlightRanges("${o}${c}empty"))
  }

  @Test fun `substring ranges find every occurrence of every run in order`() {
    assertEquals(listOf(0..1, 3..4, 6..7), substringRanges("東京と東京と京都", listOf("東京", "京都")))
    assertEquals(emptyList<IntRange>(), substringRanges("abc", listOf("日本")))
  }

  @Test fun `a short text becomes one excerpt with the match highlighted and nothing cut`() {
    val excerpt = excerpt(oneChunk, "Pemberley")!!
    assertEquals(oneChunk.text, excerpt.spans.joined())
    assertEquals(listOf("Pemberley"), excerpt.spans.hits())
    assertEquals("Pemberley", excerpt.target.highlight)
  }

  @Test fun `the words of a phrase are one highlight and the locator target covers the whole phrase`() {
    val excerpt = excerpt(oneChunk, "stood", "on")!!
    assertEquals(listOf("stood on"), excerpt.spans.hits())
    assertEquals("stood on", excerpt.target.highlight)
    assertEquals("The old house ", excerpt.target.before)
  }

  @Test fun `separate matches stay separate highlights and each one inside the excerpt is marked`() {
    val excerpt = excerpt(oneChunk, "house", "hill", "road")!!
    assertEquals(listOf("house", "hill", "road"), excerpt.spans.hits())
    assertEquals("house", excerpt.target.highlight) // the first match in the text is the target
  }

  @Test fun `matches are found by position even when they come in any order`() {
    val excerpt = excerpt(oneChunk, "road", "house")!!
    assertEquals("house", excerpt.target.highlight)
    assertEquals(listOf("house", "road"), excerpt.spans.hits())
  }

  @Test fun `a later match just past the window is pulled in so the second term stays visible`() {
    val text = "alpha " + List(20) { "filler$it" }.joinToString(" ") + " omega " + List(20) { "tail$it" }.joinToString(" ")
    val chunk = TextChunker.chunk(listOf(el(text))).chunks.single()
    val excerpt = excerpt(chunk, "alpha", "omega")!!
    assertEquals(listOf("alpha", "omega"), excerpt.spans.hits())
  }

  @Test fun `a later match far beyond the cap does not stretch the excerpt`() {
    val text = "alpha " + List(50) { "filler$it" }.joinToString(" ") + " omega"
    val chunk = TextChunker.chunk(listOf(el(text))).chunks.single()
    val shown = excerpt(chunk, "alpha", "omega")!!.spans
    assertEquals(listOf("alpha"), shown.hits())
    assertFalse(shown.joined().contains("omega"))
  }

  @Test fun `text after multibyte characters is highlighted correctly`() {
    val text = "Ünïcödé 日本語 and 😀 emoji then target word here"
    val chunk = TextChunker.chunk(listOf(el(text))).chunks.single()
    val excerpt = excerpt(chunk, "target")!!
    assertEquals(listOf("target"), excerpt.spans.hits())
    assertEquals(chunk.text, excerpt.spans.joined())
    assertEquals("target", excerpt.target.highlight)
  }

  @Test fun `a match near the start of a long text is cut only after it`() {
    val text = List(400) { "word$it" }.joinToString(" ")
    val chunk = TextChunker.chunk(listOf(el(text))).chunks.first()
    val excerpt = excerpt(chunk, "word3")!!
    val shown = excerpt.spans.joined()
    assertTrue(shown.length < 300)
    assertTrue(shown.endsWith("…"))
    assertFalse(shown.startsWith("…"))
    assertEquals(listOf("word3"), excerpt.spans.hits())
  }

  @Test fun `a match deep in a long text has ellipses on both sides and keeps whole words`() {
    val text = List(2000) { "token$it" }.joinToString(" ")
    val excerpt = excerpt(whole(text), "token1000")!!
    val shown = excerpt.spans.joined()
    assertTrue(shown.startsWith("…token") && shown.endsWith("…"))
    assertTrue(shown.removePrefix("…").removeSuffix("…").split(' ').all { w -> w.startsWith("token") && w.drop(5).all(Char::isDigit) })
    assertEquals(listOf("token1000"), excerpt.spans.hits())
  }

  @Test fun `an excerpt never splits a surrogate pair`() {
    val text = "😀".repeat(300) + " needle " + "😀".repeat(300)
    val shown = excerpt(whole(text), "needle")!!.spans.joined()
    assertEquals("no lone surrogates", shown, String(shown.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
  }

  @Test fun `hits that do not fit the text or no mapped segment give no excerpt`() {
    assertNull(buildExcerpt(oneChunk.text, oneChunk.segments, oneChunk.href, null, emptyList()))
    assertNull(buildExcerpt(oneChunk.text, oneChunk.segments, oneChunk.href, null, listOf(99999..99999 + 4)))
    assertNull(buildExcerpt(oneChunk.text, emptyList(), oneChunk.href, null, hits(oneChunk.text, "house")))
  }

  @Test fun `the locator target comes from the element that holds the match`() {
    val first = el("Alpha beta gamma.", progression = 0.1)
    val second = el("Delta epsilon zeta.", progression = 0.2)
    val chunk = TextChunker.chunk(listOf(first, second)).chunks.single()
    val excerpt = excerpt(chunk, "epsilon")!!
    assertEquals(slimLocatorJson("ch1.xhtml", "application/xhtml+xml", MappingCodec.stored(0.2)), excerpt.target.locatorJson)
    assertEquals("Delta ", excerpt.target.before)
    assertEquals(" zeta", excerpt.target.after)
  }

  @Test fun `the full locator keeps the stored one and adds the text around the match`() {
    val target = MatchTarget("""{"href":"a.xhtml","locations":{"progression":0.5,"cssSelector":"p"}}""", "say \"hi\" ", "there\n", " 日本語", 0)
    assertEquals(
      """{"href":"a.xhtml","locations":{"progression":0.5,"cssSelector":"p"},"text":{"before":"say \"hi\" ","highlight":"there\u000a","after":" 日本語"}}""",
      target.fullLocatorJson(),
    )
  }

  @Test fun `empty before and after are left out of the locator text and a bare object still works`() {
    val target = MatchTarget("{}", "", "word", "", 0)
    assertEquals("""{"text":{"highlight":"word"}}""", target.fullLocatorJson())
    assertNull(MatchTarget("not json", "", "word", "", 0).fullLocatorJson())
    assertNotNull(MatchTarget("""{"a":1}""", "b", "w", "a", 0).fullLocatorJson())
  }
}
