package com.quire.reader.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TagLinesTest {
  @Test fun `no chips make no lines`() {
    assertEquals(emptyList<IntRange>(), packLines(IntArray(0), 100, 8))
  }

  @Test fun `chips that fit share one line`() {
    assertEquals(listOf(0..2), packLines(intArrayOf(20, 20, 20), 100, 8))
  }

  @Test fun `the gap goes between chips, not after the last`() {
    // 30 + 8 + 30 + 8 + 24 = 100 exactly.
    assertEquals(listOf(0..2), packLines(intArrayOf(30, 30, 24), 100, 8))
    assertEquals(listOf(0..1, 2..2), packLines(intArrayOf(30, 30, 25), 100, 8))
  }

  @Test fun `lines fill in order`() {
    assertEquals(listOf(0..1, 2..3, 4..4), packLines(intArrayOf(40, 40, 60, 30, 90), 100, 8))
  }

  @Test fun `a chip wider than the line gets a line to itself`() {
    assertEquals(listOf(0..0, 1..1, 2..3), packLines(intArrayOf(30, 150, 30, 30), 100, 8))
  }

  private val tags = listOf("Fantasy" to 12, "Science Fiction" to 7, "fiction" to 5, "Poetry" to 2)

  @Test fun `a blank search keeps every tag`() {
    assertSame(tags, matchingTags(tags, ""))
    assertSame(tags, matchingTags(tags, "   "))
  }

  @Test fun `a search keeps the tags containing it, ignoring case and spaces around it, in their order`() {
    assertEquals(listOf("Science Fiction" to 7, "fiction" to 5), matchingTags(tags, " FICTION "))
    assertEquals(listOf("Fantasy" to 12), matchingTags(tags, "tas"))
  }

  @Test fun `a search nothing contains keeps no tags`() {
    assertEquals(emptyList<Pair<String, Int>>(), matchingTags(tags, "history"))
  }
}
