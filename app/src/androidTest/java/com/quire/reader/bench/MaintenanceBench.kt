package com.quire.reader.bench

import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.index.IndexStore
import com.quire.reader.data.index.RoomIndexSql
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Step 8: what housekeeping costs on a built variant (`-e variant`, `-e tag`, `-e pagesize`): sweeping away 100 books, a
 * merge to the fastest form, replacing one large book, and clearing everything, with the file and WAL sizes after each.
 */
class MaintenanceBench : BenchStep() {
  private fun sizes(f: File) = "file=${f.length()}\twal=${File(f.path + "-wal").length()}"

  @Test fun maintenance() = runBlocking<Unit> {
    requireBench()
    val v = Variant.valueOf(arg("variant"))
    val file = dbFile(v)
    val db = IndexDatabase.create(ctx, file.absolutePath, pageSize)
    val store = IndexStore(RoomIndexSql(db))
    fun lap(label: String, since: Long) = report("maint.tsv", "$v\t$label\t${(System.nanoTime() - since) / 1_000_000} ms\t${sizes(file)}")
    report("maint.tsv", "$v\tstart\t\t${sizes(file)}")
    val ids = db.states().all().map { it.bookId }.sorted()

    var t = System.nanoTime()
    store.retainOnly(ids.drop(100))
    lap("remove 100 books", t)
    t = System.nanoTime()
    store.incrementalVacuum()
    lap("incremental vacuum + truncating checkpoint", t)

    val big = db.states().all().maxBy { it.chunkCount }
    val chunks = com.quire.reader.data.index.TextChunker.chunk(cache.read((big.bookId - 1).toInt())).chunks
    t = System.nanoTime()
    store.replaceBook(big.bookId, big.mtime, big.sizeBytes, chunks, false, 0)
    lap("replace the largest book (${chunks.size} chunks)", t)

    t = System.nanoTime()
    var steps = 0
    while (!store.mergeStep()) steps++
    lap("merge in $steps steps", t)
    store.incrementalVacuum()
    report("maint.tsv", "$v\tafter merge\t\t${sizes(file)}")

    t = System.nanoTime()
    store.clearAll()
    lap("clear all", t)
    t = System.nanoTime()
    store.incrementalVacuum()
    lap("vacuum after clear", t)
    db.close()
  }
}

/** Step 9: the one-off removal of the old index from `quire.db` (`-e variant A` built by [BuildBench]; works on a copy). */
class LegacyDropBench : BenchStep() {
  @Test fun drop() {
    requireBench()
    val src = dbFile(Variant.A)
    val copy = File(dir, "A-drop.db")
    listOf("", "-wal", "-shm").forEach { File(copy.path + it).delete() }
    src.copyTo(copy, overwrite = true)
    val callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
      override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
      override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx).name(copy.path).callback(callback).build()
    val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(config)
    val db = helper.writableDatabase
    report("legacy.tsv", "sliced before\tfile=${copy.length()}")
    val slices = ArrayList<Long>()
    val t = System.nanoTime()
    val ms = kotlinx.coroutines.runBlocking { com.quire.reader.data.index.dropLegacyIndex(db, onSlice = { slices += it }) }
    report("legacy.tsv", "sliced drop\t${(System.nanoTime() - t) / 1_000_000} ms\ttotal=$ms\tslices=${slices.size}\tlongest slice=${slices.maxOrNull()} ms\tmedian slice=${slices.sorted().getOrNull(slices.size / 2)} ms\tfile=${copy.length()}\twal=${File(copy.path + "-wal").length()}")
    helper.close()
    listOf("", "-wal", "-shm").forEach { File(copy.path + it).delete() }
  }
}
