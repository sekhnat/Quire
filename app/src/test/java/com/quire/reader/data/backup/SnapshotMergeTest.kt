package com.quire.reader.data.backup

import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.db.BookStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotMergeTest {
  // ── helpers ──────────────────────────────────────────────────────────────

  private fun entry(
    uuid: String? = null, uid: String? = null, fp: String? = null, title: String = "Emma", entryKey: String = "k1",
    state: SnapshotBookState? = null, tags: List<String> = emptyList(),
    bookmarks: List<SnapshotBookmark> = emptyList(), highlights: List<SnapshotHighlight> = emptyList(),
    addedAt: Long? = null, missingSince: Long? = null,
  ) = SnapshotBook(
    identityKey = identityKeyOf(SnapshotIdentity(uuid, uid, fp), entryKey), entryKey,
    identity = SnapshotIdentity(uuid, uid, fp), title = title, author = "Ann Author", addedAt = addedAt,
    missingSince = missingSince, state = state, userTags = tags, bookmarks = bookmarks, highlights = highlights,
  )

  private fun candidate(id: Long, uuid: String? = null, uid: String? = null, fp: String? = null, title: String = "Emma") =
    MatchCandidate(id, title, uuid, uid, fp)

  private fun state(
    locator: String? = null, progress: Float? = null, status: String? = null, opened: Long? = null,
    finished: Long? = null, rating: Int? = null, prefs: String? = null,
  ) = SnapshotBookState(locator, progress, status, opened, finished, rating, prefs)

  private fun localState(
    bookId: Long = 1, locator: String? = null, progress: Float = 0f, status: String = "unread",
    opened: Long = 0, finished: Long = 0, rating: Int? = null, prefs: String? = null,
  ) = BookStateEntity(bookId, locator, progress, status, opened, finished, rating, prefs)

  // ── identity keys and matching ───────────────────────────────────────────

  @Test fun `the identity key prefers the strongest available key`() {
    assertEquals("calibre:u", identityKeyOf(SnapshotIdentity(calibreUuid = "u", epubUid = "i", fingerprint = "f"), "e"))
    assertEquals("fingerprint:f", identityKeyOf(SnapshotIdentity(epubUid = "i", fingerprint = "f"), "e"))
    assertEquals("epubUid:i", identityKeyOf(SnapshotIdentity(epubUid = "i"), "e"))
    assertEquals("entry:e", identityKeyOf(SnapshotIdentity(), "e"))
  }

  @Test fun `an entry key is opaque and stable for its source row`() {
    val first = entryKeyFor(7, "/sdcard/Books/Emma.epub")
    assertEquals(first, entryKeyFor(7, "/sdcard/Books/Emma.epub"))
    assertEquals(20, first.length)
    assertEquals(false, first == entryKeyFor(8, "/sdcard/Books/Emma.epub"))
    assertEquals(false, first == entryKeyFor(7, "/sdcard/Books/Emma (copy).epub"))
  }

  @Test fun `a unique Calibre uuid matches`() {
    assertEquals(listOf(EntryMatch.Matched(10L)), matchEntries(listOf(entry(uuid = "u")), listOf(candidate(10, uuid = "u"), candidate(11, fp = "f"))))
  }

  @Test fun `an ambiguous uuid or fingerprint is not guessed`() {
    val two = listOf(candidate(10, uuid = "u"), candidate(11, uuid = "u"))
    assertEquals(listOf(EntryMatch.Tombstone), matchEntries(listOf(entry(uuid = "u")), two))
    assertEquals(listOf(EntryMatch.Tombstone), matchEntries(listOf(entry(fp = "f")), listOf(candidate(10, fp = "f"), candidate(11, fp = "f"))))
  }

  @Test fun `a fingerprint matches after the uuid tier found nothing`() {
    assertEquals(listOf(EntryMatch.Matched(11L)), matchEntries(listOf(entry(uuid = "u", fp = "f")), listOf(candidate(10, uuid = "other"), candidate(11, fp = "f"))))
  }

  @Test fun `an ambiguous uuid still allows a unique fingerprint lower tier`() {
    assertEquals(listOf(EntryMatch.Matched(12L)), matchEntries(listOf(entry(uuid = "u", fp = "f")), listOf(candidate(10, uuid = "u"), candidate(11, uuid = "u"), candidate(12, fp = "f"))))
  }

  @Test fun `a unique uid matches only when the titles agree`() {
    assertEquals(listOf(EntryMatch.Matched(10L)), matchEntries(listOf(entry(uid = "i", title = " Emma ")), listOf(candidate(10, uid = "i"))))
    assertEquals(listOf(EntryMatch.Tombstone), matchEntries(listOf(entry(uid = "i", title = "Emma")), listOf(candidate(10, uid = "i", title = "Persuasion"))))
  }

  @Test fun `a uid shared by several entries is trusted for none of them`() {
    val entries = listOf(entry(uid = "i", title = "Emma"), entry(uid = "i", title = "Emma", entryKey = "k2"))
    assertEquals(listOf(EntryMatch.Tombstone, EntryMatch.Tombstone), matchEntries(entries, listOf(candidate(10, uid = "i"))))
  }

  @Test fun `a uid two candidates carry is not guessed even with matching titles`() {
    assertEquals(listOf(EntryMatch.Tombstone), matchEntries(listOf(entry(uid = "i")), listOf(candidate(10, uid = "i"), candidate(11, uid = "i"))))
  }

  @Test fun `a title alone never matches`() {
    assertEquals(listOf(EntryMatch.Tombstone), matchEntries(listOf(entry(title = "Emma")), listOf(candidate(10, title = "Emma"))))
  }

  @Test fun `an identity-less entry falls back to its entry key and never matches by title`() {
    assertEquals(listOf(EntryMatch.Tombstone), matchEntries(listOf(entry()), listOf(candidate(10, title = "Emma"))))
  }

  @Test fun `several copies of one book all merge into the single live copy`() {
    val entries = listOf(entry(uuid = "u", entryKey = "k1"), entry(uuid = "u", entryKey = "k2"))
    assertEquals(listOf(EntryMatch.Matched(10L), EntryMatch.Matched(10L)), matchEntries(entries, listOf(candidate(10, uuid = "u"))))
  }

  // ── reading state ────────────────────────────────────────────────────────

  @Test fun `a book with no local state takes the incoming state in full`() {
    val incoming = state(locator = """{"href":"c"}""", progress = 0.4f, status = "reading", opened = 1_000, rating = 3, prefs = """{"fontSize":22}""")
    assertEquals(
      BookStateEntity(7, incoming.locatorJson, 0.4f, "reading", 1_000, 0, 3, incoming.prefsJson),
      SnapshotMerge.mergeState(7, null, incoming),
    )
  }

  @Test fun `an incoming state with nothing in it writes no row`() {
    assertNull(SnapshotMerge.mergeState(7, null, SnapshotBookState()))
  }

  @Test fun `a strictly more recently opened position displaces the local one`() {
    val local = localState(locator = """{"href":"old"}""", progress = 0.1f, status = "reading", opened = 100)
    val merged = SnapshotMerge.mergeState(1, local, state(locator = """{"href":"new"}""", progress = 0.9f, status = "finished", opened = 200, finished = 300))
    assertEquals(localState(locator = """{"href":"new"}""", progress = 0.9f, status = "finished", opened = 200, finished = 300), merged)
  }

  @Test fun `an older or equal position keeps the local one`() {
    val local = localState(locator = """{"href":"old"}""", progress = 0.5f, status = "reading", opened = 200, finished = 1)
    for (incomingOpenedAt in listOf(200L, 199L)) {
      val merged = SnapshotMerge.mergeState(1, local, state(locator = """{"href":"new"}""", progress = 0.1f, opened = incomingOpenedAt))
      assertEquals(local, merged)
    }
  }

  @Test fun `a partial incoming position fills only the fields it has`() {
    val local = localState(locator = """{"href":"old"}""", progress = 0.5f, status = "reading", opened = 100, finished = 5)
    val merged = SnapshotMerge.mergeState(1, local, state(locator = null, progress = 0.9f, status = null, opened = 200, finished = null))
    assertEquals(localState(locator = """{"href":"old"}""", progress = 0.9f, status = "reading", opened = 200, finished = 5), merged)
  }

  @Test fun `an incoming state without a timestamp never displaces an unread local book`() {
    val local = localState(locator = """{"href":"old"}""", progress = 0f, status = "unread", opened = 0)
    assertEquals(local, SnapshotMerge.mergeState(1, local, state(locator = """{"href":"new"}""", progress = 0.5f)))
  }

  @Test fun `ratings and per-book settings are local first and filled when absent`() {
    val local = localState(opened = 100, rating = 4, prefs = """{"fontSize":19}""")
    val merged = SnapshotMerge.mergeState(1, local, state(opened = 200, rating = 2, prefs = """{"fontSize":30}"""))
    assertEquals(localState(opened = 200, rating = 4, prefs = """{"fontSize":19}"""), merged)

    val withoutLocal = localState(opened = 100)
    val filled = SnapshotMerge.mergeState(1, withoutLocal, state(opened = 50, rating = 2, prefs = """{"fontSize":30}"""))
    assertEquals(localState(opened = 100, rating = 2, prefs = """{"fontSize":30}"""), filled)
  }

  // ── notes, tags and settings ─────────────────────────────────────────────

  @Test fun `notes merge only on exact equality`() {
    assertNull(SnapshotMerge.unionNote(null, null))
    assertEquals("A", SnapshotMerge.unionNote("A", null))
    assertEquals("A", SnapshotMerge.unionNote(null, "A"))
    assertEquals("A", SnapshotMerge.unionNote("A", "A"))
    assertEquals("A", SnapshotMerge.unionNote("A", " A "))
    assertEquals("A${NoteVariants.SEPARATOR}B", SnapshotMerge.unionNote("A", "B"))
    // A substring is a distinct variant, not a duplicate.
    assertEquals("great passage${NoteVariants.SEPARATOR}great", SnapshotMerge.unionNote("great passage", "great"))
  }

  @Test fun `a joined note merges variant by variant and stays stable`() {
    val merged = SnapshotMerge.unionNote("A", "B")
    assertEquals(merged, SnapshotMerge.unionNote(merged, "B"))
    assertEquals(listOf("A", "B", "C"), NoteVariants.split(SnapshotMerge.unionNote(merged, "B${NoteVariants.SEPARATOR}C")))
    assertEquals("A${NoteVariants.SEPARATOR}B", SnapshotMerge.unionNote("A", "B", incomingVariants = listOf("B")))
  }

  @Test fun `user tags union without duplicates`() {
    assertEquals(listOf("Classic", "Favourite", "favourite"), SnapshotMerge.unionTags(listOf("Classic"), listOf("Favourite", "Classic", " favourite ")))
    assertEquals(listOf("Classic"), SnapshotMerge.unionTags(listOf("Classic"), listOf("  ")))
  }

  @Test fun `settings apply on a clean restore and are kept on an existing library`() {
    val local = SnapshotSettings(useCalibre = false, watchNewBooks = true, brightness = 80, readerDefaults = ReaderPrefs(fontSize = 19))
    val incoming = SnapshotSettings(useCalibre = true, brightness = 64, readerDefaults = ReaderPrefs(fontSize = 25))
    assertEquals(local, SnapshotMerge.mergeSettings(local, incoming, applyIncoming = false))
    assertEquals(
      SnapshotSettings(useCalibre = true, watchNewBooks = true, brightness = 64, readerDefaults = ReaderPrefs(fontSize = 25)),
      SnapshotMerge.mergeSettings(local, incoming, applyIncoming = true),
    )
  }

  // ── tombstones ───────────────────────────────────────────────────────────

  @Test fun `a tombstone path is unique per entry and deterministic`() {
    assertEquals("/Quire missing books/k1.epub", tombstonePath("k1"))
    assertEquals(tombstonePath("k1"), tombstonePath("k1"))
    assertEquals(false, tombstonePath("k1") == tombstonePath("k2"))
  }
}
