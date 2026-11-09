package com.quire.reader.ui

import com.quire.reader.data.FileOpResult
import com.quire.reader.data.backup.ImportResult
import com.quire.reader.data.backup.MergeOutcome
import com.quire.reader.data.scan.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummariesTest {
  private fun imported(matched: Int = 0, highlights: Int = 0, bookmarks: Int = 0) =
    ImportResult(matched = matched, tombstoned = 0, statesWritten = 0, bookmarksAdded = bookmarks, highlightsAdded = highlights, tagsAdded = 0)

  @Test fun `a scan that changed nothing says the library is up to date`() {
    assertEquals("Library is up to date", describe(ScanResult(0, 0, 0, unreadable = 3)))
  }

  @Test fun `a scan lists what changed, singular and plural`() {
    assertEquals("1 new book · 2 updated · 1 moved · 3 removed", describe(ScanResult(added = 1, updated = 2, removed = 3, unreadable = 0, moved = 1)))
    assertEquals("2 new books", describe(ScanResult(added = 2, updated = 0, removed = 0, unreadable = 0)))
  }

  @Test fun `only additions, removals and moves are worth a quiet notice`() {
    assertFalse(ScanResult(added = 0, updated = 5, removed = 0, unreadable = 0).noticeable())
    assertTrue(ScanResult(added = 0, updated = 0, removed = 0, unreadable = 0, moved = 1).noticeable())
  }

  @Test fun `an import lists what it added`() {
    assertEquals("Nothing to import", importSummary(imported()))
    assertEquals("1 book updated · 2 highlights · 1 bookmark", importSummary(imported(matched = 1, highlights = 2, bookmarks = 1)))
  }

  @Test fun `a merge leads with the books it added`() {
    assertEquals("2 books added", mergeSummary(MergeOutcome(imported(), booksAdded = 2)))
    assertEquals("1 book added · 1 book updated", mergeSummary(MergeOutcome(imported(matched = 1), booksAdded = 1)))
    assertEquals("Nothing to import", mergeSummary(MergeOutcome(imported(), booksAdded = 0)))
  }

  @Test fun `an import says how many picked files could not be read`() {
    assertEquals("Imported 2 books", describeImport(imported = 2, picked = 2))
    assertEquals("Imported 1 book · 1 file couldn’t be read", describeImport(imported = 1, picked = 2))
    assertEquals("Imported 2 books · 3 files couldn’t be read", describeImport(imported = 2, picked = 5))
    assertEquals("Nothing could be imported", describeImport(imported = 0, picked = 2))
  }

  @Test fun `a file action says what it did and what failed`() {
    assertEquals("Copied 2 books to Download", describeFileOp(FileOpResult(2, 0), "Copied", "copy", " to Download"))
    assertEquals("Deleted 1 book · 2 couldn’t be deleted", describeFileOp(FileOpResult(1, 2), "Deleted", "delete"))
    assertEquals("Couldn’t delete the book", describeFileOp(FileOpResult(0, 1), "Deleted", "delete"))
    assertEquals("Nothing to copy", describeFileOp(FileOpResult(0, 0), "Copied", "copy"))
  }

  @Test fun `several files are summed up by count, size and format`() {
    val books = listOf(
      testBook(1, "A").copy(path = "/b/a.epub", sizeBytes = 1024 * 1024),
      testBook(2, "B").copy(path = "/b/b.azw3", sizeBytes = 512 * 1024),
      testBook(3, "C").copy(path = "/b/c.epub", sizeBytes = 512 * 1024),
    )
    assertEquals("3 books · 2.0 MB · 2 EPUB, 1 AZW3", filesSummary(books))
    assertEquals("/Books/a.epub", shownPath("/storage/emulated/0/Books/a.epub"))
  }
}

