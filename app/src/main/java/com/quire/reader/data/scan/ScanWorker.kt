package com.quire.reader.data.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.quire.reader.QuireApplication
import java.util.concurrent.TimeUnit

/** Picks up books added to (or removed from) watched folders while the app isn't open. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    if (!StoragePaths.hasAllFilesAccess()) return Result.success()
    return runCatching { (applicationContext as QuireApplication).library.rescan() }.fold({ Result.success() }, { Result.retry() })
  }

  companion object {
    private const val NAME = "periodic-library-scan"
    private const val EVERY_HOURS = 6L

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
