package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextChunkerTest {
  private fun el(text: String, href: String = "ch1.xhtml", heading: Boolean = false, chapter: String = "One", progression: Double = 0.0, chapterStart: Boolean = false) =
    SourceElement(href, text, heading, "application/xhtml+xml", progression, progression, chapter, chapterStart)

  /** Small sizes keep the fixtures readable; the rules do not depend on them. */
  private fun chunk(vararg elements: SourceElement) = TextChunker.chunk(elements.toList(), minChars = MIN, maxChars = MAX)

  private fun chunk(elements: List<SourceElement>, cap: Long = TextChunker.MAX_STORED_BYTES) = TextChunker.chunk(elements, cap, MIN, MAX)

  private fun words(text: String) = Tokenizer.tokenize(text).map { text.substring(it.startChar, it.endChar) }

  /** A run of `n` four-character words, 5n-1 chars long. */
  private fun filler(n: Int, word: String = "abcd") = List(n) { word }.joinToString(" ")

  private fun assertWellFormed(chunks: List<IndexChunk>) {
    chunks.forEachIndexed { i, c ->
      assertEquals(i, c.seq)
      assertEquals(c.text.toByteArray().size + c.mapping.size + (c.seam?.text?.toByteArray()?.size ?: 0), c.storedBytes)
      assertEquals(c.segments, MappingCodec.decode(c.mapping, c.text.length))
      assertEquals(0, c.segments.first().charStart)
      assertEquals(c.text.length, c.segments.last().charEnd)
      c.seam?.let { s ->
        assertTrue("a seam's split is between tokens", s.splitChar in 1 until s.text.length && s.text[s.splitChar - 1] == ' ')
        assertTrue("the text after the split starts the chunk", c.text.startsWith(s.text.substring(s.splitChar).take(40)))
      }
    }
  }

  private fun randomCorpus(seed: Int, elements: Int, vocabulary: Int, maxWords: Int = 121): List<SourceElement> {
    val rnd = Random(seed)
    return List(elements) { i ->
      val text = List(rnd.nextInt(20, maxWords)) { "v${rnd.nextInt(vocabulary)}" }.joinToString(" ")
      el(text, heading = i % 9 == 0, chapter = "Chapter ${i / 9}", progression = i / elements.toDouble())
    }
  }

  @Test fun `no elements, blank elements and punctuation-only elements make no chunks`() {
    val r = chunk(el("   "), el(""), el("— … !"))
    assertEquals(emptyList<IndexChunk>(), r.chunks)
    assertFalse(r.truncated)
    assertEquals(emptyList<IndexChunk>(), TextChunker.chunk(emptyList()).chunks)
  }

  @Test fun `small neighbouring elements merge into one chunk that maps back to each of them`() {
    val r = chunk(el("Alpha beta.", progression = .1), el("Gamma delta.", progression = .2), el("Epsilon.", progression = .3))
    val only = r.chunks.single()
    assertEquals("Alpha beta. Gamma delta. Epsilon", only.text) // a chunk ends with its last token
    assertEquals(
      listOf(MappingSegment(0, 12, MappingCodec.stored(.1)), MappingSegment(12, 25, MappingCodec.stored(.2)), MappingSegment(25, 32, MappingCodec.stored(.3))),
      only.segments,
    )
    assertEquals("application/xhtml+xml", only.mediaType)
    assertNull(only.seam)
  }

  @Test fun `merging stops before a chunk would pass the maximum, and nothing is copied across the boundary`() {
    val e = filler(80) // 399 chars
    val r = chunk(el(e), el(e), el(e))
    assertEquals(listOf(799, 399), r.chunks.map { it.text.length })
    assertTrue(r.chunks.all { it.seam == null })
    assertWellFormed(r.chunks)
  }

  @Test fun `an element that fits in a chunk of its own is never split, even after a small open chunk`() {
    val small = el(filler(20)) // 99 chars, under the minimum
    val big = el(filler(170, "wxyz")) // 849 chars: fits alone, not with the open chunk
    val r = chunk(small, big)
    assertEquals(listOf(filler(20), filler(170, "wxyz")), r.chunks.map { it.text })
    assertTrue(r.chunks.all { it.seam == null })
  }

  @Test fun `a heading starts a new chunk and the chunk takes the heading's chapter`() {
    val r = chunk(
      el("Short intro paragraph.", chapter = "Intro", progression = .1),
      el("Chapter Two", heading = true, chapter = "Two", progression = .2),
      el("Body of chapter two.", chapter = "Two", progression = .3),
    )
    assertEquals(listOf("Short intro paragraph", "Chapter Two Body of chapter two"), r.chunks.map { it.text })
    assertEquals(listOf("Intro", "Two"), r.chunks.map { it.chapter })
    assertEquals(listOf(.1, .2), r.chunks.map { it.progression })
  }

  @Test fun `the first element of a chapter starts a new chunk even when it is not a heading`() {
    val r = chunk(el("Last words of one.", chapter = "One"), el("First words of two.", chapter = "Two", chapterStart = true), el("More of two.", chapter = "Two"))
    assertEquals(listOf("Last words of one", "First words of two. More of two"), r.chunks.map { it.text })
    assertEquals(listOf("One", "Two"), r.chunks.map { it.chapter })
  }

  @Test fun `a chapter that starts in a long element still starts its own chunk`() {
    val long = (1..400).joinToString(" ") { "w$it" }
    val r = chunk(el(filler(30), chapter = "One"), el(long, chapter = "Two", chapterStart = true))
    assertEquals(1, r.chunks.count { it.chapter == "One" })
    assertFalse(r.chunks.first().text.contains("w1"))
  }

  @Test fun `a heading at the start of a resource does not leave an empty chunk before it`() {
    val r = chunk(el("Title", heading = true), el("Text."), el("Next", heading = true), el("More text."))
    assertEquals(listOf("Title Text", "Next More text"), r.chunks.map { it.text })
  }

  @Test fun `an open small chunk is filled from a following element too long for any chunk`() {
    val intro = el("Short lead-in.", progression = .1)
    val long = el(filler(400), progression = .2) // 1999 chars
    val r = chunk(intro, long)
    assertTrue(r.chunks[0].text.startsWith("Short lead-in. abcd"))
    r.chunks.dropLast(1).forEach { assertTrue(it.text.length in MIN..MAX) }
    assertEquals(listOf(MappingCodec.stored(.1), MappingCodec.stored(.2)), r.chunks[0].segments.map { it.progression })
    assertNull(r.chunks[0].seam)
    assertTrue("every later part continues the split element", r.chunks.drop(1).all { it.seam != null })
    assertWellFormed(r.chunks)
  }

  @Test fun `a long element is split between tokens into parts that neither lose nor repeat a token`() {
    val text = (0 until 800).joinToString(" ") { "w$it" }
    val r = chunk(el(text))
    assertTrue(r.chunks.size >= 5)
    r.chunks.forEach { assertTrue(it.text.length <= MAX) }
    r.chunks.dropLast(1).forEach { assertTrue(it.text.length >= MIN) }
    assertEquals(words(text), r.chunks.flatMap { words(it.text) })
    for (c in r.chunks) assertTrue(text.contains(c.text))
    assertWellFormed(r.chunks)
  }

  @Test fun `a seam holds the tokens either side of its split, as far as the two chunks go`() {
    val all = (0 until 800).map { "w$it" }
    val r = chunk(el(all.joinToString(" ")))
    var tokenStart = 0
    var previousStart = 0
    for (c in r.chunks) {
      val seam = c.seam
      if (c.seq == 0) assertNull(seam) else {
        assertNotNull(seam)
        val before = words(seam!!.text.substring(0, seam.splitChar))
        val after = words(seam.text.substring(seam.splitChar))
        assertEquals(all.subList(maxOf(previousStart, tokenStart - TextChunker.SEAM_TOKENS), tokenStart), before)
        assertEquals(all.subList(tokenStart, minOf(tokenStart + words(c.text).size, tokenStart + TextChunker.SEAM_TOKENS)), after)
      }
      previousStart = tokenStart
      tokenStart += words(c.text).size
    }
    assertTrue("a 64-token phrase fits either side of a split", TextChunker.SEAM_TOKENS >= FtsQuery.MAX_TOKENS - 1)
  }

  @Test fun `chunks stop at the end of the resource and the next resource starts clean`() {
    val a = "alpha ".repeat(3) + "omega"
    val r = chunk(el(a, href = "a.xhtml"), el("beta ".repeat(3) + "zeta", href = "b.xhtml"))
    assertEquals(listOf("a.xhtml", "b.xhtml"), r.chunks.map { it.href })
    assertEquals(listOf(a, "beta beta beta zeta"), r.chunks.map { it.text })
    assertEquals(listOf(0, 1), r.chunks.map { it.seq })
    assertWellFormed(r.chunks)
  }

  @Test fun `elements are taken one at a time and chunks appear when their resource is complete`() {
    val corpus = randomCorpus(seed = 3, elements = 20, vocabulary = 50).mapIndexed { i, e -> e.copy(href = "r${i / 5}.xhtml") }
    val chunker = TextChunker(minChars = MIN, maxChars = MAX)
    val streamed = ArrayList<IndexChunk>()
    corpus.forEachIndexed { i, e ->
      val out = chunker.add(e)
      if (i % 5 != 0) assertTrue("no chunks while the resource is still open", out.isEmpty())
      if (i % 5 == 0 && i > 0) assertTrue("the previous resource is released", out.isNotEmpty())
      streamed += out
    }
    streamed += chunker.finish()
    assertEquals(chunk(corpus).chunks, streamed)
    assertEquals(4, streamed.map { it.href }.distinct().size)
  }

  /**
   * The search contract, simulated over token lists: an occurrence of a phrase that lies inside one chunk is found in that
   * chunk; one that crosses a split element is found once, by the seam covering the split, for the chunk after it; one that
   * crosses a boundary between two elements in different chunks is not found (documented). Nothing is found twice.
   */
  @Test fun `a phrase inside a chunk or across a split element is found exactly once`() {
    val corpus = randomCorpus(seed = 7, elements = 40, vocabulary = 12, maxWords = 400)
    val chunks = chunk(corpus).chunks
    assertTrue(chunks.size > 10)
    assertTrue(chunks.count { it.seam != null } > 5)
    val all = words(corpus.joinToString(" ") { it.text })
    val chunkStarts = chunks.runningFold(0) { at, c -> at + words(c.text).size }
    val elementStarts = corpus.runningFold(0) { at, e -> at + words(e.text).size }.toSet()
    fun chunkOf(token: Int) = chunkStarts.indexOfLast { it <= token }
    val chunkWords = chunks.map { words(it.text) }
    val seamRows = chunks.mapNotNull { c -> c.seam?.let { s -> Triple(c.seq, words(s.text), words(s.text.substring(0, s.splitChar)).size) } }

    var crossing = 0
    var queries = 0
    for (length in listOf(1, 2, 12, 64)) {
      for (boundary in chunkStarts.drop(1).dropLast(1)) {
        for (before in listOf(1, length / 2, length - 1).filter { it in 1..length }.distinct()) {
          val start = boundary - before
          if (start < 0 || start + length > all.size) continue
          val phrase = all.subList(start, start + length)
          queries++
          val occurrences = (0..all.size - length).filter { all.subList(it, it + length) == phrase }
          val expected = occurrences.mapNotNull { p ->
            val first = chunkOf(p)
            val last = chunkOf(p + length - 1)
            when {
              first == last -> first
              last == first + 1 && chunkStarts[last] !in elementStarts -> last.also { crossing++ } // across a split element
              else -> null // across an element boundary that is also a chunk boundary
            }
          }
          val found = ArrayList<Int>()
          chunkWords.forEachIndexed { seq, w -> repeat((0..w.size - length).count { w.subList(it, it + length) == phrase }) { found += seq } }
          for ((seq, w, split) in seamRows) {
            repeat((0..w.size - length).count { it < split && it + length > split && w.subList(it, it + length) == phrase }) { found += seq }
          }
          assertEquals("phrase at token $start, length $length", expected.sorted(), found.sorted())
        }
      }
    }
    assertTrue(queries > 100)
    assertTrue("the seams were exercised", crossing > 20)
  }

  @Test fun `a run of CJK characters is one token so it is never split across chunks even when longer than a chunk`() {
    val run = "天下大勢分久必合合久必分".repeat(150) // 1,800 chars, no space or punctuation
    val r = chunk(el("Before the run."), el("$run。And after it, more text."), el("Another paragraph here."))
    assertTrue(r.chunks.count { run in it.text } == 1)
    assertTrue(r.chunks.all { c -> CjkGrams.runs(c.text).all { it == run || it.length < run.length } })
    // every query run is therefore inside one chunk, so the bigram index of that chunk finds it
    val hit = r.chunks.single { run in it.text }
    assertTrue(CjkGrams.grams(hit.text)!!.split(' ').containsAll(listOf("天下", "勢分", "必分")))
  }

  @Test fun `highlight markers are removed from indexed text`() {
    val c = chunk(el("ab cd")).chunks.single()
    assertEquals("ab cd", c.text)
  }

  @Test fun `the per-book cap stops at a chunk boundary and says so`() {
    val corpus = randomCorpus(seed = 11, elements = 80, vocabulary = 400)
    val full = chunk(corpus)
    assertFalse(full.truncated)
    val total = full.chunks.sumOf { it.storedBytes.toLong() }

    val cap = total / 2
    val capped = chunk(corpus, cap)
    assertTrue(capped.truncated)
    assertTrue(capped.chunks.isNotEmpty())
    val used = capped.chunks.sumOf { it.storedBytes.toLong() }
    assertTrue(used <= cap)
    assertEquals("whole chunks, exactly as without a cap", full.chunks.take(capped.chunks.size), capped.chunks)
    assertTrue("stopped because the next chunk would not fit", used + full.chunks[capped.chunks.size].storedBytes > cap)
  }

  @Test fun `a book that fits exactly is not truncated and nothing more is accepted after truncation`() {
    val corpus = randomCorpus(seed = 5, elements = 30, vocabulary = 100)
    val total = chunk(corpus).chunks.sumOf { it.storedBytes.toLong() }
    assertFalse(chunk(corpus, total).truncated)
    assertTrue(chunk(corpus, total - 1).truncated)

    val chunker = TextChunker(maxStoredBytes = 10)
    assertEquals(emptyList<IndexChunk>(), chunker.add(el("This single chunk is bigger than the cap.")))
    assertEquals(emptyList<IndexChunk>(), chunker.finish())
    assertTrue(chunker.truncated)
    assertEquals(emptyList<IndexChunk>(), chunker.add(el("More text.", href = "other.xhtml")))
    assertEquals(0L, chunker.storedBytes)
  }

  @Test fun `the cap counts bytes, so multibyte text uses more of it than chars`() {
    val chunker = TextChunker()
    val chunks = chunker.add(el("Zażółć gęślą jaźń 日本語 𝒜b")) + chunker.finish()
    val c = chunks.single()
    assertTrue(c.text.toByteArray().size > c.text.length)
    assertEquals(c.text.toByteArray().size + c.mapping.size, c.storedBytes)
    assertEquals(chunks.sumOf { it.storedBytes.toLong() }, chunker.storedBytes)
  }

  @Test fun `multibyte text keeps char offsets and mapping consistent`() {
    val unit = "Zażółć gęślą jaźń, 日本語のテキスト 𝒜b ñandú."
    val elements = List(30) { el(List(12) { unit }.joinToString(" "), chapter = "c$it", progression = it / 30.0) }
    val r = chunk(elements)
    assertTrue(r.chunks.size > 5)
    assertWellFormed(r.chunks)
    for (c in r.chunks) for (s in c.segments) {
      val piece = c.text.substring(s.charStart, s.charEnd).trimEnd()
      assertTrue("segment text is element text", elements.any { it.text.contains(piece) })
    }
  }

  private companion object {
    const val MIN = 600
    const val MAX = 900
  }
}
