package com.quire.reader.data.scan

import com.quire.reader.data.TagKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fixtures from `tools/fixtures/make_ao3_epub.py`. */
class Ao3PrefaceTest {
  private fun fixture(name: String): File = File(requireNotNull(javaClass.getResource("/epub/$name")).toURI())

  private fun Ao3Work.of(kind: TagKind) = tags.filter { it.second == kind }.map { it.first }

  @Test fun `a finished work's preface types every tag`() {
    val w = requireNotNull(Ao3Preface.read(fixture("ao3_work.epub")))
    assertEquals(listOf("Mature"), w.of(TagKind.Rating))
    assertEquals(listOf("No Archive Warnings Apply"), w.of(TagKind.Warning))
    assertEquals(listOf("F/F"), w.of(TagKind.Category))
    assertEquals(listOf("Starlight Harbor (TV 2031)"), w.of(TagKind.Fandom))
    assertEquals(listOf("Mira Vale/June Okafor"), w.of(TagKind.Relationship))
    assertEquals(listOf("Mira Vale", "June Okafor", "Captain Ines Roth"), w.of(TagKind.Character))
    assertEquals(listOf("Alternate Universe - No Powers", "Fake/Pretend Relationship", "Slow Burn"), w.of(TagKind.Freeform))
    assertEquals(listOf("Harbor Fest 2021", "Favs"), w.of(TagKind.Collection))
    // Language is not a tag.
    assertFalse(w.tags.any { it.first == "English" })
  }

  @Test fun `a finished work's series and stats`() {
    val w = Ao3Preface.read(fixture("ao3_work.epub"))!!
    assertEquals("the harbor cycle", w.series)
    assertEquals(2.0, w.seriesPart!!, 0.0)
    assertEquals("2021-01-02", w.published)
    assertEquals("2021-03-04", w.completed)
    assertNull(w.updated)
    assertEquals(12345, w.words)
    assertEquals(6, w.chaptersPosted)
    assertEquals(6, w.chaptersTotal)
    assertTrue(w.hasStats)
  }

  @Test fun `plural labels, entities, an escaped href and a work in progress`() {
    val w = Ao3Preface.read(fixture("ao3_wip.epub"))!!
    assertEquals(listOf("Graphic Depictions Of Violence", "Major Character Death"), w.of(TagKind.Warning))
    assertEquals(listOf("Gen", "Multi"), w.of(TagKind.Category))
    assertEquals(listOf("Starlight Harbor (TV 2031)", "Night Ferry (Comics)"), w.of(TagKind.Fandom))
    assertEquals(listOf("Mira Vale & June Okafor"), w.of(TagKind.Relationship))
    assertEquals("2022-07-08", w.updated)
    assertEquals(4321, w.words)
    assertEquals(3, w.chaptersPosted)
    assertNull(w.chaptersTotal)
    assertNull(w.series)
  }

  @Test fun `an AO3 book without a tag list reads as AO3 with nothing typed`() {
    val w = Ao3Preface.read(fixture("ao3_no_preface.epub"))!!
    assertTrue(w.tags.isEmpty())
    assertFalse(w.hasStats)
  }

  @Test fun `a book from another publisher is not read`() {
    assertNull(Ao3Preface.read(fixture("not_ao3.epub")))
    assertNull(Ao3Preface.read(fixture("links.epub")))
  }

  @Test fun `a file that is not an EPUB reads as nothing`() {
    val junk = File.createTempFile("junk", ".epub").apply { writeText("not a zip"); deleteOnExit() }
    assertNull(Ao3Preface.read(junk))
  }

  @Test fun `labels are compared without colon, case or plural`() {
    assertEquals("archive warning", Ao3Preface.labelKey(" Archive Warnings: "))
    assertEquals("category", Ao3Preface.labelKey("Categories:"))
    assertEquals("character", Ao3Preface.labelKey("Character:"))
    assertEquals("series", Ao3Preface.labelKey("Series:"))
    assertEquals("additional tag", Ao3Preface.labelKey("Additional Tags:"))
  }

  @Test fun `stats are read from AO3's one block of text`() {
    val s = Ao3Preface.parseStats("Published: 2020-07-11\n  Completed: 2020-08-17\n  Words: 59,904\n  Chapters: 6/6")
    assertEquals(mapOf("published" to "2020-07-11", "completed" to "2020-08-17", "words" to "59,904", "chapters" to "6/6"), s)
  }

  @Test fun `a page without a tag list is no preface`() {
    assertNull(Ao3Preface.parsePage("<html><body><dl><dt>Term</dt><dd>Meaning</dd></dl></body></html>"))
  }

  @Test fun `tags without links are split on commas`() {
    val w = Ao3Preface.parsePage("<dl><dt>Fandom:</dt><dd>One Show, Another Show</dd><dt>Rating:</dt><dd>Explicit</dd></dl>")!!
    assertEquals(listOf("One Show", "Another Show"), w.of(TagKind.Fandom))
    assertEquals(listOf("Explicit"), w.of(TagKind.Rating))
  }
}
