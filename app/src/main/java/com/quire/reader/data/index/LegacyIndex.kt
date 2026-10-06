package com.quire.reader.data.index

import androidx.sqlite.db.SupportSQLiteDatabase
import com.quire.reader.data.db.QuireDatabase
import kotlinx.coroutines.delay

/**
 * Removes the index that lived in `quire.db` before it moved to its own database (see `QuireDatabase.MIGRATION_4_5`), and
 * returns its space to the system. Dropping a table walks every overflow page of every row to free it, so dropping the 4 GB
 * of a large library in one go held the library database's write lock for 65 s (measured). Instead the rows are deleted in
 * slices of a couple of thousand, each its own short transaction with a pause after it, so reading positions, bookmarks and
 * scans can write in between; only the emptied tables are then dropped. [keepGoing] is asked between slices, and a false
 * stops the work, which the next call resumes where it stopped. Returns how long it took in milliseconds, or null when
 * there was nothing to remove or it was stopped. Nothing is lost by stopping: the old tables are never read again.
 */
suspend fun dropLegacyIndex(
  sql: SupportSQLiteDatabase,
  keepGoing: () -> Boolean = { true },
  pauseMillis: Long = SLICE_PAUSE_MILLIS,
  onSlice: (Long) -> Unit = {},
): Long? {
  val present = sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('text_chunk', 'text_chunk_fts', 'index_state')")
    .use { it.moveToFirst() && it.getInt(0) > 0 }
  if (!present) return null
  val started = System.currentTimeMillis()
  // The big tables first. `text_chunk_fts_segments` is a shadow table of the old FTS4 index, which is dropped whole after.
  for ((table, slice) in listOf("text_chunk" to TEXT_SLICE_ROWS, "text_chunk_fts_segments" to SEGMENT_SLICE_ROWS)) {
    while (tableExists(sql, table)) {
      val t = System.nanoTime()
      sql.execSQL("DELETE FROM `$table` WHERE rowid IN (SELECT rowid FROM `$table` LIMIT $slice)")
      val removed = sql.query("SELECT changes()").use { it.moveToFirst(); it.getLong(0) }
      onSlice((System.nanoTime() - t) / 1_000_000)
      if (removed < slice) break
      if (!keepGoing()) return null
      delay(pauseMillis)
    }
  }
  if (!keepGoing()) return null
  QuireDatabase.LEGACY_INDEX_TABLES.forEach { sql.execSQL("DROP TABLE IF EXISTS `$it`") }
  sql.execSQL("VACUUM")
  return System.currentTimeMillis() - started
}

private fun tableExists(sql: SupportSQLiteDatabase, name: String): Boolean =
  sql.query("SELECT COUNT(*) FROM sqlite_master WHERE name = ?", arrayOf(name)).use { it.moveToFirst() && it.getInt(0) > 0 }

/**
 * Old chunks are about 3 KB with their mapping. Measured on a 4.2 GB legacy index on the test emulator: slices of 5,000 rows
 * held the lock for up to 1.7 s (median 0.14 s) and 2,000 rows for up to 1.0 s (median 0.08 s); the whole removal takes
 * about two minutes in the background, half of it the pauses, against 65 s of continuous lock in one transaction.
 */
private const val TEXT_SLICE_ROWS = 2_000L
private const val SEGMENT_SLICE_ROWS = 8_000L
private const val SLICE_PAUSE_MILLIS = 100L
