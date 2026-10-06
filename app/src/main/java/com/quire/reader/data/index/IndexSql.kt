package com.quire.reader.data.index

import androidx.room.Transactor
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.quire.reader.data.db.IndexDatabase

/** A connection to the index database, good for running statements one after another. */
interface IndexConnection {
  suspend fun <R> statement(sql: String, block: (SQLiteStatement) -> R): R
}

/**
 * How [IndexStore] reaches the index database: through Room in the app, or through a bare connection in benchmarks.
 * [write] runs its block in one immediate transaction.
 */
interface IndexSql {
  suspend fun <T> read(block: suspend (IndexConnection) -> T): T
  suspend fun <T> write(block: suspend (IndexConnection) -> T): T
  /** Runs [sql] to completion outside any transaction: a pragma such as `incremental_vacuum` returns a row for every page it frees, and stops early if not stepped to the end. */
  suspend fun exec(sql: String)
}

class RoomIndexSql(private val db: IndexDatabase) : IndexSql {
  private class On(private val c: Transactor) : IndexConnection {
    override suspend fun <R> statement(sql: String, block: (SQLiteStatement) -> R): R = c.usePrepared(sql, block)
  }

  override suspend fun <T> read(block: suspend (IndexConnection) -> T): T = db.useReaderConnection { block(On(it)) }

  override suspend fun <T> write(block: suspend (IndexConnection) -> T): T =
    db.useWriterConnection { c -> c.immediateTransaction { block(object : IndexConnection {
      override suspend fun <R> statement(sql: String, block: (SQLiteStatement) -> R): R = usePrepared(sql, block)
    }) } }.also { db.invalidationTracker.refreshAsync() }

  override suspend fun exec(sql: String) = db.useWriterConnection { c -> c.usePrepared(sql) { while (it.step()) Unit } }
}

/** A single bare connection, for tools that build index files without Room. Not thread-safe. */
class ConnectionIndexSql(private val connection: SQLiteConnection) : IndexSql {
  private val on = object : IndexConnection {
    override suspend fun <R> statement(sql: String, block: (SQLiteStatement) -> R): R = connection.prepare(sql).use(block)
  }

  override suspend fun <T> read(block: suspend (IndexConnection) -> T): T = block(on)

  override suspend fun <T> write(block: suspend (IndexConnection) -> T): T {
    connection.execSQL("BEGIN IMMEDIATE")
    try {
      return block(on).also { connection.execSQL("COMMIT") }
    } catch (e: Throwable) {
      connection.execSQL("ROLLBACK")
      throw e
    }
  }

  override suspend fun exec(sql: String) = connection.prepare(sql).use { while (it.step()) Unit }
}

internal suspend fun IndexConnection.exec(sql: String, vararg args: Any?) = statement(sql) { st -> st.bindAll(args); st.step(); Unit }

internal suspend fun IndexConnection.long(sql: String, vararg args: Any?): Long? =
  statement(sql) { st -> st.bindAll(args); if (st.step() && !st.isNull(0)) st.getLong(0) else null }

internal fun SQLiteStatement.bindAll(args: Array<out Any?>) {
  args.forEachIndexed { i, a ->
    when (a) {
      null -> bindNull(i + 1)
      is Long -> bindLong(i + 1, a)
      is Int -> bindLong(i + 1, a.toLong())
      is Boolean -> bindLong(i + 1, if (a) 1 else 0)
      is Double -> bindDouble(i + 1, a)
      is String -> bindText(i + 1, a)
      is ByteArray -> bindBlob(i + 1, a)
      else -> error("cannot bind ${a::class}")
    }
  }
}
