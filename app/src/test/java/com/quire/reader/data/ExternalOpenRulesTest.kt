package com.quire.reader.data

import com.quire.reader.OpenBookActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a book file handed over by another app finds its way into the library (spec feature 12). */
class ExternalOpenRulesTest {
  private val roots = listOf(
    WatchedRoot(stored = "/sdcard/Books", canonical = "/storage/emulated/0/Books"),
    WatchedRoot(stored = "/sdcard/Books/Calibre", canonical = "/storage/emulated/0/Books/Calibre"),
    WatchedRoot(stored = "/storage/1A2B-3C4D/Novels", canonical = "/storage/1A2B-3C4D/Novels"),
  )

  @Test
  fun `a file in a watched folder keeps the folder's own spelling`() {
    assertEquals("/sdcard/Books/x.epub", ExternalOpenRules.libraryPath("/storage/emulated/0/Books/x.epub", roots))
    assertEquals("/storage/1A2B-3C4D/Novels/a/b.mobi", ExternalOpenRules.libraryPath("/storage/1A2B-3C4D/Novels/a/b.mobi", roots))
  }

  @Test
  fun `the deepest watched folder wins`() {
    assertEquals(
      "/sdcard/Books/Calibre/Author/Title (1)/book.epub",
      ExternalOpenRules.libraryPath("/storage/emulated/0/Books/Calibre/Author/Title (1)/book.epub", roots),
    )
  }

  @Test
  fun `a file outside every watched folder has no library path`() {
    assertNull(ExternalOpenRules.libraryPath("/storage/emulated/0/Download/x.epub", roots))
    // A sibling whose name only starts like a watched folder is not inside it.
    assertNull(ExternalOpenRules.libraryPath("/storage/emulated/0/BooksOld/x.epub", roots))
    assertNull(ExternalOpenRules.libraryPath("/storage/emulated/0/Books", roots))
  }

  @Test
  fun `a book already in the library opens, by path first, then by fingerprint`() {
    assertEquals(OpenPlan.Open(7), ExternalOpenRules.plan("/sdcard/Books/x.epub", byPath = 7, byFingerprint = 9, removed = false))
    assertEquals(OpenPlan.Open(9), ExternalOpenRules.plan(null, byPath = null, byFingerprint = 9, removed = false))
    // The same file copied elsewhere is the same book: never imported twice.
    assertEquals(OpenPlan.Open(9), ExternalOpenRules.plan("/sdcard/Books/copy.epub", byPath = null, byFingerprint = 9, removed = false))
  }

  @Test
  fun `a removed file is brought back, a new one in a watched folder is scanned in`() {
    assertEquals(OpenPlan.Restore("/sdcard/Books/x.epub"), ExternalOpenRules.plan("/sdcard/Books/x.epub", null, null, removed = true))
    assertEquals(OpenPlan.ScanThenOpen("/sdcard/Books/x.epub"), ExternalOpenRules.plan("/sdcard/Books/x.epub", null, null, removed = false))
  }

  @Test
  fun `anything else is copied in`() {
    assertEquals(OpenPlan.Import, ExternalOpenRules.plan(null, null, null, removed = false))
  }

  @Test
  fun `the share summary names what happened`() {
    assertEquals("3 books added", OpenBookActivity.addedSummary(added = 3, already = 0, failed = 0))
    assertEquals("1 book added · 2 already in your library · 1 couldn't be opened", OpenBookActivity.addedSummary(1, 2, 1))
    assertEquals("2 already in your library", OpenBookActivity.addedSummary(0, 2, 0))
  }
}
