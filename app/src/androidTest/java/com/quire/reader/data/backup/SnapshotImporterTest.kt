package com.quire.reader.data.backup

import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.BookStateEntity
import com.quire.reader.data.db.BookTagEntity
import com.quire.reader.data.db.DbTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The importer against a real database: matching, merging, tombstones and repeated imports. */
class SnapshotImporterTest : DbTestCase() {
  private val db = open()
  private val folderId = runBlocking { folder(db) }

  private fun importer() = SnapshotImporter(db, SettingsStore(target), clock = { 9_000 })

  private fun addBook(name: String, uuid: String? = null, uid: String? = null, fp: String? = null): Long = runBlocking {
    val entity = bookEntity(folderId, name).copy(calibreUuid = uuid, epubUid = uid, fingerprint = fp)
    db.books().save(entity, emptyList())
  }

  private fun entry(
    uuid: String? = null, uid: String? = null, fp: String? = null, title: String = "Emma", entryKey: String = "k1",
    state: SnapshotBookState? = null, tags: List<String> = emptyList(),
    bookmarks: List<SnapshotBookmark> = emptyList(), highlights: List<SnapshotHighlight> = emptyList(),
  ) = SnapshotBook(
    identityKey = identityKeyOf(SnapshotIdentity(uuid, uid, fp), entryKey), entryKey,
    identity = SnapshotIdentity(uuid, uid, fp), title = title, author = "Jane Austen",
    addedAt = 5, missingSince = null, state = state, userTags = tags, bookmarks = bookmarks, highlights = highlights,
  )

  private fun state(opened: Long, locator: String = """{"href":"c1.xhtml"}""", progress: Float = 0.5f, status: String = "reading", rating: Int? = null) =
    SnapshotBookState(locatorJson = locator, progress = progress, status = status, lastOpenedAt = opened, userRating = rating)

  @Test fun `an entry merges into the book its identity points at`() = runBlocking {
    val bookId = addBook("Emma", uuid = "u1")
    val result = importer().import(
      UserDataSnapshot(
        1, 1, SnapshotSettings(),
        listOf(
          entry(uuid = "u1", state = state(1_000, rating = 4), tags = listOf("Favourite"),
            bookmarks = listOf(SnapshotBookmark("""{"href":"c2.xhtml"}""", "Chapter 2", 0.2f, 3)),
            highlights = listOf(SnapshotHighlight("""{"href":"c3.xhtml"}""", "I am Dracula", note = "ominous", progress = 0.3f, createdAt = 4)),
          ),
        ),
      ),
      applySettings = false,
    )
    assertEquals(ImportResult(matched = 1, tombstoned = 0, statesWritten = 1, bookmarksAdded = 1, highlightsAdded = 1, tagsAdded = 1), result)
    assertEquals(0.5f, db.states().get(bookId)!!.progress, 0f)
    assertEquals(4, db.states().get(bookId)!!.userRating)
    assertEquals(listOf("Favourite"), db.books().userTags(bookId))
    assertEquals(1, db.annotations().bookmarksOf(bookId).size)
    assertEquals("ominous", db.annotations().highlightsOf(bookId).single().note)
    assertNull(db.books().byId(bookId)!!.missingSince)
  }

  @Test fun `an entry nothing matches becomes a missing book that keeps the user data`() = runBlocking {
    addBook("Persuasion", uuid = "u2") // in the library, but the snapshot entry is for another book
    val result = importer().import(
      UserDataSnapshot(1, 1, SnapshotSettings(), listOf(entry(uuid = "u9", title = "Lost", entryKey = "k9", state = state(100)))),
      applySettings = false,
    )
    assertEquals(1, result.tombstoned)
    val tombstone = db.books().byPath(tombstonePath("k9"))!!
    assertEquals("Lost", tombstone.title)
    assertEquals("u9", tombstone.calibreUuid)
    assertEquals(false, tombstone.readable)
    assertTrue(tombstone.missingSince != null)
    assertEquals(0.5f, db.states().get(tombstone.id)!!.progress, 0f)
    assertEquals(1, db.books().observeMissing().first().size)
    // The synthetic folder is not watched, so the scanner never looks for the file.
    val tombFolder = db.folders().all().single { it.path == TOMBSTONE_FOLDER_PATH }
    assertFalse(tombFolder.watched)
  }

  @Test fun `repeating the same import changes nothing`() = runBlocking {
    val bookId = addBook("Emma", uuid = "u1")
    val snapshot = UserDataSnapshot(
      1, 1, SnapshotSettings(),
      listOf(
        entry(uuid = "u1", state = state(1_000), tags = listOf("Favourite"),
          bookmarks = listOf(SnapshotBookmark("""{"href":"c2.xhtml"}""", "Chapter 2", 0.2f, 3)),
          highlights = listOf(SnapshotHighlight("""{"href":"c3.xhtml"}""", "I am Dracula", note = "ominous", progress = 0.3f, createdAt = 4)),
        ),
      ),
    )
    val first = importer().import(snapshot, applySettings = false)
    assertTrue(first.changed)
    val second = importer().import(snapshot, applySettings = false)
    assertEquals(ImportResult(matched = 1, tombstoned = 0, statesWritten = 0, bookmarksAdded = 0, highlightsAdded = 0, tagsAdded = 0), second)
    assertEquals(1, db.annotations().bookmarksOf(bookId).size)
    assertEquals(1, db.annotations().highlightsOf(bookId).size)
    assertEquals(listOf("Favourite"), db.books().userTags(bookId))
  }

  @Test fun `a local position survives unless the incoming one is strictly newer`() = runBlocking {
    val bookId = addBook("Emma", uuid = "u1")
    db.states().put(BookStateEntity(bookId, locatorJson = """{"href":"local"}""", progress = 0.2f, status = "reading", lastOpenedAt = 500, userRating = 2))
    val import: suspend (SnapshotBookState) -> Unit = { state ->
      importer().import(UserDataSnapshot(1, 1, SnapshotSettings(), listOf(entry(uuid = "u1", state = state))), applySettings = false)
    }
    import(state(opened = 400))
    assertEquals("""{"href":"local"}""", db.states().get(bookId)!!.locatorJson)
    import(state(opened = 600, locator = """{"href":"newer"}""", progress = 0.9f, status = "finished"))
    val merged = db.states().get(bookId)!!
    assertEquals("""{"href":"newer"}""", merged.locatorJson)
    assertEquals(0.9f, merged.progress, 0f)
    assertEquals(BookStateEntity.STATUS_FINISHED, merged.status)
    assertEquals(2, merged.userRating) // local rating kept
  }

  @Test fun `conflicting notes on one highlight are joined and stay joined`() = runBlocking {
    val bookId = addBook("Emma", uuid = "u1")
    db.annotations().addHighlight(
      com.quire.reader.data.db.HighlightEntity(bookId = bookId, locatorJson = """{"href":"c3.xhtml"}""", text = "same text", note = "mine", progress = 0.3f, createdAt = 1),
    )
    val incoming = SnapshotHighlight("""{"href":"c3.xhtml"}""", "same text", note = "theirs", progress = 0.3f, createdAt = 2)
    importer().import(UserDataSnapshot(1, 1, SnapshotSettings(), listOf(entry(uuid = "u1", highlights = listOf(incoming)))), applySettings = false)
    val highlights = db.annotations().highlightsOf(bookId)
    assertEquals(1, highlights.size)
    assertEquals(listOf("mine", "theirs"), NoteVariants.split(highlights.single().note))
    // Again: still two variants, never three.
    importer().import(UserDataSnapshot(1, 1, SnapshotSettings(), listOf(entry(uuid = "u1", highlights = listOf(incoming)))), applySettings = false)
    assertEquals(listOf("mine", "theirs"), NoteVariants.split(db.annotations().highlightsOf(bookId).single().note))
  }

  @Test fun `an entry joins a missing book it matches before a tombstone is made`() = runBlocking {
    val bookId = addBook("Emma", uuid = "u1")
    db.books().markMissing(listOf(bookId), 100)
    val result = importer().import(
      UserDataSnapshot(1, 1, SnapshotSettings(), listOf(entry(uuid = "u1", state = state(1_000)))),
      applySettings = false,
    )
    assertEquals(1, result.matched)
    assertEquals(0, result.tombstoned)
    assertEquals(0.5f, db.states().get(bookId)!!.progress, 0f)
  }

  @Test fun `bookmarks with the same position but different titles are one bookmark`() = runBlocking {
    val bookId = addBook("Emma", uuid = "u1")
    db.annotations().addBookmark(
      com.quire.reader.data.db.BookmarkEntity(bookId = bookId, locatorJson = """{"href":"c2.xhtml","title":"Old title"}""", label = "Old title", progress = 0.2f, createdAt = 1),
    )
    importer().import(
      UserDataSnapshot(1, 1, SnapshotSettings(), listOf(entry(uuid = "u1", bookmarks = listOf(SnapshotBookmark("""{"title":"New title","href":"c2.xhtml"}""", "New title", 0.2f, 2))))),
      applySettings = false,
    )
    assertEquals(1, db.annotations().bookmarksOf(bookId).size)
  }

  @Test fun `a clean restore applies the settings an existing library keeps its own`() = runBlocking {
    val settings = SettingsStore(target)
    settings.setUseCalibre(false)
    addBook("Emma", uuid = "u1")
    importer().import(UserDataSnapshot(1, 1, SnapshotSettings(useCalibre = true), listOf(entry(uuid = "u1"))), applySettings = false)
    assertEquals(false, settings.useCalibre.first())
    importer().import(UserDataSnapshot(1, 1, SnapshotSettings(useCalibre = true), emptyList()), applySettings = true)
    assertEquals(true, settings.useCalibre.first())
  }
}
