package com.quire.reader.ui

import com.quire.reader.data.TagKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagQueryTest {
  @Test fun `a query without terms is all text`() {
    assertEquals(TagQuery(emptyList(), "the hobbit"), parseTagQuery(" the hobbit "))
  }

  @Test fun `typed terms are split out, quoted or not`() {
    val q = parseTagQuery("""fandom:starlight ship:"Mira Vale/June Okafor" slow burn""")
    assertEquals(listOf(TagTerm(TagKind.Fandom, "starlight"), TagTerm(TagKind.Relationship, "mira vale/june okafor")), q.terms)
    assertEquals("slow burn", q.text)
  }

  @Test fun `every prefix names its kind`() {
    assertEquals(TagKind.Freeform, parseTagQuery("tag:au").terms.single().kind)
    assertEquals(TagKind.Character, parseTagQuery("char:mira").terms.single().kind)
    assertEquals(TagKind.Warning, parseTagQuery("Warnings:none").terms.single().kind)
    assertEquals(TagKind.Collection, parseTagQuery("collection:fest").terms.single().kind)
  }

  @Test fun `an unknown word before a colon stays text`() {
    val q = parseTagQuery("Re:Zero rating:teen")
    assertEquals("Re:Zero", q.text)
    assertEquals(listOf(TagTerm(TagKind.Rating, "teen")), q.terms)
  }

  @Test fun `an open quote or an empty value still searches`() {
    assertEquals(TagTerm(TagKind.Fandom, "starlight har"), parseTagQuery("fandom:\"Starlight Har").terms.single())
    assertEquals(TagTerm(TagKind.Fandom, ""), parseTagQuery("fandom:").terms.single())
  }

  @Test fun `a book matches when it has a tag of each term's kind`() {
    val b = testBook(1, "Lanterns", tags = listOf("Starlight Harbor", "Mira/June", "Starlight"), tagKinds = mapOf("Starlight Harbor" to TagKind.Fandom, "Mira/June" to TagKind.Relationship))
    assertTrue(parseTagQuery("fandom:harbor").matches(b))
    assertTrue(parseTagQuery("fandom: ship:june").matches(b))
    assertFalse(parseTagQuery("character:mira").matches(b))
    // "Starlight" is a plain tag, not a relationship.
    assertFalse(parseTagQuery("ship:starlight").matches(b))
  }
}
