package com.quire.reader.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

/**
 * The search index's SQLite: the bundled build (the platform's has no FTS5), with the settings every connection needs.
 * `auto_vacuum` and `page_size` only take effect before the first table is created, so it is set on every open and is a no-op once the
 * file has tables. `synchronous` is per connection; NORMAL is safe in WAL mode and the index can always be rebuilt.
 */
class IndexDriver(private val pageSize: Int = PAGE_SIZE, private val delegate: BundledSQLiteDriver = BundledSQLiteDriver()) : SQLiteDriver {
  override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool

  override fun open(fileName: String): SQLiteConnection =
    delegate.open(fileName).also { connection ->
      // The bundled driver waits for no lock by default, so a connection opening while another writes would fail at once.
      connection.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
      // Like auto_vacuum, the page size only takes effect on an empty file.
      connection.execSQL("PRAGMA page_size = $pageSize")
      connection.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
      connection.execSQL("PRAGMA synchronous = NORMAL")
      // The bundled build is compiled with SECURE_DELETE, which overwrites every freed page with zeros: deleting a book
      // (or clearing the index) would rewrite all of its pages into the WAL. The index is rebuildable app-private data.
      connection.execSQL("PRAGMA secure_delete = OFF")
      // Lets the WAL shrink back after a checkpoint instead of staying as large as its biggest transaction.
      connection.execSQL("PRAGMA journal_size_limit = $WAL_SIZE_LIMIT")
    }

  companion object {
    const val BUSY_TIMEOUT_MILLIS = 10_000
    const val WAL_SIZE_LIMIT = 64L * 1024 * 1024

    /**
     * Chunk rows are 1–2 KB, so with 4 KB pages much of each page is empty. Measured on 1,500 books: 16 KB pages make the
     * index 11% smaller than 4 KB (8 KB: 8%) with no change in query time.
     */
    const val PAGE_SIZE = 16_384
  }
}
