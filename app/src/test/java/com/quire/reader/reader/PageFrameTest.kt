package com.quire.reader.reader

import com.quire.reader.data.BookReaderPrefs
import com.quire.reader.data.PageMargins
import com.quire.reader.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Custom margins (spec feature 3): the presets must keep exactly the layout every book had before. */
class PageFrameTest {

  @Test
  fun `the presets reproduce today's page`() {
    assertEquals(PageMargins(top = 8, bottom = 10, left = 14, right = 14), PageFrame.effectiveMargins(ReaderPrefs(margin = 16)))
    assertEquals(PageMargins(top = 8, bottom = 10, left = 20, right = 20), PageFrame.effectiveMargins(ReaderPrefs(margin = 26)))
    assertEquals(PageMargins(top = 8, bottom = 10, left = 32, right = 32), PageFrame.effectiveMargins(ReaderPrefs(margin = 40)))
    // Space above and below: statusTop + 32 dp and navBottom + 46 dp, as ReaderScreen always had.
    assertEquals(32, PageFrame.topSpace(ReaderPrefs(), showHeader = true))
    assertEquals(46, PageFrame.bottomSpace(ReaderPrefs(), showFooter = true))
    // Readium keeps the same factor and the reader view gets no padding.
    assertEquals(marginFactor(26), PageFrame.readiumFactor(ReaderPrefs(margin = 26)), 0.0)
    assertEquals(0 to 0, PageFrame.sidePadding(ReaderPrefs(margin = 40)))
  }

  @Test
  fun `custom margins put the sides in padding around a small Readium gutter`() {
    val prefs = ReaderPrefs(margins = PageMargins(top = 0, bottom = 20, left = 48, right = 4))
    assertEquals(PageFrame.CUSTOM_GUTTER_FACTOR, PageFrame.readiumFactor(prefs), 0.0)
    assertEquals(42 to 0, PageFrame.sidePadding(prefs))
    assertEquals(24, PageFrame.topSpace(prefs, showHeader = true))
    assertEquals(0, PageFrame.topSpace(prefs, showHeader = false))
    assertEquals(20, PageFrame.bottomSpace(prefs, showFooter = false))
  }

  @Test
  fun `margins snap to the sliders' steps and range`() {
    assertEquals(PageMargins(0, 64, 12, 6), PageFrame.snapped(PageMargins(-3, 99, 11, 5)))
  }

  @Test
  fun `prefs keep custom margins through JSON, and older JSON has none`() {
    val custom = ReaderPrefs(margins = PageMargins(6, 8, 30, 12))
    assertEquals(custom, ReaderPrefs.fromJson(custom.toJson()))
    assertNull(ReaderPrefs.fromJson("""{"theme":"Night","margin":40}""")!!.margins)
    // A preset book's stored JSON carries no margins key: it reads exactly as before custom margins existed.
    org.junit.Assert.assertFalse("margins" in ReaderPrefs(margin = 40).toJson())
  }

  @Test
  fun `a book's own settings carry its custom margins, and a preset book's override clears the default's`() {
    val defaults = ReaderPrefs(margins = PageMargins(6, 8, 30, 12))
    val book = BookReaderPrefs().withBasicFrom(ReaderPrefs(margin = 16))
    assertNull(BookReaderPrefs.fromJson(book.toJson())!!.appliedTo(defaults).margins)
    val customBook = BookReaderPrefs().withBasicFrom(ReaderPrefs(margins = PageMargins(2, 2, 2, 2)))
    assertEquals(PageMargins(2, 2, 2, 2), BookReaderPrefs.fromJson(customBook.toJson())!!.appliedTo(ReaderPrefs()).margins)
    // An advanced-only row keeps inheriting the default's basics, custom margins included.
    assertEquals(defaults.margins, BookReaderPrefs(advanced = defaults.advanced).appliedTo(defaults).margins)
  }
}
