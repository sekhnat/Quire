package com.quire.reader.data.scan

import com.quire.reader.data.db.KnownFile
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanPlanTest {
  private fun known(id: Long, path: String, folder: Long = 1, size: Long = 100, mtime: Long = 10) = KnownFile(id, path, folder, size, mtime, 0, null)
  private fun found(path: String, folder: Long = 1, size: Long = 100, mtime: Long = 10) = FoundFile(path, folder, size, mtime)

  @Test fun `unchanged files are skipped`() {
    val plan = planScan(listOf(found("/a.epub")), mapOf("/a.epub" to known(1, "/a.epub")), setOf(1))
    assertEquals(emptyList<FoundFile>(), plan.toRead)
    assertEquals(emptyList<Long>(), plan.removedIds)
  }

  @Test fun `new and changed files are read`() {
    val known = mapOf("/a.epub" to known(1, "/a.epub"), "/b.epub" to known(2, "/b.epub"))
    val plan = planScan(listOf(found("/a.epub", size = 999), found("/b.epub"), found("/c.epub")), known, setOf(1))
    assertEquals(listOf("/a.epub", "/c.epub"), plan.toRead.map { it.path })
  }

  @Test fun `changed modified time alone triggers a reread`() {
    val plan = planScan(listOf(found("/a.epub", mtime = 11)), mapOf("/a.epub" to known(1, "/a.epub")), setOf(1))
    assertEquals(1, plan.toRead.size)
  }

  @Test fun `files that disappeared from a reachable folder are removed`() {
    val plan = planScan(emptyList(), mapOf("/a.epub" to known(7, "/a.epub")), setOf(1))
    assertEquals(listOf(7L), plan.removedIds)
  }

  @Test fun `an unreachable folder never loses its books`() {
    val plan = planScan(emptyList(), mapOf("/sd/a.epub" to known(7, "/sd/a.epub", folder = 2)), reachableFolderIds = setOf(1))
    assertEquals(emptyList<Long>(), plan.removedIds)
  }
}
