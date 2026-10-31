package com.quire.reader.data.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.quire.reader.QuireApplication
import com.quire.reader.data.runCatchingCancellable
import java.util.concurrent.TimeUnit

/** Picks up books added to (or removed from) watched folders while the app isn't open. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    if (!StoragePaths.hasAllFilesAccess()) return Result.success()
    // A scan that keeps failing gives up until the next period instead of retrying for ever.
    return runCatchingCancellable { (applicationContext as QuireApplication).library.rescan() }
      .fold({ Result.success() }, { if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure() })
  }

  companion object {
    private const val NAME = "periodic-library-scan"
    private const val EVERY_HOURS = 6L
    private const val MAX_RETRIES = 3

    /** Turns the background scan on or off to match the "Watch for new books" setting. */
    fun schedule(context: Context, enabled: Boolean) {
      val wm = WorkManager.getInstance(context)
      if (enabled) {
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ScanWorker>(EVERY_HOURS, TimeUnit.HOURS).build())
      } else {
        wm.cancelUniqueWork(NAME)
      }
    }
  }
}
