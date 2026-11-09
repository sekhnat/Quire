package com.quire.reader.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

  @Test fun `a folder tag is read apart from the others and hidden tags keep books out of text search`() = runBlocking {
    val db = open()
    val folderId = folder(db)
    val a = db.books().save(bookEntity(folderId, "A").copy(path = "/lib/Fiction/a.epub"), listOf("Classic"), folderTag = "Fiction")
    val b = db.books().save(bookEntity(folderId, "B").copy(path = "/lib/b.epub"), emptyList(), folderTag = "lib")
    db.books().insertTags(listOf(BookTagEntity(b, "Private", BookTagEntity.ORIGIN_USER)))

    val rows = db.books().observeCatalog().first().associateBy { it.id }
    assertEquals("Fiction", rows.getValue(a).folderTag)
    assertEquals(setOf("Classic", "Fiction"), rows.getValue(a).tagList.toSet())
    assertEquals(emptyList<String>(), rows.getValue(a).userTagList)
    assertEquals(listOf("Classic", "Fiction", "lib", "Private"), db.books().observeTagNames().first())

    fun searchable(hidden: List<String>, tag: String? = null) = runBlocking { db.search().searchableBooks(null, null, tag, null, 0, hidden).map { it.id }.toSet() }
    assertEquals(setOf(a, b), searchable(emptyList()))
    assertEquals(setOf(a), searchable(listOf("Private")))
    assertEquals(setOf(b), searchable(listOf("Fiction")))
    assertEquals(emptySet<Long>(), searchable(listOf("Classic", "lib")))
    assertEquals(setOf(a), searchable(listOf("Private"), tag = "Classic"))

    // A rescan replaces the folder tag and keeps the user's.
    db.books().setFolderTags(listOf(b to "Other", a to null))
    val after = db.books().observeCatalog().first().associateBy { it.id }
    assertEquals(null, after.getValue(a).folderTag)
    assertEquals(setOf("Other", "Private"), after.getValue(b).tagList.toSet())
  }

  @Test fun `removing books keeps those with history as missing, and forgetting them deletes it all`() = runBlocking {
    val db = open()
    val folderId = folder(db)
    val read = db.books().upsert(bookEntity(folderId, "Read").copy(path = "/lib/read.epub"))
    val unread = db.books().upsert(bookEntity(folderId, "Unread").copy(path = "/lib/unread.epub"))
    val other = db.books().upsert(bookEntity(folderId, "Other").copy(path = "/lib/other.epub"))
    db.states().edit(read) { it.copy(progress = .5f, status = BookStateEntity.STATUS_READING, lastOpenedAt = 5) }
    db.books().insertTags(listOf(read, unread).map { BookTagEntity(it, "Austen", BookTagEntity.ORIGIN_USER) })
    db.books().deleteUserTag(unread, "Austen")

    assertEquals(setOf("/lib/read.epub", "/lib/unread.epub"), db.books().files(listOf(read, unread, 999)).map { it.path }.toSet())
    val removal = db.books().removeBooks(listOf(read, unread), at = 7)
    assertEquals(1, removal.keptWithHistory)
    assertEquals(listOf(read), db.books().observeMissing().first().map { it.id })
    assertEquals("/lib/read.epub", db.books().observeMissing().first().single().path)
    assertEquals(listOf(other), db.books().observeCatalog().first().map { it.id })

    db.books().forgetBooks(listOf(read, other))
    assertEquals(0, db.books().totalCount())
    assertEquals(null, db.states().get(read))
  }
}

