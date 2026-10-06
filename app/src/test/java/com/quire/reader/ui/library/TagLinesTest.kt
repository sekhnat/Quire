package com.quire.reader.ui.library

import org.junit.Assert.assertEquals
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
}
