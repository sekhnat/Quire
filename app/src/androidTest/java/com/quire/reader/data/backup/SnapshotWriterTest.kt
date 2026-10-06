package com.quire.reader.data.backup

import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.BookStateEntity
import com.quire.reader.data.db.BookTagEntity
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.DbTestCase
import com.quire.reader.data.db.HighlightEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** The snapshot writer against a real database: what it captures, and when it refuses to write. */
class SnapshotWriterTest : DbTestCase() {
  private val db = open()
  private val folderId = runBlocking { folder(db) }
  private val dir = File(target.cacheDir, "snapshot-tests/${UUID.randomUUID()}")

  private fun writer(gate: () -> Boolean = { false }) =
    SnapshotWriter(db, SettingsStore(target), dir, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), isRestorePending = gate, debounceMillis = 30_000)

  private fun annotatedBook(): Long = runBlocking {
    val book = bookEntity(folderId, "Emma").copy(calibreUuid = "uuid-1")
    val id = db.books().save(book, listOf("Calibre tag"))
    db.states().put(BookStateEntity(id, locatorJson = """{"href":"c1.xhtml"}""", progress = 0.4f, status = "reading", lastOpenedAt = 500, userRating = 3, prefsJson = """{"fontSize":22}"""))
    db.annotations().addBookmark(BookmarkEntity(bookId = id, locatorJson = """{"href":"c2.xhtml","title":"Chapter 2"}""", label = "Chapter 2", progress = 0.2f, createdAt = 11))
    db.annotations().addHighlight(HighlightEntity(bookId = id, locatorJson = """{"href":"c3.xhtml","title":"Chapter 3"}""", text = "I am Dracula", note = "ominous", progress = 0.3f, createdAt = 12))
    db.books().insertTags(listOf(BookTagEntity(id, "favourite", BookTagEntity.ORIGIN_USER)))
    id
  }

  @Test fun `a capture holds exactly what the user authored`() = runBlocking {
    val bookId = annotatedBook()
    val untouched = db.books().save(bookEntity(folderId, "Untouched"), emptyList())
    db.states().put(BookStateEntity(untouched)) // a default state row is not user data

    val decoded = SnapshotCodec.decode(SnapshotCodec.encode(writer().capture()))
    assertTrue(decoded is SnapshotCodec.Decoded.Ok)
    val snapshot = (decoded as SnapshotCodec.Decoded.Ok).snapshot

    assertEquals(1, snapshot.books.size)
    val entry = snapshot.books.single()
    assertEquals("Emma", entry.title)
    assertEquals("calibre:uuid-1", entry.identityKey)
    assertEquals("uuid-1", entry.identity.calibreUuid)
    assertEquals(BookStateEntity.STATUS_READING, entry.state?.status)
    assertEquals(0.4f, entry.state?.progress)
    assertEquals(3, entry.state?.userRating)
    assertEquals(listOf("favourite"), entry.userTags)
    assertEquals(1, entry.bookmarks.size)
    assertEquals(1, entry.highlights.size)
    assertEquals("ominous", entry.highlights.single().note)
    assertEquals("Chapter 3", entry.highlights.single().chapter)
    assertNotNull(entry.entryKey)
    assertEquals(entryKeyFor(bookId, db.books().byId(bookId)!!.path), entry.entryKey)
    assertEquals(SettingsStore(target).capture(), snapshot.settings)
  }

  @Test fun `a flush writes the file and a later flush replaces it in place`() = runBlocking {
    val bookId = annotatedBook()
    val file = writer().also { assertTrue(it.flush()) }.let { File(dir, SNAPSHOT_FILE_NAME) }
    assertTrue(file.isFile)
    val first = SnapshotCodec.decode(file.readText()) as SnapshotCodec.Decoded.Ok
    assertEquals(0.4f, first.snapshot.books.single().state?.progress)

    db.states().edit(bookId) { it.copy(progress = 0.9f, lastOpenedAt = 900) }
    assertTrue(writer().flush())
    val second = SnapshotCodec.decode(file.readText()) as SnapshotCodec.Decoded.Ok
    assertEquals(0.9f, second.snapshot.books.single().state?.progress)
    assertFalse(File(dir, "$SNAPSHOT_FILE_NAME.tmp").exists())
  }

  @Test fun `the writer stands down while a restore is pending and keeps the old file`() = runBlocking {
    val file = File(dir, SNAPSHOT_FILE_NAME)
    dir.mkdirs()
    file.writeText("the restored copy")
    val held = writer(gate = { true })
    assertFalse(held.flush())
    assertEquals("the restored copy", file.readText())
    // Once the restore is done the same writer writes again.
    val free = writer()
    assertTrue(free.flush())
    assertTrue(SnapshotCodec.decode(file.readText()) is SnapshotCodec.Decoded.Ok)
  }

  @Test fun `a missing book is captured with its missing date`() = runBlocking {
    val bookId = annotatedBook()
    db.books().markMissing(listOf(bookId), 7_000)
    val snapshot = writer().capture()
    val entry = snapshot.books.single()
    assertEquals(7_000L, entry.missingSince)
  }

  @Test fun `joined note variants survive a capture`() = runBlocking {
    val bookId = annotatedBook()
    db.annotations().setHighlightNote(db.annotations().highlightsOf(bookId).single().id, "ominous${NoteVariants.SEPARATOR}darker still")
    val entry = writer().capture().books.single().highlights.single()
    assertEquals(listOf("ominous", "darker still"), entry.noteVariants)
    assertEquals("ominous${NoteVariants.SEPARATOR}darker still", entry.note)
  }
  @Test fun `the debounced writer starts without an explicit flush and follows edits`() = runBlocking {
    val bookId = annotatedBook()
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    try {
      val writer = SnapshotWriter(db, SettingsStore(target), dir, scope, debounceMillis = 50)
      writer.start()
      val file = File(dir, SNAPSHOT_FILE_NAME)
      kotlinx.coroutines.withTimeout(5_000) {
        while (!file.isFile) kotlinx.coroutines.delay(20)
      }
      db.states().edit(bookId) { it.copy(progress = 0.8f) }
      kotlinx.coroutines.withTimeout(5_000) {
        while ((SnapshotCodec.decode(file.readText()) as? SnapshotCodec.Decoded.Ok)
            ?.snapshot?.books?.single()?.state?.progress != 0.8f) kotlinx.coroutines.delay(20)
      }
    } finally {
      scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
  }
  @Test fun `search v2 order is portable but index maintenance flags are not`() = runBlocking {
    val settings = SettingsStore(target)
    val original = settings.capture()
    try {
      settings.setTextSearchOrder(com.quire.reader.data.index.SearchOrder.Library)
      settings.setIndexOptimized(true)
      val captured = writer().capture()
      assertEquals("Library", captured.settings.textSearchOrder)
      assertFalse(SnapshotCodec.encode(captured).contains("indexOptimized"))
      settings.setTextSearchOrder(com.quire.reader.data.index.SearchOrder.Relevance)
      settings.applySettings(captured.settings)
      assertEquals(com.quire.reader.data.index.SearchOrder.Library, settings.textSearchOrder.first())
      assertTrue(settings.indexOptimized.first()) // portable application does not touch local maintenance
    } finally {
      settings.applySettings(original)
      settings.setIndexOptimized(false)
    }
  }

}
