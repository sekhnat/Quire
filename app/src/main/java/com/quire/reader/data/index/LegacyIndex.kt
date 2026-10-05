package com.quire.reader.data.index

import com.quire.reader.data.db.QuireDatabase

/**
 * Removes the index that lived in `quire.db` before it moved to its own database (see `QuireDatabase.MIGRATION_4_5`), and
 * returns its space to the system. Dropping gigabytes of FTS4 pages holds the library database's write lock while it runs,
 * so the indexer does this in the background, only while no reader is open. Returns how long it took in milliseconds, or
 * null when there was nothing to drop, which costs one lookup.
 */
fun dropLegacyIndex(db: QuireDatabase): Long? {
  val sql = db.openHelper.writableDatabase
  val present = sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('text_chunk', 'text_chunk_fts', 'index_state')")
    .use { it.moveToFirst() && it.getInt(0) > 0 }
  if (!present) return null
  val started = System.currentTimeMillis()
  QuireDatabase.LEGACY_INDEX_TABLES.forEach { sql.execSQL("DROP TABLE IF EXISTS `$it`") }
  sql.execSQL("VACUUM")
  return System.currentTimeMillis() - started
}
