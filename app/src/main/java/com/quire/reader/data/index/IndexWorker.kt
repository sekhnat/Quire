package com.quire.reader.data.index

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.quire.reader.QuireApplication
import com.quire.reader.data.scan.StoragePaths
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * The only thing that runs text extraction. Each run indexes for at most [BATCH_MILLIS] and, if books are left,
 * appends another run to the same chain; requests that arrive meanwhile join the chain too. Always reports success:
 * pausing, a missing permission or a disabled setting end the run quietly, and whatever ended it requests the next one.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val app = applicationContext as QuireApplication
    val indexer = app.indexer
    if (!app.settings.indexingEnabled.first()) return Result.success()
    val hasAccess = StoragePaths.hasAllFilesAccess()
    indexer.setPermissionMissing(!hasAccess)
    // A reader that is open or opening keeps the CPU and storage to itself; closing it requests indexing again.
    if (!hasAccess || indexer.readerBusy.value) return Result.success()
    val t0 = System.currentTimeMillis() // SCALE-PROBE
    android.util.Log.i("ScaleProbe", "batch start id=$id attempt=$runAttemptCount") // SCALE-PROBE
    val result = indexer.runBatch(System.currentTimeMillis() + BATCH_MILLIS)
    android.util.Log.i("ScaleProbe", "batch end id=$id processed=${result.processed} stop=${result.stop} ms=${System.currentTimeMillis() - t0}") // SCALE-PROBE
    if (result.needsContinuation) indexer.request()
    return Result.success()
  }

  private companion object {
    val BATCH_MILLIS = TimeUnit.MINUTES.toMillis(5)
  }
}
