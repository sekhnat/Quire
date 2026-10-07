package com.quire.reader.ui.settings

import android.net.Uri
import com.quire.reader.data.backup.BackupContents
import com.quire.reader.data.backup.BackupInterval
import com.quire.reader.data.backup.ImportResult
import com.quire.reader.ui.BackupSizes
import com.quire.reader.ui.BackupUi
import com.quire.reader.ui.Toasts
import com.quire.reader.ui.importSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How importing a reading-data file went. */
sealed interface ReadingDataImport {
  data object Unreadable : ReadingDataImport
  data object NewerVersion : ReadingDataImport
  data object NotReadingData : ReadingDataImport
  data object Failed : ReadingDataImport
  data class Imported(val result: ImportResult) : ReadingDataImport
}

/** Full backups and reading-data files (`SettingsStore`, `BackupWorker`, `SnapshotWriter`, `SnapshotImporter`). */
interface BackupService {
  /** The saved backup choices: contents, auto, interval, folder, keep. */
  val choices: Flow<BackupUi>
  /** What backups are doing: running, progress, lastAt, lastError. */
  val work: Flow<BackupUi>
  /** What covers and imported books would add to a full backup. */
  suspend fun sizes(): BackupSizes
  /** Starts a full backup into [uri] in the background. */
  fun start(uri: Uri)
  suspend fun setContents(contents: BackupContents)
  suspend fun setAutoBackup(enabled: Boolean)
  suspend fun setInterval(interval: BackupInterval)
  suspend fun setKeep(keep: Int)
  suspend fun setFolder(path: String)
  /** Writes the reading-data snapshot to [uri]; false when the file couldn't be written. */
  suspend fun exportReadingData(uri: Uri): Boolean
  /** Merges a reading-data file into the library; what the library has is never overwritten. */
  suspend fun importReadingData(uri: Uri): ReadingDataImport
}

/** Settings' backup section: full-backup choices and status, and exporting and importing reading data. */
class BackupState(
  private val service: BackupService,
  private val toasts: Toasts,
  private val scope: CoroutineScope,
  private val persist: CoroutineScope,
) {
  /** Full-backup settings and the state of the last or running backup. */
  val backup: StateFlow<BackupUi> = combine(service.choices, service.work) { choices, work ->
    choices.copy(running = work.running, progress = work.progress, lastAt = work.lastAt, lastError = work.lastError)
  }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), BackupUi())

  private val _sizes = MutableStateFlow<BackupSizes?>(null)
  /** What covers and imported books add to a full backup, or null until measured. */
  val sizes: StateFlow<BackupSizes?> = _sizes

  fun refreshSizes() = scope.launch { _sizes.value = service.sizes() }

  fun setContents(contents: BackupContents) = persist.launch { service.setContents(contents) }
  fun setAutoBackup(enabled: Boolean) = persist.launch { service.setAutoBackup(enabled) }
  fun setInterval(interval: BackupInterval) = persist.launch { service.setInterval(interval) }
  fun setKeep(keep: Int) = persist.launch { service.setKeep(keep) }
  fun setFolder(path: String) = persist.launch { service.setFolder(path) }

  /** Starts a full backup into the document the user just created; it runs in the background with a notification. */
  fun start(uri: Uri) {
    service.start(uri)
    toasts.show("Backing up in the background")
  }

  /** Writes the reading-data snapshot to a file the user picked (Settings → Export reading data). */
  fun exportReadingData(uri: Uri) = persist.launch {
    toasts.show(if (service.exportReadingData(uri)) "Reading data exported" else "Couldn't write the file")
  }

  /** Merges a picked reading-data file into the library. */
  fun importReadingData(uri: Uri) = persist.launch {
    toasts.show(
      when (val r = service.importReadingData(uri)) {
        ReadingDataImport.Unreadable -> "Couldn't read the file"
        ReadingDataImport.NewerVersion -> "That file was written by a newer Quire"
        ReadingDataImport.NotReadingData -> "That file isn't Quire reading data"
        ReadingDataImport.Failed -> "Couldn't import the reading data"
        is ReadingDataImport.Imported -> importSummary(r.result)
      },
    )
  }
}
