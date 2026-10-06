package com.quire.reader.data.db

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.quire.reader.data.index.dropLegacyIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the version 1 -> 5, 2 -> 5, 3 -> 5 and 4 -> 5 migrations on the device's own SQLite, over a real file, and the
 * one-off removal of the index that version 5 moved out of `quire.db`.
 */
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

  /**
   * Builds a version 2, 3 or 4 database file the way an installed app of that version left it: the v1 tables and seed,
   * the migrations up to [version], and one indexed book.
   */
  private fun createV2File(version: Int = 2): File {
    val file = tempDbFile()
    val callback = object : SupportSQLiteOpenHelper.Callback(version) {
      override fun onCreate(db: SupportSQLiteDatabase) {
        v1Schema.forEach(db::execSQL)
        v1Seed.forEach(db::execSQL)
        QuireDatabase.MIGRATION_1_2.migrate(db)
        if (version >= 3) QuireDatabase.MIGRATION_2_3.migrate(db)
        if (version >= 4) QuireDatabase.MIGRATION_3_4.migrate(db)
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
    assertEquals(5, sqlite.rows("PRAGMA user_version").single().single()!!.toInt())
    // Later versions only append columns, so the v1 columns come first and must hold exactly what they held.
    val after = v1Tables.associateWith { t -> sqlite.rows("SELECT * FROM $t ORDER BY rowid").map { it.take(before.getValue(t).first().size) } }

    assertEquals(before, after)
    assertTrue(sqlite.rows("PRAGMA foreign_key_check").isEmpty())
    assertEquals("ok", sqlite.rows("PRAGMA integrity_check").single().single())
  }

  private val legacyTriggers = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'room_fts_content_sync_text_chunk_fts%'"
  private val legacyTables = "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('text_chunk', 'text_chunk_fts', 'index_state', 'text_chunk_fts_terms')"

  @Test fun `migration from versions 2 to 4 leaves the old index inert and its one-off removal keeps every user row`() = runBlocking {
    for (version in 2..4) {
      val file = createV2File(version)
      val before = dumpV1Tables(file)
      val db = open(file)
      val sqlite = db.openHelper.writableDatabase
      assertEquals(5, sqlite.rows("PRAGMA user_version").single().single()!!.toInt())
      // Nothing writes to the old tables any more, but dropping them is left to the background.
      assertEquals(0, sqlite.count(legacyTriggers))
      assertTrue(sqlite.count(legacyTables) >= 3)
      sqlite.execSQL("INSERT INTO book (path, folderId, sizeBytes, mtime, title, sortTitle, author, primaryAuthor, authorSort, calibreRating, addedAt, pageEstimate, source, readable) VALUES ('/x.epub', 1, 1, 1, 'X', 'X', 'A', 'A', 'A', 0, 1, 0, 'file', 1)")

      assertTrue(dropLegacyIndex(sqlite) != null)
      assertEquals(null, dropLegacyIndex(sqlite))

      assertEquals(0, sqlite.count(legacyTables))
      val after = v1Tables.associateWith { t -> sqlite.rows("SELECT * FROM $t ORDER BY rowid").map { it.take(before.getValue(t).first().size) } }
      assertEquals(before, after.mapValues { (t, rows) -> if (t == "book") rows.dropLast(1) else rows }) // less the book added above
      assertEquals(listOf(1L, 2L, 3L), db.books().observeAll().first().map { it.id }.sorted())
      assertEquals("ok", sqlite.rows("PRAGMA integrity_check").single().single())
      db.close()
    }
  }

  @Test fun `stopping the removal part-way leaves a working database that a later call finishes`() = runBlocking<Unit> {
    val db = open(createV2File(version = 4))
    val sqlite = db.openHelper.writableDatabase
    repeat(30) { i -> sqlite.execSQL("INSERT INTO text_chunk (bookId, seq, chapter, href, tokenStart, tokenEnd, primaryEndByte, text, mapping, progression) VALUES (1, ${i + 1}, 'c', 'h', 0, 1, 1, 'text $i', '[]', 0.0)") }
    var slices = 0
    // Slices of 2,000 rows are too big for this fixture, so stop after the first one through keepGoing.
    assertEquals(null, dropLegacyIndex(sqlite, keepGoing = { slices++ < 0 }, pauseMillis = 0))
    assertEquals("a slice smaller than its limit ends that table, so only the final drop is left", 1, sqlite.count("SELECT COUNT(*) FROM sqlite_master WHERE name = 'text_chunk'"))
    assertTrue(dropLegacyIndex(sqlite, pauseMillis = 0) != null)
    assertEquals(0, sqlite.count(legacyTables))
  }

  @Test fun `a new install has none of the old index tables and nothing to drop`() = runBlocking<Unit> {
    val db = open()
    assertEquals(0, db.openHelper.writableDatabase.count(legacyTables))
    assertEquals(null, dropLegacyIndex(db.openHelper.writableDatabase))
  }

  @Test fun `migration from version 3 keeps every book in the library with its history and identity still to be read`() = runBlocking {
    val db = open(createV2File(version = 3))
    val sqlite = db.openHelper.writableDatabase
    assertEquals(5, sqlite.rows("PRAGMA user_version").single().single()!!.toInt())
    assertEquals(
      listOf(listOf<String?>(null, null, null, null), listOf<String?>(null, null, null, null)),
      sqlite.rows("SELECT calibreUuid, epubUid, fingerprint, missingSince FROM book ORDER BY id"),
    )
    assertEquals(listOf(1L, 2L), db.books().observeAll().first().map { it.id }.sorted())
    assertEquals(listOf(false, false), db.books().knownFiles().sortedBy { it.id }.map { it.hasIdentity })
    assertEquals(listOf(false, false), db.books().knownFiles().sortedBy { it.id }.map { it.missing })
    assertEquals(emptyList<MissingBookRow>(), db.books().observeMissing().first())
    assertEquals(2, db.annotations().observeHighlights(1).first().size)
    assertEquals(listOf("index_book_calibreUuid", "index_book_epubUid", "index_book_fingerprint"),
      sqlite.rows("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'book' AND name IN ('index_book_calibreUuid', 'index_book_epubUid', 'index_book_fingerprint') ORDER BY name").map { it[0] })
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
}
