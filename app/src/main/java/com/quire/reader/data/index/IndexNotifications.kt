package com.quire.reader.data.index

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.quire.reader.R
import com.quire.reader.data.db.IndexCoverage
import java.text.NumberFormat

/** The "Indexing book text" notification [IndexWorker] runs under, with how many books have been settled so far. */
internal class IndexNotifications(private val context: Context) {
  private val manager = context.getSystemService(NotificationManager::class.java)

  fun foregroundInfo(coverage: IndexCoverage?): ForegroundInfo =
    ForegroundInfo(NOTIFICATION_ID, notification(coverage), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

  /** Updates the progress shown; without the notification permission nothing shows, and indexing carries on regardless. */
  fun update(coverage: IndexCoverage) {
    runCatching { manager.notify(NOTIFICATION_ID, notification(coverage)) }
  }

  /** Removes a notification posted while the worker was not in the foreground; WorkManager removes its own. */
  fun cancel() {
    runCatching { manager.cancel(NOTIFICATION_ID) }
  }

  private fun notification(coverage: IndexCoverage?) = run {
    if (manager.getNotificationChannel(CHANNEL) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL, "Library indexing", NotificationManager.IMPORTANCE_LOW).apply {
          description = "Shown while Quire reads your books' text so you can search inside them."
        },
      )
    }
    val done = coverage?.let { it.searchable + it.failed + it.skipped }
    val total = coverage?.eligible
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
    NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(R.drawable.ph_magnifying_glass)
      .setContentTitle("Indexing book text")
      .setContentText(if (done != null && total != null && total > 0) "${number(done)} of ${number(total)} books" else "Getting ready…")
      .setContentIntent(open)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setShowWhen(false)
      .setProgress(total ?: 0, done ?: 0, done == null || total == null || total == 0)
      .build()
  }

  private fun number(n: Int) = NumberFormat.getIntegerInstance().format(n)

  companion object {
    private const val CHANNEL = "indexing"
    private const val NOTIFICATION_ID = 4108

    /**
     * Whether a foreground service may start now. From Android 12 one may not start from the background unless the user
     * exempted the app from battery optimization. Asking anyway is not harmless: WorkManager then counts the worker as
     * foreground although no service started, so it is only asked when it will be allowed.
     */
    fun canStartForeground(context: Context): Boolean {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
      val state = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
      if (state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return true
      return context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
  }
}
