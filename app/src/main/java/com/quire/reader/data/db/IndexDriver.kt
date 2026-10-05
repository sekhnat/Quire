package com.quire.reader.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

/**
 * The search index's SQLite: the bundled build (the platform's has no FTS5), with the settings every connection needs.
 * `auto_vacuum` only takes effect before the first table is created, so it is set on every open and is a no-op once the
 * file has tables. `synchronous` is per connection; NORMAL is safe in WAL mode and the index can always be rebuilt.
 */
class IndexDriver(private val delegate: BundledSQLiteDriver = BundledSQLiteDriver()) : SQLiteDriver {
  override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool

  override fun open(fileName: String): SQLiteConnection =
    delegate.open(fileName).also { connection ->
      connection.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
      connection.execSQL("PRAGMA synchronous = NORMAL")
    }
}
