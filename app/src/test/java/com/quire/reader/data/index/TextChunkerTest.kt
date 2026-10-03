package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextChunkerTest {
  private fun el(text: String, href: String = "ch1.xhtml", heading: Boolean = false, chapter: String = "One", progression: Double = 0.0) =
    SourceElement(href, text, heading, """{"e":"${text.take(12)}"}""", progression, chapter)

  private fun chunk(vararg elements: SourceElement) = TextChunker.chunk(elements.toList())

  private fun words(text: String) = Tokenizer.tokenize(text).map { text.substring(it.startChar, it.endChar) }

  /** The chunk's own text, without the context that follows it. */
  private fun primary(c: IndexChunk) = c.text.toByteArray(Charsets.UTF_8).copyOfRange(0, c.primaryEndByte).toString(Charsets.UTF_8)

  /** A run of `n` four-character words, 5n-1 chars long. */
  private fun filler(n: Int, word: String = "abcd") = List(n) { word }.joinToString(" ")

  private fun assertWellFormed(chunks: List<IndexChunk>) {
    chunks.forEachIndexed { i, c ->
      assertEquals(i, c.seq)
      if (i > 0) assertEquals("tokens are numbered without gaps", chunks[i - 1].tokenEnd, c.tokenStart)
      val tokens = Tokenizer.tokenize(c.text)
      assertEquals("primary text ends exactly after its last token", c.primaryEndByte, tokens[c.tokenEnd - c.tokenStart - 1].endByte)
      assertEquals(c.text.toByteArray().size + c.mappingJson.toByteArray().size, c.storedBytes)
      assertEquals(c.segments, MappingCodec.decode(c.mappingJson))
    }
  }

  private fun randomCorpus(seed: Int, elements: Int, vocabulary: Int): List<SourceElement> {
    val rnd = Random(seed)
    return List(elements) { i ->
      val text = List(rnd.nextInt(20, 121)) { "v${rnd.nextInt(vocabulary)}" }.joinToString(" ")
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
    val a = el("Alpha beta.")
    val b = el("Gamma delta.")
    val c = el("Epsilon.")
    val r = chunk(a, b, c)
    val only = r.chunks.single()
    assertEquals("Alpha beta. Gamma delta. Epsilon", only.text) // a chunk ends with its last token
    assertEquals(only.text.length, only.primaryEndByte)
    assertEquals(
      listOf(
        MappingSegment(0, 11, 0, a.locatorJson),
        MappingSegment(12, 24, 0, b.locatorJson),
        MappingSegment(25, 32, 0, c.locatorJson),
      ),
      only.segments,
    )
    assertEquals(0, only.tokenStart)
    assertEquals(5, only.tokenEnd)
  }

  @Test fun `merging stops before a chunk would pass the maximum`() {
    val e = filler(80) // 399 chars
    val r = chunk(el(e), el(e), el(e))
    assertEquals(listOf(799, 399), r.chunks.map { primary(it).length })
    assertWellFormed(r.chunks)
  }

  @Test fun `a heading starts a new chunk and the chunk takes the heading's chapter`() {
    val r = chunk(
      el("Short intro paragraph.", chapter = "Intro", progression = .1),
      el("Chapter Two", heading = true, chapter = "Two", progression = .2),
      el("Body of chapter two.", chapter = "Two", progression = .3),
    )
    assertEquals(listOf("Short intro paragraph", "Chapter Two Body of chapter two"), r.chunks.map { primary(it) })
    assertEquals(listOf("Intro", "Two"), r.chunks.map { it.chapter })
    assertEquals(listOf(.1, .2), r.chunks.map { it.progression })
    // the intro chunk still carries what follows it as searchable context, which is not its own text
    val context = r.chunks[0].segments.filter { it.byteStart >= r.chunks[0].primaryEndByte }
    assertEquals(2, context.size)
  }

  @Test fun `the first element of a chapter starts a new chunk even when it is not a heading`() {
    // Small neighbouring elements would merge; the chapter boundary must keep them apart so every chunk has one chapter.
    val r = chunk(
      el("Last words of one.", chapter = "One"),
      SourceElement("ch1.xhtml", "First words of two.", false, """{"e":"two"}""", 0.0, "Two", chapterStart = true),
      el("More of two.", chapter = "Two"),
    )
    assertEquals(listOf("Last words of one", "First words of two. More of two"), r.chunks.map { primary(it) })
    assertEquals(listOf("One", "Two"), r.chunks.map { it.chapter })
  }

  @Test fun `a chapter that starts in the middle of a long element still starts its own chunk`() {
    val long = (1..400).joinToString(" ") { "w$it" }
    val r = chunk(
      el(filler(30), chapter = "One"),
      SourceElement("ch1.xhtml", long, false, """{"e":"long"}""", 0.0, "Two", chapterStart = true),
    )
    assertEquals("One", r.chunks.first().chapter)
    assertEquals(setOf("One", "Two"), r.chunks.map { it.chapter }.toSet())
    assertEquals(1, r.chunks.count { it.chapter == "One" })
    assertTrue("the first chapter's chunk holds none of the second chapter's own words", !primary(r.chunks.first()).contains("w1"))
  }

  @Test fun `a heading at the start of a resource does not leave an empty chunk before it`() {
    val r = chunk(el("Title", heading = true), el("Text."), el("Next", heading = true), el("More text."))
    assertEquals(listOf("Title Text", "Next More text"), r.chunks.map { primary(it) })
  }

  @Test fun `an open small chunk is filled from the following long element`() {
    val intro = el("Short lead-in.")
    val long = el(filler(400)) // 1999 chars
    val r = chunk(intro, long)
    assertTrue(primary(r.chunks[0]).startsWith("Short lead-in. abcd"))
    r.chunks.dropLast(1).forEach { assertTrue(primary(it).length in 600..900) }
    assertEquals(listOf(intro.locatorJson, long.locatorJson), r.chunks[0].segments.take(2).map { it.locatorJson })
    assertWellFormed(r.chunks)
  }

  @Test fun `a long element is split between tokens and every part keeps its source range`() {
    val text = (0 until 800).joinToString(" ") { "w$it" }
    val long = el(text)
    val r = chunk(long)
    assertTrue(r.chunks.size >= 5)
    r.chunks.forEach { assertTrue(primary(it).length <= 900) }
    r.chunks.dropLast(1).forEach { assertTrue(primary(it).length >= 600) }
    // the parts, taken together, are exactly the element's tokens: nothing split, lost or doubled
    assertEquals(words(text), r.chunks.flatMap { words(primary(it)) })
    // and each part's mapping points at where its text really sits in the element
    for (c in r.chunks) {
      val first = c.segments.first()
      assertEquals(long.locatorJson, first.locatorJson)
      assertTrue(text.startsWith(c.text, first.charStart))
    }
    assertWellFormed(r.chunks)
  }

  @Test fun `context after a chunk is the next tokens, up to the limit, across chunks`() {
    val all = (0 until 400).map { "w$it" }
    val r = chunk(el(all.joinToString(" ")))
    for (c in r.chunks) {
      val contextTokens = minOf(TextChunker.CONTEXT_TOKENS, all.size - c.tokenEnd)
      assertEquals(all.subList(c.tokenStart, c.tokenEnd + contextTokens), words(c.text))
    }
    assertTrue("context must cover a 64-token phrase starting on the last primary token", TextChunker.CONTEXT_TOKENS >= FtsQuery.MAX_TOKENS - 1)
  }

  @Test fun `context stops at the end of the resource and the next resource starts clean`() {
    val a = "alpha ".repeat(3) + "omega"
    val r = chunk(el(a, href = "a.xhtml"), el("beta ".repeat(3) + "zeta", href = "b.xhtml"))
    assertEquals(listOf("a.xhtml", "b.xhtml"), r.chunks.map { it.href })
    assertEquals(a, r.chunks[0].text)
    assertEquals("beta beta beta zeta", r.chunks[1].text)
    assertEquals(listOf(0, 1), r.chunks.map { it.seq })
    assertWellFormed(r.chunks)
  }

  @Test fun `elements are taken one at a time and chunks appear when their resource is complete`() {
    val corpus = randomCorpus(seed = 3, elements = 20, vocabulary = 50).mapIndexed { i, e -> e.copy(href = "r${i / 5}.xhtml") }
    val chunker = TextChunker()
    val streamed = ArrayList<IndexChunk>()
    corpus.forEachIndexed { i, e ->
      val out = chunker.add(e)
      if (i % 5 != 0) assertTrue("no chunks while the resource is still open", out.isEmpty())
      if (i % 5 == 0 && i > 0) assertTrue("the previous resource is released", out.isNotEmpty())
      streamed += out
    }
    streamed += chunker.finish()
    assertEquals(TextChunker.chunk(corpus).chunks, streamed)
    assertEquals(4, streamed.map { it.href }.distinct().size)
  }

  @Test fun `a phrase at any chunk or element boundary belongs to exactly the chunks that start it`() {
    val corpus = randomCorpus(seed = 7, elements = 60, vocabulary = 12)
    val chunks = TextChunker.chunk(corpus).chunks
    assertTrue(chunks.size > 10)
    val all = words(corpus.joinToString(" ") { it.text })

    val elementStarts = corpus.runningFold(0) { at, e -> at + words(e.text).size }.dropLast(1)
    val boundaries = (chunks.map { it.tokenEnd }.dropLast(1) + elementStarts).distinct()
    val rows = chunks.map { c -> c to Tokenizer.tokenize(c.text).let { t -> t.map { c.text.substring(it.startChar, it.endChar) } to t } }

    var queries = 0
    var rawRows = 0
    var ownedRows = 0
    for (length in listOf(1, 2, 12, 64)) {
      for (boundary in boundaries) {
        for (before in listOf(1, length / 2, length - 1).filter { it in 1..length }.distinct()) {
          val start = boundary - before
          if (start < 0 || start + length > all.size) continue
          val phrase = all.subList(start, start + length)
          queries++

          // what the user should see: each chunk whose own text contains the start of an occurrence
          val expected = (0..all.size - length).filter { all.subList(it, it + length) == phrase }
            .map { p -> chunks.single { p >= it.tokenStart && p < it.tokenEnd }.seq }.toSet()

          // what FTS plus the ownership rule would return: rows containing the phrase, kept when its first token starts in primary text
          val owned = HashSet<Int>()
          for ((c, wordsAndTokens) in rows) {
            val (w, t) = wordsAndTokens
            val at = (0..w.size - length).firstOrNull { w.subList(it, it + length) == phrase } ?: continue
            rawRows++
            if (t[at].startByte < c.primaryEndByte) { owned += c.seq; ownedRows++ }
          }
          assertEquals("phrase at token $start, length $length", expected, owned)
        }
      }
    }
    assertTrue(queries > 100)
    assertTrue("overlap really does repeat matches that the rule has to drop", rawRows > ownedRows)
  }

  @Test fun `the per-book cap stops at a chunk boundary and says so`() {
    val corpus = randomCorpus(seed = 11, elements = 80, vocabulary = 400)
    val full = TextChunker.chunk(corpus)
    assertFalse(full.truncated)
    val total = full.chunks.sumOf { it.storedBytes.toLong() }

    val cap = total / 2
    val capped = TextChunker.chunk(corpus, maxStoredBytes = cap)
    assertTrue(capped.truncated)
    assertTrue(capped.chunks.isNotEmpty())
    val used = capped.chunks.sumOf { it.storedBytes.toLong() }
    assertTrue(used <= cap)
    assertEquals("whole chunks, each with its full context, exactly as without a cap", full.chunks.take(capped.chunks.size), capped.chunks)
    assertTrue("stopped because the next chunk would not fit", used + full.chunks[capped.chunks.size].storedBytes > cap)
  }

  @Test fun `a book that fits exactly is not truncated and nothing more is accepted after truncation`() {
    val corpus = randomCorpus(seed = 5, elements = 30, vocabulary = 100)
    val total = TextChunker.chunk(corpus).chunks.sumOf { it.storedBytes.toLong() }
    assertFalse(TextChunker.chunk(corpus, maxStoredBytes = total).truncated)
    assertTrue(TextChunker.chunk(corpus, maxStoredBytes = total - 1).truncated)

    val chunker = TextChunker(maxStoredBytes = 10)
    assertEquals(emptyList<IndexChunk>(), chunker.add(el("This single chunk is bigger than the cap.")))
    assertEquals(emptyList<IndexChunk>(), chunker.finish())
    assertTrue(chunker.truncated)
    assertEquals(emptyList<IndexChunk>(), chunker.add(el("More text.", href = "other.xhtml")))
    assertEquals(0L, chunker.storedBytes)
  }

  @Test fun `the cap counts bytes, so multibyte text and its mapping use more of it than chars`() {
    val r = chunk(el("Zażółć gęślą jaźń 日本語 𝒜b"))
    val c = r.chunks.single()
    assertTrue(c.text.toByteArray().size > c.text.length)
    assertEquals(c.text.toByteArray().size + c.mappingJson.toByteArray().size, c.storedBytes)

    val chunker = TextChunker()
    val chunks = chunker.add(el("Zażółć gęślą jaźń 日本語 𝒜b")) + chunker.finish()
    assertEquals(chunks.sumOf { it.storedBytes.toLong() }, chunker.storedBytes)
  }

  @Test fun `multibyte text keeps byte offsets, token numbering and mapping consistent`() {
    val unit = "Zażółć gęślą jaźń, 日本語のテキスト 𝒜b ñandú."
    val elements = List(30) { el(List(12) { unit }.joinToString(" "), chapter = "c$it") }
    val r = TextChunker.chunk(elements)
    assertTrue(r.chunks.size > 5)
    assertWellFormed(r.chunks)
    val byLocator = elements.associateBy { it.locatorJson }
    for (c in r.chunks) {
      val bytes = c.text.toByteArray(Charsets.UTF_8)
      for (s in c.segments) {
        val piece = bytes.copyOfRange(s.byteStart, s.byteEnd).toString(Charsets.UTF_8)
        assertTrue("segment text is the element's text from charStart", byLocator.getValue(s.locatorJson).text.startsWith(piece, s.charStart))
      }
    }
  }
}
