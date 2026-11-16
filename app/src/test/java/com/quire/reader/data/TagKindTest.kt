package com.quire.reader.data

import com.quire.reader.data.scan.Ao3Work
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagKindTest {
  @Test fun `AO3's fixed tags are typed ignoring case`() {
    assertEquals(TagKind.Rating, Ao3Vocabulary.kindOf("teen and up audiences"))
    assertEquals(TagKind.Warning, Ao3Vocabulary.kindOf(" No Archive Warnings Apply "))
    assertEquals(TagKind.Category, Ao3Vocabulary.kindOf("F/F"))
    assertNull(Ao3Vocabulary.kindOf("Fake/Pretend Relationship"))
  }

  @Test fun `only an archive warning marks a book as AO3`() {
    assertTrue(Ao3Vocabulary.lookLikeAo3(listOf("Fanworks", "Major Character Death")))
    assertFalse(Ao3Vocabulary.lookLikeAo3(listOf("Mature", "Other", "Gen")))
  }

  @Test fun `kinds round-trip through their key and letter`() {
    TagKind.entries.forEach {
      assertEquals(it, TagKind.of(it.key))
      assertEquals(it, TagKind.ofLetter(it.letter))
    }
    assertEquals(TagKind.entries.size, TagKind.entries.map { it.letter }.toSet().size)
    assertNull(TagKind.ofLetter('-'))
    assertEquals(TagKind.entries.toSet(), TagKind.displayOrder.toSet())
  }

  @Test fun `subjects take the preface's kinds, and the preface adds what they lack`() {
    val ao3 = Ao3Work(
      tags = listOf("Mature" to TagKind.Rating, "Starlight Harbor" to TagKind.Fandom, "Night Ferry" to TagKind.Fandom, "Harbor Fest" to TagKind.Collection),
    )
    val tags = scannedTags(listOf("Fanworks", "mature", "Starlight Harbor", "F/F"), ao3)
    assertEquals(
      listOf(
        ScannedTag("Fanworks"), ScannedTag("mature", TagKind.Rating), ScannedTag("Starlight Harbor", TagKind.Fandom),
        // Not in the preface's tag list, but one of AO3's fixed tags.
        ScannedTag("F/F", TagKind.Category),
        ScannedTag("Night Ferry", TagKind.Fandom), ScannedTag("Harbor Fest", TagKind.Collection),
      ),
      tags,
    )
  }

  @Test fun `without a preface only an AO3-looking book gets fixed tags typed`() {
    assertEquals(
      listOf(ScannedTag("Explicit", TagKind.Rating), ScannedTag("Choose Not To Use Archive Warnings", TagKind.Warning), ScannedTag("Night Ferry")),
      scannedTags(listOf("Explicit", "Choose Not To Use Archive Warnings", "Night Ferry"), null),
    )
    assertEquals(listOf(ScannedTag("Mature"), ScannedTag("Other")), scannedTags(listOf("Mature", "Other"), null))
    // The publisher alone (an empty preface) is enough.
    assertEquals(listOf(ScannedTag("Mature", TagKind.Rating)), scannedTags(listOf("Mature"), Ao3Work()))
  }
}
