package com.quire.reader.data.db

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the version 1 -> 2 -> 3 and 2 -> 3 migrations and the FTS triggers on the device's own SQLite, over a real file. */
class TextIndexMigrationTest : DbTestCase() {
  private val v1Tables = listOf("folder", "book", "book_tag", "book_state", "bookmark", "highlight")

  /** The version 1 schema, as Room generated it for `QuireDatabase` before the text index existed. */
  private val v1Schema = listOf(
    "CREATE TABLE IF NOT EXISTS `folder` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `watched` INTEGER NOT NULL, `lastScanAt` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_folder_path` ON `folder` (`path`)",
    "CREATE TABLE IF NOT EXISTS `book` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `folderId` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `mtime` INTEGER NOT NULL, `title` TEXT NOT NULL, `sortTitle` TEXT NOT NULL, `author` TEXT NOT NULL, `primaryAuthor` TEXT NOT NULL, `authorSort` TEXT NOT NULL, `series` TEXT, `seriesIndex` REAL, `pubYear` INTEGER, `language` TEXT, `description` TEXT, `calibreRating` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `pageEstimate` INTEGER NOT NULL, `coverPath` TEXT, `source` TEXT NOT NULL, `readable` INTEGER NOT NULL, FOREIGN KEY(`folderId`) REFERENCES `folder`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_path` ON `book` (`path`)",
    "CREATE INDEX IF NOT EXISTS `index_book_folderId` ON `book` (`folderId`)",
    "CREATE TABLE IF NOT EXISTS `book_tag` (`bookId` INTEGER NOT NULL, `tag` TEXT NOT NULL, `origin` TEXT NOT NULL, PRIMARY KEY(`bookId`, `tag`), FOREIGN KEY(`bookId`) REFERENCES `book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_book_tag_tag` ON `book_tag` (`tag`)",
    "CREATE TABLE IF NOT EXISTS `book_state` (`bookId` INTEGER NOT NULL, `locatorJson` TEXT, `progress` REAL NOT NULL, `status` TEXT NOT NULL, `lastOpenedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `userRating` INTEGER, `prefsJson` TEXT, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `bookmark` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `locatorJson` TEXT NOT NULL, `label` TEXT NOT NULL, `progress` REAL NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`bookId`) REFERENCES `book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_bookmark_bookId` ON `bookmark` (`bookId`)",
    "CREATE TABLE IF NOT EXISTS `highlight` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `locatorJson` TEXT NOT NULL, `text` TEXT NOT NULL, `note` TEXT, `progress` REAL NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`bookId`) REFERENCES `book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_highlight_bookId` ON `highlight` (`bookId`)",
  )

  private val v1Seed = listOf(
    "INSERT INTO folder VALUES (1, '/sdcard/Books', 1, 1700000000111), (2, '/sdcard/Calibre Library', 0, 1700000000222)",
    """INSERT INTO book VALUES
      (1, '/sdcard/Books/Dracula.epub', 1, 603000, 1690000000001, 'Dracula', 'Dracula', 'Bram Stoker', 'Bram Stoker', 'Stoker, Bram',
       'Gothic', 1.5, 1897, 'en', 'A count and his guests', 4, 1695000000000, 310, '/data/covers/1.jpg', 'calibre', 1),
      (2, '/sdcard/Calibre Library/Jane Austen/Emma (7)/book.epub', 2, 475000, 1690000000002, 'Emma', 'Emma', 'Jane Austen', 'Jane Austen', 'Austen, Jane',
       NULL, NULL, NULL, NULL, NULL, 0, 1696000000000, 0, NULL, 'file', 0)""",
    "INSERT INTO book_tag VALUES (1, 'Horror', 'calibre'), (1, 'to-reread', 'user'), (2, 'Classic', 'calibre')",
    """INSERT INTO book_state VALUES
      (1, '{"href":"ch4.xhtml","locations":{"progression":0.3}}', 0.42, 'reading', 1699999999000, 0, 5, '{"fontSize":1.2}'),
      (2, NULL, 1.0, 'finished', 1699999990000, 1699999995000, NULL, NULL)""",
    "INSERT INTO bookmark VALUES (1, 1, '{\"href\":\"ch2.xhtml\"}', 'Chapter 2', 0.12, 1698000000000), (2, 2, '{\"href\":\"ch1.xhtml\"}', 'Opening', 0.01, 1698000000001)",
    "INSERT INTO highlight VALUES (1, 1, '{\"href\":\"ch3.xhtml\"}', 'I am Dracula', 'ominous', 0.2, 1698100000000), (2, 1, '{\"href\":\"ch5.xhtml\"}', 'the blood is the life', NULL, 0.5, 1698100000001)",
  )

  /** Builds a version 1 database file with plain Android SQLite, the way an installed v1 app left it. */
  private fun createV1File(): File {
    val file = tempDbFile()
    val helper = object : SQLiteOpenHelper(target, file.absolutePath, null, 1) {
      override fun onCreate(db: SQLiteDatabase) {
        v1Schema.forEach(db::execSQL)
        v1Seed.forEach(db::execSQL)
      }
      override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    helper.writableDatabase.use { }
    return file
  }

  /** Builds a version 2 database file the way an installed v2 app left it: the v1 tables and seed, the 1 -> 2 migration, and one indexed book. */
  private fun createV2File(): File {
    val file = tempDbFile()
    val callback = object : SupportSQLiteOpenHelper.Callback(2) {
      override fun onCreate(db: SupportSQLiteDatabase) {
        v1Schema.forEach(db::execSQL)
        v1Seed.forEach(db::execSQL)
        QuireDatabase.MIGRATION_1_2.migrate(db)
        db.execSQL(
          "INSERT INTO text_chunk (bookId, seq, chapter, href, tokenStart, tokenEnd, primaryEndByte, text, mapping, progression) " +
            "VALUES (1, 0, 'Chapter 1', 'ch1.xhtml', 0, 4, 31, 'Jonathan kept a careful journal', '[]', 0.0)",
        )
        db.execSQL(
          "INSERT INTO index_state (bookId, mtime, sizeBytes, status, completedAt, chunkCount, textBytes, truncated) " +
            "VALUES (1, 1690000000001, 603000, 'done', 1700000000000, 1, 31, 0)",
        )
      }
      override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    val config = SupportSQLiteOpenHelper.Configuration.builder(target).name(file.absolutePath).callback(callback).build()
    FrameworkSQLiteOpenHelperFactory().create(config).use { it.writableDatabase }
    return file
  }

  private fun dumpV1Tables(file: File): Map<String, List<List<String?>>> =
    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      v1Tables.associateWith { t ->
        db.rawQuery("SELECT * FROM $t ORDER BY rowid", null).use { c ->
          buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }
      }
    }

  private fun migrated(): QuireDatabase = open(createV1File())

  @Test fun `migration to the current version keeps every seeded row of every v1 table unchanged`() {
    val file = createV1File()
    val before = dumpV1Tables(file)
    assertTrue("seed must populate each table", before.values.all { it.isNotEmpty() })

    val db = open(file)
    val sqlite = db.openHelper.writableDatabase
    assertEquals(3, sqlite.rows("PRAGMA user_version").single().single()!!.toInt())
    val after = v1Tables.associateWith { t -> sqlite.rows("SELECT * FROM $t ORDER BY rowid") }

    assertEquals(before, after)
    assertTrue(sqlite.rows("PRAGMA foreign_key_check").isEmpty())
    assertEquals("ok", sqlite.rows("PRAGMA integrity_check").single().single())
  }

  @Test fun `migration from version 2 keeps the index and reads every state as fully readable`() = runBlocking {
    val db = open(createV2File())
    val sqlite = db.openHelper.writableDatabase
    assertEquals(3, sqlite.rows("PRAGMA user_version").single().single()!!.toInt())
    val state = db.stateOf(1)!!
    assertEquals(IndexStateEntity.STATUS_DONE, state.status)
    assertEquals(1, state.chunkCount)
    assertEquals(0, state.unreadableResources)
    assertEquals(1, db.hits("journal").size)
    assertEquals(emptyList<EligibleBook>(), db.index().eligibleBooks().filter { it.id == 1L })
    assertEquals("ok", sqlite.rows("PRAGMA integrity_check").single().single())
  }

  @Test fun `migrated data is readable through the existing DAOs`() = runBlocking {
    val db = migrated()
    val dracula = db.books().byId(1)!!
    assertEquals("Dracula", dracula.title)
    assertEquals(1.5, dracula.seriesIndex!!, 0.0)
    assertFalse(db.books().byId(2)!!.readable)
    assertEquals(0.42f, db.states().get(1)!!.progress, 0.0f)
    assertEquals("{\"fontSize\":1.2}", db.states().get(1)!!.prefsJson)
    assertEquals(listOf("Chapter 2"), db.annotations().observeBookmarks(1).first().map { it.label })
    assertEquals(listOf("I am Dracula", "the blood is the life"), db.annotations().observeHighlights(1).first().map { it.text })
    val row = db.books().observeAll().first().single { it.id == 1L }
    assertEquals(setOf("Horror", "to-reread"), row.tagList.toSet())
    assertEquals(listOf("to-reread"), row.userTagList)
    // AUTOINCREMENT continues past the seeded ids rather than reusing them.
    assertEquals(3L, db.annotations().addBookmark(BookmarkEntity(bookId = 1, locatorJson = "{}", label = "new", progress = 0.9f, createdAt = 1)))
  }

  @Test fun `migration adds empty index tables that eligibility already understands`() = runBlocking {
    val db = migrated()
    assertEquals(0, db.chunkCount())
    assertEquals(0, db.openHelper.writableDatabase.count("SELECT COUNT(*) FROM index_state"))
    // Only the readable seeded book needs indexing; the unreadable one is never queued.
    assertEquals(listOf(1L), db.index().eligibleBooks().map { it.id })
  }

  @Test fun `full-text rows follow chunk insert update and delete on the migrated database`() = runBlocking {
    val db = migrated()
    val sqlite = db.openHelper.writableDatabase
    val book = db.books().byId(1)!!
    assertTrue(db.index().replaceBook(1, book.mtime, book.sizeBytes, listOf(chunk(1, 0, "The Count welcomed Jonathan to Transylvania"), chunk(1, 1, "Mina wrote in her journal")), doneState(book, 1, 2)))
    val ids = sqlite.rows("SELECT id FROM text_chunk WHERE bookId = 1 ORDER BY seq").map { it[0]!!.toLong() }

    assertEquals(listOf(ids[0]), db.hits("transylvania"))
    assertEquals(listOf(ids[1]), db.hits("journal"))
    assertEquals("prefix queries are case-folded", listOf(ids[0]), db.hits("TRANSYLV*"))

    sqlite.execSQL("UPDATE text_chunk SET text = 'Lucy slept by the window' WHERE id = ${ids[0]}")
    assertEquals(emptyList<Long>(), db.hits("transylvania"))
    assertEquals(listOf(ids[0]), db.hits("window"))
    assertEquals(listOf(ids[1]), db.hits("journal"))

    sqlite.execSQL("DELETE FROM text_chunk WHERE id = ${ids[1]}")
    assertEquals(emptyList<Long>(), db.hits("journal"))
    assertEquals(listOf(ids[0]), db.hits("window"))
    sqlite.execSQL("INSERT INTO text_chunk_fts(text_chunk_fts) VALUES('integrity-check')")
  }

  @Test fun `deleting a book removes its chunks and full-text rows and index state and nothing else`() = runBlocking {
    val db = open(createV1File())
    val folderId = db.folders().all().first().id
    val other = bookEntity(folderId, "Other", addedAt = 5)
    val otherId = db.books().save(other, emptyList())
    val dracula = db.books().byId(1)!!
    db.index().replaceBook(1, dracula.mtime, dracula.sizeBytes, listOf(chunk(1, 0, "Quincey arrived with a bowie knife")), doneState(dracula, 1, 1))
    db.index().replaceBook(otherId, other.mtime, other.sizeBytes, listOf(chunk(otherId, 0, "Quincey is a name in another book")), doneState(other, otherId, 1))
    assertEquals(2, db.hits("quincey").size)

    db.books().delete(listOf(1L))

    assertEquals(0, db.chunkCount(1))
    assertEquals(null, db.stateOf(1))
    assertEquals(1, db.hits("quincey").size)
    assertEquals(1, db.chunkCount(otherId))
    assertEquals(otherId, db.openHelper.writableDatabase.rows("SELECT bookId FROM text_chunk").single().single()!!.toLong())
    assertTrue(db.stateOf(otherId) != null)
    db.openHelper.writableDatabase.execSQL("INSERT INTO text_chunk_fts(text_chunk_fts) VALUES('integrity-check')")
  }

  @Test fun `removing a folder cascades through its books to the index`() = runBlocking {
    val db = migrated()
    val dracula = db.books().byId(1)!!
    db.index().replaceBook(1, dracula.mtime, dracula.sizeBytes, listOf(chunk(1, 0, "Whitby harbour")), doneState(dracula, 1, 1))
    assertEquals(1, db.hits("whitby").size)

    db.folders().delete(1L)

    assertEquals(emptyList<Long>(), db.hits("whitby"))
    assertEquals(0, db.chunkCount())
    assertEquals(null, db.stateOf(1))
  }
}
