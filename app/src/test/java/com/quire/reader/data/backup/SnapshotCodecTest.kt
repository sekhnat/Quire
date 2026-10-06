package com.quire.reader.data.backup

import com.quire.reader.data.ReaderPrefs
import com.quire.reader.theme.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotCodecTest {
  private fun snapshot(
    books: List<SnapshotBook> = emptyList(),
    settings: SnapshotSettings = SnapshotSettings(),
    exportedAt: Long = 1_700_000_000_000,
  ) = UserDataSnapshot(SNAPSHOT_SCHEMA_VERSION, exportedAt, settings, books)

  private fun book(
    title: String = "Emma",
    entryKey: String = "0123456789abcdef0123",
    state: SnapshotBookState? = null,
    userTags: List<String> = emptyList(),
    bookmarks: List<SnapshotBookmark> = emptyList(),
    highlights: List<SnapshotHighlight> = emptyList(),
    identity: SnapshotIdentity = SnapshotIdentity(calibreUuid = "u1"),
    missingSince: Long? = null,
  ) = SnapshotBook(
    identityKey = identityKeyOf(identity, entryKey), entryKey, identity, title = title, author = "Jane Austen",
    addedAt = 10, missingSince = missingSince, state = state, userTags = userTags, bookmarks = bookmarks, highlights = highlights,
  )

  private fun decode(text: String) = SnapshotCodec.decode(text)

  @Test fun `a snapshot survives encode and decode unchanged`() {
    val original = snapshot(
      books = listOf(
        book(
          title = "Emma ✳ 日本語",
          state = SnapshotBookState(
            locatorJson = """{"href":"ch4.xhtml","locations":{"progression":0.3}}""",
            progress = 0.42f, status = "reading", lastOpenedAt = 1_000, finishedAt = 0, userRating = 4,
            prefsJson = """{"fontSize":21}""",
          ),
          userTags = listOf("Favourite", "to-reread", "« quoted » tag"),
          bookmarks = listOf(SnapshotBookmark("""{"href":"ch2.xhtml"}""", "Chapter 2 — ✳", 0.12f, 5)),
          highlights = listOf(
            SnapshotHighlight(
              locatorJson = """{"href":"ch3.xhtml","text":{"highlight":"I am Dracula"}}""",
              text = "I am Dracula", note = "ominous", noteVariants = null, progress = 0.2f, createdAt = 7, chapter = "Chapter 3",
            ),
            SnapshotHighlight("""{"href":"ch5.xhtml"}""", "the blood is the life", note = null, progress = 0.5f, createdAt = 8),
          ),
        ),
        book(title = "Missing one", entryKey = "ffffffffffffffffffff", identity = SnapshotIdentity(fingerprint = "4096:ab"), missingSince = 99),
        book(title = "No identity", entryKey = "eeeeeeeeeeeeeeeeeeee", identity = SnapshotIdentity()),
      ),
      settings = SnapshotSettings(
        useCalibre = true, watchNewBooks = false, indexingEnabled = true, indexChargingOnly = true, brightness = 64,
        readerDefaults = ReaderPrefs(fontSize = 21, theme = ReaderTheme.Sepia),
        textSearchOrder = "Library",
      ),
    )
    assertEquals(original, (decode(SnapshotCodec.encode(original)) as SnapshotCodec.Decoded.Ok).snapshot)
  }

  @Test fun `boundary values are valid`() {
    val valid = snapshot(
      listOf(
        book(state = SnapshotBookState(progress = 0f, status = "unread")),
        book(state = SnapshotBookState(progress = 1f, status = "finished", userRating = 5), entryKey = "1"),
        book(state = SnapshotBookState(progress = null, status = null), entryKey = "2"),
      ),
      settings = SnapshotSettings(brightness = 30),
    )
    assertTrue(decode(SnapshotCodec.encode(valid)) is SnapshotCodec.Decoded.Ok)
  }

  @Test fun `a file without a schema version is refused`() {
    val text = SnapshotCodec.encode(snapshot()).replace("\"schemaVersion\":1,", "")
    assertTrue(decode(text) is SnapshotCodec.Decoded.Malformed)
  }

  @Test fun `a snapshot from another schema version is refused with its version`() {
    for (version in listOf(0, 2, 17)) {
      val text = SnapshotCodec.encode(snapshot()).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":$version")
      assertEquals(SnapshotCodec.Decoded.UnsupportedVersion(version), decode(text))
    }
  }

  @Test fun `a damaged file is malformed`() {
    for (text in listOf("", "{", "[]", "null", "\"a string\"", "{\"schemaVersion\":1,", "{\"schemaVersion\":1}")) {
      assertTrue("«$text» should be malformed", decode(text) is SnapshotCodec.Decoded.Malformed)
    }
  }

  @Test fun `invalid typed values are refused before writing`() {
    fun rejects(candidate: UserDataSnapshot) {
      assertTrue(
        "${candidate.books.firstOrNull()?.title ?: "settings"} should be refused",
        decode(SnapshotCodec.encode(candidate)) is SnapshotCodec.Decoded.Malformed,
      )
    }
    rejects(snapshot(settings = SnapshotSettings(brightness = 10)))
    rejects(snapshot(settings = SnapshotSettings(brightness = 101)))
    rejects(snapshot(settings = SnapshotSettings(textSearchOrder = "Random")))
    rejects(snapshot(listOf(book(state = SnapshotBookState(progress = 1.5f)))))
    rejects(snapshot(listOf(book(state = SnapshotBookState(progress = -0.1f)))))
    rejects(snapshot(listOf(book(state = SnapshotBookState(status = "skimming")))))
    rejects(snapshot(listOf(book(state = SnapshotBookState(userRating = 7)))))
    rejects(snapshot(listOf(book(state = SnapshotBookState(locatorJson = "not json")))))
    rejects(snapshot(listOf(book(bookmarks = listOf(SnapshotBookmark("not json", "Chapter 2", 0.12f, 5))))))
    rejects(snapshot(listOf(book(bookmarks = listOf(SnapshotBookmark("""{"href":"c"}""", "Chapter 2", 1.2f, 5))))))
    rejects(snapshot(listOf(book(highlights = listOf(SnapshotHighlight("not json", "text", progress = 0.2f, createdAt = 1))))))
    rejects(snapshot(listOf(book(highlights = listOf(SnapshotHighlight("""{"href":"c"}""", "text", noteVariants = emptyList(), progress = 0.2f, createdAt = 1))))))
    rejects(snapshot(listOf(book(userTags = listOf("  ")))))
    rejects(snapshot(listOf(book(entryKey = " "))))
    rejects(snapshot(listOf(book(title = " "))))
    rejects(snapshot(listOf(book(identity = SnapshotIdentity(calibreUuid = " ")))))
  }

  @Test fun `unknown keys are tolerated within the schema version`() {
    val text = SnapshotCodec.encode(snapshot()).replaceFirst("{\"schemaVersion", "{\"futureField\":1,\"schemaVersion")
    assertTrue(decode(text) is SnapshotCodec.Decoded.Ok)
  }

  @Test fun `an empty snapshot is valid`() {
    assertTrue(decode(SnapshotCodec.encode(snapshot())) is SnapshotCodec.Decoded.Ok)
  }

  @Test fun `a locator is canonicalised by dropping its display title and sorting keys`() {
    val canonical = SnapshotCodec.canonicalLocatorJson(
      """{"title":"Chapter 1","text":{"before":"b ","highlight":"a"},"href":"c1.xhtml","locations":{"progression":0.25}}""",
    )
    assertEquals("""{"href":"c1.xhtml","locations":{"progression":0.25},"text":{"before":"b ","highlight":"a"}}""", canonical)
    assertNull(SnapshotCodec.canonicalLocatorJson("not json"))
  }

  @Test fun `key order alone does not change a canonical locator`() {
    val a = SnapshotCodec.canonicalLocatorJson("""{"href":"c1.xhtml","locations":{"progression":0.25}}""")
    val b = SnapshotCodec.canonicalLocatorJson("""{"locations":{"progression":0.25},"href":"c1.xhtml"}""")
    assertEquals(a, b)
  }
}
