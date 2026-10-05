package com.quire.reader.data.scan

import com.quire.reader.data.db.IdentityRow
import com.quire.reader.data.db.KnownFile
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanPlanTest {
  private fun known(id: Long, path: String, folder: Long = 1, size: Long = 100, mtime: Long = 10, missing: Boolean = false) =
    KnownFile(id, path, folder, size, mtime, 0, null, missing = missing)
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

  @Test fun `a book already missing is not removed again`() {
    val plan = planScan(emptyList(), mapOf("/a.epub" to known(7, "/a.epub", missing = true)), setOf(1))
    assertEquals(emptyList<Long>(), plan.removedIds)
  }

  // ── matchMoves ──────────────────────────────────────────────────────────

  private fun row(id: Long, uuid: String? = null, fp: String? = null, uid: String? = null, folder: Long = 1, opened: Long = 0, history: Boolean = false, title: String = "Emma") =
    IdentityRow(id, folder, title, uuid, uid, fp, opened, history)

  @Test fun `a Calibre rename is matched by uuid even though the file changed`() {
    assertEquals(listOf(Move(1, 10)), matchMoves(listOf(row(1, uuid = "u", fp = "old")), listOf(row(10, uuid = "u", fp = "new"), row(11, fp = "old2"))))
  }

  @Test fun `a plain move is matched by fingerprint`() {
    assertEquals(listOf(Move(1, 10)), matchMoves(listOf(row(1, fp = "f")), listOf(row(11, fp = "g"), row(10, fp = "f", folder = 2))))
  }

  @Test fun `a live book with history of its own is never taken over`() {
    assertEquals(emptyList<Move>(), matchMoves(listOf(row(1, fp = "f")), listOf(row(10, fp = "f", history = true))))
  }

  @Test fun `the EPUB identifier counts only when one missing and one live book carry it and their titles agree`() {
    assertEquals(listOf(Move(1, 10)), matchMoves(listOf(row(1, uid = "isbn")), listOf(row(10, uid = "isbn", title = " emma "))))
    assertEquals(emptyList<Move>(), matchMoves(listOf(row(1, uid = "isbn")), listOf(row(10, uid = "isbn", title = "Persuasion"))))
    // Two live books share it, even though one has history and is not a candidate.
    assertEquals(emptyList<Move>(), matchMoves(listOf(row(1, uid = "isbn")), listOf(row(10, uid = "isbn"), row(11, uid = "isbn", history = true))))
    // Two missing books share it.
    assertEquals(emptyList<Move>(), matchMoves(listOf(row(1, uid = "isbn"), row(2, uid = "isbn")), listOf(row(10, uid = "isbn"))))
  }

  @Test fun `copies in several places go to the one in the old folder, or nowhere`() {
    val copies = listOf(row(10, fp = "f", folder = 2), row(11, fp = "f", folder = 1))
    assertEquals(listOf(Move(1, 11)), matchMoves(listOf(row(1, fp = "f", folder = 1)), copies))
    assertEquals(emptyList<Move>(), matchMoves(listOf(row(1, fp = "f", folder = 3)), copies))
  }

  @Test fun `an ambiguous key falls through to the next one`() {
    val live = listOf(row(10, uuid = "u", fp = "a", folder = 2), row(11, uuid = "u", fp = "b", folder = 3))
    assertEquals(listOf(Move(1, 11)), matchMoves(listOf(row(1, uuid = "u", fp = "b")), live))
  }

  @Test fun `a live book is taken once, by the most recently opened missing book`() {
    val missing = listOf(row(1, fp = "f", opened = 100), row(2, fp = "f", opened = 500))
    assertEquals(listOf(Move(2, 10)), matchMoves(missing, listOf(row(10, fp = "f"))))
  }

  @Test fun `missing keys never match each other`() {
    assertEquals(emptyList<Move>(), matchMoves(listOf(row(1)), listOf(row(10))))
  }
}
