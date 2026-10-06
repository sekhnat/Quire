package com.quire.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryLayoutTest {
  @Test fun `a saved layout comes back by name`() {
    LibLayout.entries.forEach { assertEquals(it, libLayoutOf(it.name)) }
  }

  @Test fun `nothing saved or an unknown name is the grid`() {
    assertEquals(LibLayout.Grid, libLayoutOf(null))
    assertEquals(LibLayout.Grid, libLayoutOf("Carousel"))
  }

  @Test fun `a saved sort comes back by name, and nothing saved or an unknown name is Recently opened`() {
    SortKey.entries.forEach { assertEquals(it, sortKeyOf(it.name)) }
    assertEquals(SortKey.Opened, sortKeyOf(null))
    assertEquals(SortKey.Opened, sortKeyOf("Rating"))
  }

  @Test fun `a synopsis runs its paragraphs together`() {
    assertEquals("One. Two. Three", synopsisPreview("  One.\n\n  Two. Three \n"))
    assertEquals("A B", synopsisPreview("A\n   \nB"))
  }

  @Test fun `no synopsis gives no preview`() {
    assertNull(synopsisPreview(null))
    assertNull(synopsisPreview(" \n "))
  }

  @Test fun `author line adds the series and number only when there is a series`() {
    assertEquals("Jane Austen", authorLine(testBook(1, "Emma", "Jane Austen")))
    assertEquals("Arthur Doyle · Holmes 2", authorLine(testBook(2, "Gamma", "Arthur Doyle", series = "Holmes", seriesNo = 2.0)))
  }
}
