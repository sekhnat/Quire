package com.quire.reader.ui

import android.net.Uri
import com.quire.reader.data.backup.BackupContents
import com.quire.reader.data.backup.BackupInterval
import com.quire.reader.data.backup.FullBackupManifest

/** The full-backup settings and what backups are doing, for Settings. */
data class BackupUi(
  val contents: BackupContents = BackupContents(),
  val auto: Boolean = false,
  val interval: BackupInterval = BackupInterval.Weekly,
  val folder: String = "",
  val keep: Int = 5,
  /** True while a backup is queued or running. */
  val running: Boolean = false,
  /** 0..1 while a running backup reports progress, else null. */
  val progress: Float? = null,
  /** When the last backup finished, epoch millis; 0 = never. */
  val lastAt: Long = 0,
  /** Why the last backup failed, or null when it did not. */
  val lastError: String? = null,
)

/** How much the optional parts of a full backup take. */
data class BackupSizes(val covers: Int, val coverBytes: Long, val imported: Int, val importedBytes: Long)

/** Where a restore from a full backup is. */
sealed interface RestoreUi {
  data object Idle : RestoreUi
  /** Reading the picked file's manifest. */
  data object Reading : RestoreUi
  /** The backup was read; the user chooses how to restore it. */
  data class Ready(val uri: Uri, val manifest: FullBackupManifest) : RestoreUi
  /** Restoring; [progress] is null while there is nothing to measure. */
  data class Working(val label: String, val progress: Float?) : RestoreUi
}
