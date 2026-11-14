package com.quire.qdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Looking words up as they appear in books (spec feature 7), against `oewn-2025-slice.xml`: a few lemmas of the real
 * Open English WordNet 2025 (see its LICENSE beside it), with their synsets and the entries of those synsets. The slice
 * goes through the same parser, writer and reader as the shipped dictionary.
 */
class DictionaryTest {
  private val data = javaClass.getResourceAsStream("/oewn-2025-slice.xml")!!.use(WnLmf::read)
  private val reader = QdictReader(ByteArraySource(QdictWriter.write(data, Codec.Deflate, 2048, preset = true)))
  private val dictionary = Dictionary(reader)

  private fun entry(text: String) = dictionary.lookUp(text) as? LookupResult.Entry ?: throw AssertionError("no entry for '$text'")

  @Test fun `the slice converts and reads back exactly`() {
    assertEquals("2025", reader.release)
    assertEquals(data.headwords, reader.allHeadwords().toList())
    assertEquals(data.exceptions, reader.allExceptions().toList())
    data.synsets.forEachIndexed { id, s -> assertEquals(s, reader.synset(id)) }
  }

  @Test fun `a word in its dictionary form`() {
    val run = entry("run")
    assertEquals("run", run.headword)
    assertNull(run.fromForm)
    assertEquals(listOf(Pos.Noun, Pos.Verb), run.senses.keys.toList())
  }

  @Test fun `irregular forms come from the exception table`() {
    assertEquals("run" to "ran", entry("ran").let { it.headword to it.fromForm })
    assertEquals("goose", entry("geese").headword)
    assertEquals("child", entry("children").headword)
    assertEquals("grow", entry("grew").headword)
    assertEquals("big", entry("biggest").headword)
  }

  @Test fun `every detachment rule`() {
    assertEquals("box", entry("boxes").headword) // xes → x
    assertEquals("hope", entry("hoping").headword) // ing → e
    assertEquals("hope", entry("hoped").headword) // ed → e
    assertEquals("study", entry("studies").headword) // ies → y
    assertEquals("satyr", entry("satyrs").headword) // s → ""
    assertEquals("mature", entry("matured").headword)
    assertEquals("grow", entry("grows").headword)
    // "running" is a noun of its own, and the exception table adds the verb "run": both show, noun first.
    val running = entry("running")
    assertEquals("running", running.headword)
    assertEquals(listOf(Pos.Noun, Pos.Verb), running.senses.keys.toList())
  }

  @Test fun `capitals, curly apostrophes, possessives and punctuation`() {
    assertEquals("run", entry("Run").headword)
    assertEquals("child", entry("child’s").headword)
    assertEquals("satyr", entry("satyrs’").headword)
    assertEquals("box", entry("“Boxes,”").headword)
  }

  @Test fun `collocations, and a phrase falls back to its last word it knows`() {
    assertEquals("give up", entry("give up").headword)
    assertEquals("give up", entry("Give  up").headword)
    assertEquals("cat", entry("the black cat").headword)
  }

  @Test fun `hyphenated words try the whole word, then each part`() {
    assertEquals("well-known", entry("well-known").headword)
    assertEquals("happy", entry("happy-go-lucky").headword)
  }

  @Test fun `synonyms are the other words of a meaning, never the word itself`() {
    val mature = entry("mature")
    mature.senses.values.flatten().forEach { assertFalse(it.synonyms.any { s -> s.equals("mature", ignoreCase = true) }) }
    assertTrue(mature.senses.values.flatten().any { it.synonyms.isNotEmpty() })
  }

  @Test fun `words with no entry`() {
    assertTrue(dictionary.lookUp("xyzzy") is LookupResult.NotFound)
    assertTrue(dictionary.lookUp("") is LookupResult.NotFound)
    assertTrue(dictionary.lookUp("1234") is LookupResult.NotFound)
  }

  @Test fun `the selection rules`() {
    assertEquals("give up", Selections.normalise("  give\n up  "))
    assertEquals("don't", Selections.normalise("don’t"))
    assertEquals("child", Selections.normalise("(child’s)"))
    assertNull(Selections.normalise("—"))
    assertNull(Selections.normalise("a".repeat(Selections.MAX_CHARS + 1)))
    assertNull(Selections.normalise("one two three four five six"))
    // CJK has no spaces: kept as one piece (no English card will find it, which is right).
    assertEquals("我們", Selections.normalise("我們"))
  }

  @Test fun `the language rule`() {
    assertTrue(Selections.isEnglish("en"))
    assertTrue(Selections.isEnglish("en-GB"))
    assertTrue(Selections.isEnglish("EN_us"))
    assertTrue(Selections.isEnglish(null))
    assertTrue(Selections.isEnglish(""))
    assertFalse(Selections.isEnglish("fr"))
    assertFalse(Selections.isEnglish("eng-x"))
  }
}
