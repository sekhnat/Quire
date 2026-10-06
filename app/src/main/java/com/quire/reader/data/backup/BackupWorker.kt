package com.quire.reader.data.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.quire.reader.QuireApplication
import com.quire.reader.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Writes a full backup in the background, with a progress notification, so a backup of a large index survives the user
 * leaving the app. A manual backup goes to the document the user picked ([KEY_URI]); a scheduled one goes to the backup
 * folder in Settings, as a new file, after which only the newest few backups there are kept.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val app get() = applicationContext as QuireApplication
  private val uri: Uri? get() = inputData.getString(KEY_URI)?.let(Uri::parse)

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

  override suspend fun doWork(): Result {
    // Android 12+ refuses to start a foreground service from the background, as a scheduled run usually is. The backup
    // still runs, only without the notification and its longer time allowance.
    runCatching { setForeground(foregroundInfo(0f)) }.onFailure { Log.i(TAG, "backup runs without a notification: ${it.message}") }
    val contents = app.settings.backupContents.first()
    var lastPercent = -1
    val progress: (Float) -> Unit = { fraction ->
      val percent = (fraction * 100).toInt()
      if (percent != lastPercent) {
        lastPercent = percent
        setProgressAsync(workDataOf(KEY_PROGRESS to fraction))
        notify(fraction)
      }
    }
    return try {
      val target = uri
      if (target != null) writeToDocument(target, contents, progress) else writeToFolder(contents, progress)
      app.settings.recordBackup(System.currentTimeMillis(), null)
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "backup failed", e)
      val message = (e as? BackupException)?.message ?: "Couldn't write the backup"
      app.settings.recordBackup(System.currentTimeMillis(), message)
      Result.failure(workDataOf(KEY_ERROR to message))
    }
  }

  private suspend fun writeToDocument(target: Uri, contents: BackupContents, progress: (Float) -> Unit) {
    val resolver = applicationContext.contentResolver
    try {
      val out = resolver.openOutputStream(target, "wt") ?: throw BackupException("Couldn't write the file")
      app.fullBackupWriter.write(out, contents, progress)
    } catch (e: Throwable) {
      // A partial archive must not pass for a backup.
      runCatching { DocumentsContract.deleteDocument(resolver, target) }
      throw e
    } finally {
      runCatching { resolver.releasePersistableUriPermission(target, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }
  }

  private suspend fun writeToFolder(contents: BackupContents, progress: (Float) -> Unit) {
    val folder = File(app.settings.autoBackupFolder.first())
    if (!folder.isDirectory && !folder.mkdirs()) throw BackupException("Couldn't create the backup folder ${folder.path}")
    val target = BackupFiles.freshFile(folder, System.currentTimeMillis())
    val partial = File(folder, target.name + ".partial")
    try {
      app.fullBackupWriter.write(partial.outputStream(), contents, progress)
      if (!partial.renameTo(target)) throw BackupException("Couldn't write the backup to ${folder.path}")
    } finally {
      partial.delete()
    }
    val keep = app.settings.autoBackupKeep.first()
    BackupFiles.toPrune(folder.listFiles()?.toList().orEmpty(), keep).forEach { old ->
      if (old.delete()) Log.i(TAG, "deleted old backup ${old.name}")
    }
    Log.i(TAG, "wrote ${target.path} (${humanBytes(target.length())})")
  }

  private fun notify(fraction: Float) {
    runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(fraction)) }
  }

  private fun foregroundInfo(fraction: Float?): ForegroundInfo =
    ForegroundInfo(NOTIFICATION_ID, notification(fraction), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

  private fun notification(fraction: Float?) = run {
    val manager = applicationContext.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL) == null) {
      manager.createNotificationChannel(NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_LOW))
    }
    NotificationCompat.Builder(applicationContext, CHANNEL)
      .setSmallIcon(R.drawable.ph_tray_arrow_down)
      .setContentTitle("Backing up your library")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
      .build()
  }

  companion object {
    private const val TAG = "BackupWorker"
    const val KEY_URI = "uri"
    const val KEY_PROGRESS = "progress"
    const val KEY_ERROR = "error"
    /** The manual backup the user started; at most one at a time. */
    const val MANUAL_WORK = "manual-full-backup"
    /** Scheduled backups. */
    const val PERIODIC_WORK = "periodic-full-backup"
    private const val CHANNEL = "backups"
    private const val NOTIFICATION_ID = 4107

    /** Starts a backup into the document at [uri], which the caller holds a persistable write grant for. */
    fun startManual(context: Context, uri: Uri) {
      val request = OneTimeWorkRequestBuilder<BackupWorker>()
        .setInputData(workDataOf(KEY_URI to uri.toString()))
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
      WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** Turns scheduled backups on or off, every [interval]; an unchanged schedule keeps its timing. */
    fun schedule(context: Context, enabled: Boolean, interval: BackupInterval) {
      val wm = WorkManager.getInstance(context)
      if (!enabled) {
        wm.cancelUniqueWork(PERIODIC_WORK)
        return
      }
      val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
      val request = PeriodicWorkRequestBuilder<BackupWorker>(interval.days, TimeUnit.DAYS)
        .setConstraints(constraints)
        .addTag(interval.name)
        .build()
      // UPDATE keeps the period's timing when nothing changed and applies a new interval when it did.
      wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
  }
}
