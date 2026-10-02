package com.quire.reader.data

import com.quire.reader.data.db.BookRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookModelTest {
  private val day = 24L * 60 * 60 * 1000
  private val now = 1_800_000_000_000L

  private fun row(
    status: String? = null, progress: Float? = null, added: Long = now, calibre: Int = 0, user: Int? = null, tags: String? = null,
    series: String? = null, seriesIndex: Double? = null, author: String = "Arthur Conan Doyle", pages: Int = 300, size: Long = 2_500_000,
  ) = BookRow(
    id = 1, path = "/b/x.epub", folderId = 1, title = "The Sign of the Four", sortTitle = "sign of the four", author = author, primaryAuthor = author,
    authorSort = "doyle, arthur conan", series = series, seriesIndex = seriesIndex, pubYear = 1890, language = "eng", description = null,
    calibreRating = calibre, userRating = user, sizeBytes = size, addedAt = added, pageEstimate = pages, coverPath = null, source = "calibre",
    readable = true, progress = progress, status = status, lastOpenedAt = null, tags = tags,
  )

  @Test fun `tags carry their origin so only Quire's own can be removed`() {
    val r = row(tags = "cMystery\u001FuFavourite\u001FcClassics")
    assertEquals(listOf("Mystery", "Favourite", "Classics"), r.tagList)
    assertEquals(listOf("Favourite"), r.userTagList)
    assertEquals(emptyList<String>(), row(tags = null).tagList)
  }

  @Test fun `the user's rating beats Calibre's`() {
    assertEquals(4, row(calibre = 4).toBook(now).rating)
    assertEquals(2, row(calibre = 4, user = 2).toBook(now).rating)
    assertEquals(0, row(calibre = 4, user = 0).toBook(now).rating) // an explicit "no stars"
  }

  @Test fun `status comes from the saved state, defaulting to unread`() {
    assertEquals(BookStatus.Unread, row().toBook(now).status)
    assertEquals(BookStatus.Reading, row(status = "reading", progress = .4f).toBook(now).status)
    assertEquals(BookStatus.Finished, row(status = "finished", progress = 1f).toBook(now).status)
  }

  @Test fun `only unread books added in the last 30 days are new`() {
    assertTrue(row(added = now - 3 * day).toBook(now).isNew)
    assertFalse(row(added = now - 40 * day).toBook(now).isNew)
    assertFalse(row(status = "reading", progress = .1f, added = now - day).toBook(now).isNew)
  }

  @Test fun `size and series labels read naturally`() {
    assertEquals("2.4 MB", row(size = 2_500_000).toBook(now).sizeLabel)
    assertEquals("512 KB", row(size = 524_288).toBook(now).sizeLabel)
    assertEquals("2", row(series = "S", seriesIndex = 2.0).toBook(now).seriesNoLabel)
    assertEquals("2.5", row(series = "S", seriesIndex = 2.5).toBook(now).seriesNoLabel)
  }

  @Test fun `time left scales with pages and progress`() {
    assertEquals("about 3h 45m left", row(pages = 300).toBook(now).timeLeftLabel())            // 300 × 0.75 min
    assertEquals("about 1h 53m left", row(status = "reading", progress = .5f, pages = 300).toBook(now).timeLeftLabel())
    assertEquals("about 1m left", row(status = "reading", progress = .999f, pages = 300).toBook(now).timeLeftLabel())
  }

  @Test fun `an unknown author shows as Unknown on covers`() {
    assertEquals("Unknown", row(author = "Unknown author").toBook(now).authorLast)
    assertEquals("Doyle", row().toBook(now).authorLast)
  }

  @Test fun `a title always gets the same cover colour`() {
    assertEquals(Ground.forTitle("Dracula"), Ground.forTitle("Dracula"))
  }
}
