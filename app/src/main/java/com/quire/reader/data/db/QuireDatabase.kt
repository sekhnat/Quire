package com.quire.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    FolderEntity::class, BookEntity::class, BookTagEntity::class, BookStateEntity::class, BookmarkEntity::class, HighlightEntity::class,
  ],
  version = QuireDatabase.VERSION,
  exportSchema = false,
)
abstract class QuireDatabase : RoomDatabase() {
  abstract fun folders(): FolderDao
  abstract fun books(): BookDao
  abstract fun states(): StateDao
  abstract fun annotations(): AnnotationDao
  abstract fun snapshots(): SnapshotDao
  abstract fun search(): SearchDao

  companion object {
    private const val FTS_DELETE_TRIGGER_NAME = "room_fts_content_sync_text_chunk_fts_BEFORE_DELETE"

    /** Keeps the full-text table in step when a chunk is deleted; the statement Room generates, see [MIGRATION_1_2]. */
    const val FTS_DELETE_TRIGGER =
      "CREATE TRIGGER IF NOT EXISTS $FTS_DELETE_TRIGGER_NAME BEFORE DELETE ON `text_chunk` BEGIN DELETE FROM `text_chunk_fts` WHERE `docid`=OLD.`rowid`; END"
    const val DROP_FTS_DELETE_TRIGGER = "DROP TRIGGER IF EXISTS $FTS_DELETE_TRIGGER_NAME"

    /**
     * Adds the library text index. The statements are the ones Room generates for these entities, so its
     * schema validation passes; the sync triggers are repeated here although Room also recreates them
     * after every migration. Existing tables are not touched.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
          "CREATE TABLE IF NOT EXISTS `text_chunk` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `chapter` TEXT NOT NULL, `href` TEXT NOT NULL, `tokenStart` INTEGER NOT NULL, `tokenEnd` INTEGER NOT NULL, `primaryEndByte` INTEGER NOT NULL, `text` TEXT NOT NULL, `mapping` TEXT NOT NULL, `progression` REAL NOT NULL, FOREIGN KEY(`bookId`) REFERENCES `book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
          "CREATE UNIQUE INDEX IF NOT EXISTS `index_text_chunk_bookId_seq` ON `text_chunk` (`bookId`, `seq`)",
          "CREATE VIRTUAL TABLE IF NOT EXISTS `text_chunk_fts` USING FTS4(`text` TEXT NOT NULL, tokenize=unicode61, content=`text_chunk`)",
          "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_text_chunk_fts_BEFORE_UPDATE BEFORE UPDATE ON `text_chunk` BEGIN DELETE FROM `text_chunk_fts` WHERE `docid`=OLD.`rowid`; END",
          FTS_DELETE_TRIGGER,
          "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_text_chunk_fts_AFTER_UPDATE AFTER UPDATE ON `text_chunk` BEGIN INSERT INTO `text_chunk_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END",
          "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_text_chunk_fts_AFTER_INSERT AFTER INSERT ON `text_chunk` BEGIN INSERT INTO `text_chunk_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END",
          "CREATE TABLE IF NOT EXISTS `index_state` (`bookId` INTEGER NOT NULL, `mtime` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, `chunkCount` INTEGER NOT NULL, `textBytes` INTEGER NOT NULL, `truncated` INTEGER NOT NULL, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        ).forEach(db::execSQL)
      }
    }

    /** Records how many resources of an indexed book could not be read. Books indexed before it read as fully readable (0). */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `index_state` ADD COLUMN `unreadableResources` INTEGER NOT NULL DEFAULT 0")
      }
    }

    /**
     * Gives books identity keys and a missing state, so a vanished file no longer deletes its reading history. Columns are
     * only added: rebuilding `book` would cascade-delete everything that hangs off it. Existing books get their keys on the
     * next scan.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
          "ALTER TABLE `book` ADD COLUMN `calibreUuid` TEXT",
          "ALTER TABLE `book` ADD COLUMN `epubUid` TEXT",
          "ALTER TABLE `book` ADD COLUMN `fingerprint` TEXT",
          "ALTER TABLE `book` ADD COLUMN `missingSince` INTEGER",
          "CREATE INDEX IF NOT EXISTS `index_book_calibreUuid` ON `book` (`calibreUuid`)",
          "CREATE INDEX IF NOT EXISTS `index_book_epubUid` ON `book` (`epubUid`)",
          "CREATE INDEX IF NOT EXISTS `index_book_fingerprint` ON `book` (`fingerprint`)",
        ).forEach(db::execSQL)
      }
    }

    /**
     * Moves the library text index out to [IndexDatabase]. Only the sync triggers are dropped here, which is instant: the
     * old tables can hold gigabytes, and dropping them is left to a one-off cleanup in the background ([LEGACY_INDEX_TABLES]).
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT").forEach {
          db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_text_chunk_fts_$it")
        }
      }
    }

    /** The tables of the index as it was before [MIGRATION_4_5], in the order they can be dropped. */
    val LEGACY_INDEX_TABLES = listOf("text_chunk_fts_terms", "text_chunk_fts", "text_chunk", "index_state")

    const val FILE_NAME = "quire.db"
    /** The schema version; a full backup from a newer one cannot be restored. */
    const val VERSION = 5

    /** [name] exists so tests can open throwaway files with the production migrations; the app uses the default. */
    fun create(context: Context, name: String = FILE_NAME): QuireDatabase =
      Room.databaseBuilder(context.applicationContext, QuireDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()
  }
}
