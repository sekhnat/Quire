package com.quire.reader.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FolderDiscoveryTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun touch(root: File, path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("") }

  /** Empty, flat Books with 2, Calibre-style nested with 30 (crossing one report step), plus folders discovery skips. */
  private fun storage(): File {
    val root = tmp.newFolder("storage")
    File(root, "Empty").mkdirs()
    touch(root, "Books/a.epub"); touch(root, "Books/b.EPUB"); touch(root, "Books/notes.txt")
    repeat(30) { touch(root, "Calibre Library/Author $it/Title ($it)/book.epub") }
    touch(root, ".hidden/x.epub"); touch(root, "Android/y.epub"); touch(root, "Music/z.epub")
    return root
  }

  @Test fun `finds folders with EPUBs, most first`() {
    val found = FolderDiscovery.discover(listOf(storage()))
    assertEquals(listOf("Calibre Library" to 30, "Books" to 2), found.map { it.name to it.epubCount })
  }

  @Test fun `progress counts top-level folders and EPUBs, and reports each candidate once`() {
    val reports = mutableListOf<Pair<DiscoveryProgress, FolderCandidate?>>()
    FolderDiscovery.discover(listOf(storage())) { p, c -> reports += p to c }

    assertTrue("skipped and hidden folders don't count", reports.all { it.first.total == 3 })
    assertEquals(0, reports.first().first.done)
    val last = reports.last().first
    assertEquals(3, last.done)
    assertEquals(1f, last.fraction)
    assertEquals(32, last.epubs)
    assertEquals(reports.map { it.first.done }.sorted(), reports.map { it.first.done })
    assertEquals(reports.map { it.first.epubs }.sorted(), reports.map { it.first.epubs })
    assertEquals(setOf("Calibre Library", "Books"), reports.mapNotNull { it.second?.name }.toSet())
    assertEquals(2, reports.count { it.second != null })
    // The 25th EPUB inside Calibre Library is reported before the folder finishes.
    assertTrue(reports.any { (p, c) -> c == null && p.current == "Calibre Library" && p.epubs - reports.first { it.first.current == "Calibre Library" }.first.epubs == 25 })
  }

  @Test fun `nothing to walk reports nothing and divides safely`() {
    val reports = mutableListOf<DiscoveryProgress>()
    assertEquals(emptyList<FolderCandidate>(), FolderDiscovery.discover(listOf(tmp.newFolder("bare"))) { p, _ -> reports += p })
    assertEquals(emptyList<DiscoveryProgress>(), reports)
    assertEquals(0f, DiscoveryProgress(0, 0, "", 0).fraction)
  }
}
