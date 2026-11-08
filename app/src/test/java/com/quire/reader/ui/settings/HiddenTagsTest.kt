package com.quire.reader.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenTagsTest {
  private val all = listOf("Classic", "Downloads", "Fiction", "Gothic", "science fiction")

  @Test fun `hidden tags come first, then the rest in order`() {
    assertEquals(listOf("Fiction", "Gothic", "Classic", "Downloads", "science fiction"), hiddenTagChoices(all, setOf("Gothic", "Fiction"), ""))
  }

  @Test fun `the search matches any part of a tag, ignoring case`() {
    assertEquals(listOf("Fiction", "science fiction"), hiddenTagChoices(all, emptySet(), "FIC"))
    assertEquals(listOf("Fiction", "science fiction"), hiddenTagChoices(all, setOf("Fiction"), " fic "))
    assertEquals(emptyList<String>(), hiddenTagChoices(all, emptySet(), "zzz"))
  }

  @Test fun `a hidden tag no book has any more can still be shown again`() {
    assertEquals(listOf("Old"), hiddenTagChoices(all, setOf("Old"), "ol"))
  }

  @Test fun `the summary counts hidden tags`() {
    assertEquals("No tags hidden", hiddenTagsSummary(0))
    assertEquals("1 tag hidden", hiddenTagsSummary(1))
    assertEquals("4 tags hidden", hiddenTagsSummary(4))
  }
}
