package com.quire.reader.data

import com.quire.reader.data.db.BookEditEntity
import com.quire.reader.data.scan.CoverStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Editing a book's title, authors, series and cover in Quire (spec feature 11). */
class BookEditsTest {
  private val none = BookEditEntity(bookId = 3)

  @Test fun `an edited title sorts as the scanner would sort it`() {
    val e = BookEdits.apply(none, EditField.Title, "  The   Hound of the Baskervilles ", now = 9)
    assertEquals("The Hound of the Baskervilles", e.title)
    assertEquals("hound of the baskervilles", e.sortTitle)
    assertEquals(9L, e.updatedAt)
  }

  @Test fun `a blank title is refused, null resets it`() {
    try { BookEdits.apply(none, EditField.Title, "   ", 0); fail() } catch (_: EditRejected) {}
    val edited = BookEdits.apply(none, EditField.Title, "X", 0)
    val reset = BookEdits.apply(edited, EditField.Title, null, 0)
    assertNull(reset.title)
    assertNull(reset.sortTitle)
    assertTrue(reset.isEmpty)
  }

  @Test fun `several authors in one field, separated by ampersands or semicolons`() {
    val e = BookEdits.apply(none, EditField.Authors, "Terry Pratchett & Neil Gaiman; Neil Gaiman", 0)
    assertEquals("Terry Pratchett & Neil Gaiman", e.author)
    assertEquals("Terry Pratchett", e.primaryAuthor)
    assertEquals("pratchett, terry", e.authorSort)
    assertEquals(listOf("Arthur Conan Doyle"), BookEdits.splitAuthors(" Arthur Conan Doyle "))
  }

  @Test fun `series numbers are decimals of at least zero with at most two places`() {
    assertEquals(2.5, BookEdits.parseSeriesIndex("2.5"), 0.0)
    assertEquals(2.5, BookEdits.parseSeriesIndex("2,5"), 0.0)
    assertEquals(0.0, BookEdits.parseSeriesIndex("0"), 0.0)
    assertEquals(1.25, BookEdits.parseSeriesIndex("1.25"), 0.0)
    for (bad in listOf("-1", "two", "1.255", "NaN", "")) {
      try { BookEdits.parseSeriesIndex(bad); fail("accepted '$bad'") } catch (_: EditRejected) {}
    }
  }

  @Test fun `an empty series takes the book out of its series, number and all`() {
    val inSeries = BookEdits.apply(BookEdits.apply(none, EditField.Series, "Discworld", 0), EditField.SeriesIndex, "3", 0)
    assertEquals("Discworld", inSeries.series)
    assertEquals(3.0, inSeries.seriesIndex!!, 0.0)
    val out = BookEdits.apply(inSeries, EditField.Series, "", 0)
    assertEquals("", out.series)
    assertNull(out.seriesIndex)
    // Edited to no series still counts as an edit (it hides the file's series).
    assertTrue(BookEdits.isEdited(out, EditField.Series))
    assertFalse(out.isEmpty)
  }

  @Test fun `which fields are edited`() {
    val e = BookEdits.apply(none, EditField.Title, "X", 0)
    assertTrue(BookEdits.isEdited(e, EditField.Title))
    assertFalse(BookEdits.isEdited(e, EditField.Authors))
    assertFalse(BookEdits.isEdited(null, EditField.Title))
    assertTrue(none.isEmpty)
    assertFalse(none.copy(coverPath = "/c.webp").isEmpty)
  }

  @Test fun `a chosen image is cropped to the 2 by 3 shape of a cover, centred`() {
    // Landscape: full height, a centred slice of the width.
    assertEquals(listOf(633, 0, 666, 999), CoverStore.coverCrop(1932, 1000))
    // Taller than a cover: full width, a centred slice of the height.
    assertEquals(listOf(0, 450, 600, 900), CoverStore.coverCrop(600, 1800))
    // Already 2:3.
    assertEquals(listOf(0, 0, 400, 600), CoverStore.coverCrop(400, 600))
  }
}
