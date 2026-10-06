package com.quire.reader.data.index

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.quire.reader.QuireApplication
import com.quire.reader.data.scan.StoragePaths
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * The only thing that runs text extraction. Each run indexes for at most [BATCH_MILLIS] and, if books are left,
 * appends another run to the same chain; requests that arrive meanwhile queue behind it, and requests made while no run
 * is in progress start the chain afresh. Always reports success: pausing, a missing permission or a disabled setting end
 * the run quietly, and whatever ended it requests the next one.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val app = applicationContext as QuireApplication
    val indexer = app.indexer
    return indexer.asWorker {
      if (!app.settings.indexingEnabled.first()) return@asWorker stop("indexing is disabled")
      val hasAccess = StoragePaths.hasAllFilesAccess()
      indexer.setPermissionMissing(!hasAccess)
      if (!hasAccess) return@asWorker stop("no all-files access")
      // A reader that is open or opening keeps the CPU and storage to itself; closing it requests indexing again.
      if (indexer.readerBusy.value) return@asWorker stop("a reader is open")
      val result = indexer.runBatch(System.currentTimeMillis() + BATCH_MILLIS)
      Log.i(LibraryIndexer.TAG, "batch: ${result.processed} books, stopped: ${result.stop}")
      if (result.needsContinuation) indexer.request()
      if (result.stop == BatchStop.Drained) indexer.optimizeIfDue()
      Result.success()
    }
  }

  private fun stop(reason: String): Result {
    Log.i(LibraryIndexer.TAG, "worker not run: $reason")
    return Result.success()
  }

  private companion object {
    val BATCH_MILLIS = TimeUnit.MINUTES.toMillis(5)
  }
}
