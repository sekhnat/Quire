package com.quire.reader.ui.reader

import com.quire.reader.data.ReaderControls
import com.quire.reader.data.StatusItem
import com.quire.reader.data.StatusLine
import com.quire.reader.data.StatusSlot
import com.quire.reader.data.StatusSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The adjustable status line (spec feature 4). */
class StatusLineTextTest {
  private val facts = StatusFacts(
    bookTitle = "The Name of the Wind", chapterTitle = "Chapter 12", clock = "21:47", battery = BatteryState(64, false),
    position = 212, total = 840, pagesLeftInChapter = 9, minutesLeftInBook = 312, progress = 0.25f,
  )

  @Test
  fun `the default line shows exactly what the reader always showed`() {
    val line = StatusLine()
    val header = StatusSlot.entries.map { StatusLineText.of(line.header[it], facts) }
    val footer = StatusSlot.entries.map { StatusLineText.of(line.footer[it], facts) }
    assertEquals(listOf(null, "The Name of the Wind", null), header)
    assertEquals(listOf("Page 212 of 840", null, "5h 12m left"), footer)
  }

  @Test
  fun `every item has its text`() {
    assertEquals("Chapter 12", StatusLineText.of(StatusItem.ChapterTitle, facts))
    assertEquals("21:47", StatusLineText.of(StatusItem.Clock, facts))
    assertEquals("64%", StatusLineText.of(StatusItem.Battery, facts))
    assertEquals("9 pages left in chapter", StatusLineText.of(StatusItem.PagesLeftInChapter, facts))
    assertEquals("1 page left in chapter", StatusLineText.of(StatusItem.PagesLeftInChapter, facts.copy(pagesLeftInChapter = 1)))
    assertEquals("Last page in chapter", StatusLineText.of(StatusItem.PagesLeftInChapter, facts.copy(pagesLeftInChapter = 0)))
    assertEquals("25%", StatusLineText.of(StatusItem.Percent, facts))
    assertEquals("Almost done", StatusLineText.of(StatusItem.TimeLeftInBook, facts.copy(minutesLeftInBook = 0)))
    assertEquals("45 min left", StatusLineText.of(StatusItem.TimeLeftInBook, facts.copy(minutesLeftInBook = 45)))
    assertNull(StatusLineText.of(StatusItem.None, facts))
  }

  @Test
  fun `the battery shows nothing on a phone without one`() {
    assertNull(StatusLineText.of(StatusItem.Battery, facts.copy(battery = null)))
  }

  @Test
  fun `pages left in chapter count up to the next chapter's first page`() {
    // Chapters (TOC entries, nested ones included) start on pages 1, 10, 20 and 20 again (two entries in one place).
    val starts = listOf(1, 10, 20)
    assertEquals(8, StatusLineText.pagesLeftInChapter(starts, here = 1, total = 50))
    assertEquals(0, StatusLineText.pagesLeftInChapter(starts, here = 9, total = 50))
    assertEquals(9, StatusLineText.pagesLeftInChapter(starts, here = 10, total = 50))
    // The last chapter runs to the end of the book.
    assertEquals(30, StatusLineText.pagesLeftInChapter(starts, here = 20, total = 50))
    assertEquals(0, StatusLineText.pagesLeftInChapter(starts, here = 50, total = 50))
    // No table of contents: the whole book is one chapter.
    assertEquals(40, StatusLineText.pagesLeftInChapter(emptyList(), here = 10, total = 50))
    // Before the first entry (front matter not in the contents).
    assertEquals(4, StatusLineText.pagesLeftInChapter(listOf(6, 12), here = 1, total = 50))
  }

  @Test
  fun `the status line survives JSON, and older controls get the default`() {
    val controls = ReaderControls(statusLine = StatusLine(header = StatusSlots(StatusItem.Clock, StatusItem.ChapterTitle, StatusItem.Battery), showFooter = false))
    assertEquals(controls, ReaderControls.fromJson(controls.toJson()))
    assertEquals(StatusLine(), ReaderControls.fromJson("""{"volumeKeys":true}""")!!.statusLine)
  }
}
