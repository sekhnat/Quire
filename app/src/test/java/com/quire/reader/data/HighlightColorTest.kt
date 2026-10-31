package com.quire.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightColorTest {
  @Test fun `violet comes first and is the tint every highlight had before there was a choice`() {
    assertEquals(HighlightColor.Violet, HighlightColor.fromIndex(0))
    assertEquals(0xFF9184D9.toInt(), HighlightColor.Violet.tint)
  }

  @Test fun `an index from a newer build or a damaged file reads as violet`() {
    assertEquals(HighlightColor.Violet, HighlightColor.fromIndex(HighlightColor.entries.size))
    assertEquals(HighlightColor.Violet, HighlightColor.fromIndex(-1))
  }

  @Test fun `every colour is opaque and distinct`() {
    assertTrue(HighlightColor.entries.all { (it.tint ushr 24) == 0xFF })
    assertEquals(HighlightColor.entries.size, HighlightColor.entries.map { it.tint }.toSet().size)
  }
}
