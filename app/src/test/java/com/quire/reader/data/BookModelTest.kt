package com.quire.reader.data

import com.quire.reader.data.db.CatalogRow
import com.quire.reader.data.db.ReadingStateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookModelTest {
  private val day = 24L * 60 * 60 * 1000
  private val now = 1_800_000_000_000L

  private fun catalog(
    added: Long = now, calibre: Int = 0, tags: String? = null, series: String? = null, seriesIndex: Double? = null,
    author: String = "Arthur Conan Doyle", pages: Int = 300, size: Long = 2_500_000,
  ) = CatalogRow(
    id = 1, path = "/b/x.epub", folderId = 1, title = "The Sign of the Four", sortTitle = "sign of the four", author = author, primaryAuthor = author,
    authorSort = "doyle, arthur conan", series = series, seriesIndex = seriesIndex, pubYear = 1890, language = "eng", description = null,
    calibreRating = calibre, sizeBytes = size, addedAt = added, pageEstimate = pages, coverPath = null, source = "calibre", readable = true, tags = tags,
  )

  /** A library book as the repository builds it: the catalogue row, then the reading state if the book has one. */
  private fun book(
    status: String? = null, progress: Float? = null, added: Long = now, calibre: Int = 0, user: Int? = null, tags: String? = null,
    series: String? = null, seriesIndex: Double? = null, author: String = "Arthur Conan Doyle", pages: Int = 300, size: Long = 2_500_000,
    lastOpened: Long = 0,
  ): Book {
    val state = if (status != null || progress != null || user != null) ReadingStateRow(1, progress ?: 0f, status ?: "unread", lastOpened, user) else null
    return catalog(added, calibre, tags, series, seriesIndex, author, pages, size).toBook(now).withReadingState(state, now)
  }

  @Test fun `tags carry their origin so only Quire's own can be removed`() {
    val r = catalog(tags = "cMystery\u001FuFavourite\u001FcClassics")
    assertEquals(listOf("Mystery", "Favourite", "Classics"), r.tagList)
    assertEquals(listOf("Favourite"), r.userTagList)
    assertEquals(emptyList<String>(), catalog(tags = null).tagList)
  }

  @Test fun `the user's rating beats Calibre's`() {
    assertEquals(4, book(calibre = 4).rating)
    assertEquals(2, book(calibre = 4, user = 2).rating)
    assertEquals(0, book(calibre = 4, user = 0).rating) // an explicit "no stars"
  }

  @Test fun `status comes from the saved state, defaulting to unread`() {
    assertEquals(BookStatus.Unread, book().status)
    assertEquals(BookStatus.Reading, book(status = "reading", progress = .4f).status)
    assertEquals(BookStatus.Finished, book(status = "finished", progress = 1f).status)
  }

  @Test fun `only unread books added in the last 30 days are new`() {
    assertTrue(book(added = now - 3 * day).isNew)
    assertFalse(book(added = now - 40 * day).isNew)
    assertFalse(book(status = "reading", progress = .1f, added = now - day).isNew)
  }

  @Test fun `size and series labels read naturally`() {
    assertEquals("2.4 MB", book(size = 2_500_000).sizeLabel)
    assertEquals("512 KB", book(size = 524_288).sizeLabel)
    assertEquals("2", book(series = "S", seriesIndex = 2.0).seriesNoLabel)
    assertEquals("2.5", book(series = "S", seriesIndex = 2.5).seriesNoLabel)
  }

  @Test fun `time left scales with pages and progress`() {
    assertEquals("about 3h 45m left", book(pages = 300).timeLeftLabel())            // 300 × 0.75 min
    assertEquals("about 1h 53m left", book(status = "reading", progress = .5f, pages = 300).timeLeftLabel())
    assertEquals("about 1m left", book(status = "reading", progress = .999f, pages = 300).timeLeftLabel())
  }

  @Test fun `an unknown author shows as Unknown on covers`() {
    assertEquals("Unknown", book(author = "Unknown author").authorLast)
    assertEquals("Doyle", book().authorLast)
  }

  @Test fun `a title always gets the same cover colour`() {
    assertEquals(Ground.forTitle("Dracula"), Ground.forTitle("Dracula"))
  }

  @Test fun `reading state joins onto the catalogue book and only replaces what it holds`() {
    val base = catalog(added = now - day, calibre = 4).toBook(now)
    assertSame(base, base.withReadingState(null, now))
    val read = base.withReadingState(ReadingStateRow(1, .5f, "reading", lastOpenedAt = 123, userRating = null), now)
    assertEquals(BookStatus.Reading to .5f, read.status to read.progress)
    assertEquals(123L, read.lastOpened)
    assertEquals(4, read.rating)
    assertFalse(read.isNew)
    assertEquals(base.copy(status = read.status, progress = read.progress, lastOpened = read.lastOpened, isNew = false), read)
  }

  @Test fun `a book stops being new as time passes, without a catalogue change`() {
    val base = catalog(added = now - 29 * day).toBook(now)
    assertTrue(base.isNew)
    assertFalse(base.withReadingState(null, now + 2 * day).isNew)
  }
}
