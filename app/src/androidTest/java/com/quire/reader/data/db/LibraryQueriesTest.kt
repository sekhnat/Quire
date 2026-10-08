package com.quire.reader.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library reads its catalogue and its reading states apart, so saving a position re-runs only the small query. */
class LibraryQueriesTest : DbTestCase() {
  @Test fun `saving a position re-reads the reading states and not the catalogue`() = runBlocking {
    val db = open()
    val id = db.books().upsert(bookEntity(folder(db), "Emma"))
    val catalog = Channel<List<CatalogRow>>(Channel.UNLIMITED)
    val states = Channel<List<ReadingStateRow>>(Channel.UNLIMITED)
    val watching = listOf(
      launch(Dispatchers.Default) { db.books().observeCatalog().collect { catalog.send(it) } },
      launch(Dispatchers.Default) { db.states().observeReading().collect { states.send(it) } },
    )
    assertEquals(listOf(id), withTimeout(5_000) { catalog.receive() }.map { it.id })
    assertEquals(emptyList<ReadingStateRow>(), withTimeout(5_000) { states.receive() })

    db.states().edit(id) { it.copy(progress = .3f, status = BookStateEntity.STATUS_READING, lastOpenedAt = 5, locatorJson = "{}") }
    assertEquals(listOf(ReadingStateRow(id, .3f, BookStateEntity.STATUS_READING, 5, null)), withTimeout(5_000) { states.receive() })
    // Room would have re-run the catalogue query well within this if the save had invalidated it.
    delay(1_000)
    assertTrue(catalog.tryReceive().isFailure)

    db.books().insertTags(listOf(BookTagEntity(id, "Classic", BookTagEntity.ORIGIN_USER)))
    val row = withTimeout(5_000) { catalog.receive() }.single()
    assertEquals(listOf("Classic") to listOf("Classic"), row.tagList to row.userTagList)
    watching.forEach { it.cancel() }
  }

  @Test fun `missing books stay out of the catalogue`() = runBlocking {
    val db = open()
    val folderId = folder(db)
    val kept = db.books().upsert(bookEntity(folderId, "Kept"))
    db.books().upsert(bookEntity(folderId, "Gone").copy(missingSince = 1))
    assertEquals(listOf(kept), db.books().catalogByIds(listOf(kept, kept + 1)).map { it.id })
    db.states().edit(kept) { it.copy(userRating = 4) }
    assertEquals(listOf(4), db.states().readingOf(listOf(kept)).map { it.userRating })
  }
}
