package com.quire.reader.data.index

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.quire.reader.QuireApplication
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.scan.StoragePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The only thing that runs text extraction, under the "Indexing book text" notification.
 *
 * A run that may start a foreground service (the app is on screen, or exempt from battery optimization) becomes one and
 * indexes until no book is left, carrying on at full speed after the user leaves the app. One that may not, such as a run
 * started by a background scan, indexes for at most [BATCH_MILLIS], within the system's limit for background work, and
 * moves to the foreground as soon as it may; if books are left when its time is up, it appends another run to the same
 * chain. Requests that arrive meanwhile queue behind it, and requests made while no run is in progress start the chain
 * afresh. Pausing, a missing permission or a disabled setting end the run quietly, and whatever ended it requests the next
 * one; a run that fails is retried with backoff, so an error never leaves the chain without a successor.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val notifications = IndexNotifications(context.applicationContext)
  @Volatile private var foreground = false
  private var lastPromotion = 0L
  @Volatile private var coverage: IndexCoverage? = null

  override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foregroundInfo(null)

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
      val started = System.currentTimeMillis()
      try {
        coroutineScope {
          val progress = launch { showProgress(indexer) }
          try {
            promote()
            val result = indexer.runBatch(
              deadline = { if (foreground) Long.MAX_VALUE else started + BATCH_MILLIS },
              betweenBooks = { promote() },
            )
            Log.i(LibraryIndexer.TAG, "batch: ${result.processed} books, stopped: ${result.stop}, foreground: $foreground")
            // Asked for while this run still counts as running, so the continuation queues behind it.
            if (result.needsContinuation) indexer.requestNow()
            if (result.stop == BatchStop.Drained) indexer.optimizeIfDue()
          } finally {
            progress.cancel()
          }
        }
        Result.success()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(LibraryIndexer.TAG, "batch failed; retrying", e)
        Result.retry()
      } finally {
        notifications.cancel()
      }
    }
  }

  /** Moves this run to the foreground once that is allowed. Tried before the first book and after each one. */
  private suspend fun promote() {
    if (foreground) return
    val now = System.currentTimeMillis()
    if (now - lastPromotion < PROMOTION_GAP_MILLIS || !IndexNotifications.canStartForeground(applicationContext)) return
    lastPromotion = now
    try {
      setForeground(notifications.foregroundInfo(coverage))
      foreground = true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.i(LibraryIndexer.TAG, "indexing stays in the background: ${e.message}")
    }
  }

  /** Keeps the notification's count current. Waits first, so a run that settles a book or two shows nothing. */
  private suspend fun showProgress(indexer: LibraryIndexer) {
    delay(PROGRESS_DELAY_MILLIS)
    try {
      indexer.catalog.observeCoverage().conflate().collect {
        coverage = it
        notifications.update(it)
        delay(PROGRESS_GAP_MILLIS)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(LibraryIndexer.TAG, "indexing progress stopped updating", e)
    }
  }

  private fun stop(reason: String): Result {
    Log.i(LibraryIndexer.TAG, "worker not run: $reason")
    return Result.success()
  }

  private companion object {
    val BATCH_MILLIS = TimeUnit.MINUTES.toMillis(5)
    val PROMOTION_GAP_MILLIS = TimeUnit.SECONDS.toMillis(30)
    val PROGRESS_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(10)
    val PROGRESS_GAP_MILLIS = TimeUnit.SECONDS.toMillis(1)
  }
}
