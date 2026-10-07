package com.quire.reader.ui.settings

import android.net.Uri
import android.util.Log
import com.quire.reader.data.backup.BackupException
import com.quire.reader.data.backup.FullBackupManifest
import com.quire.reader.data.backup.MergeOutcome
import com.quire.reader.ui.RestoreUi
import com.quire.reader.ui.Toasts
import com.quire.reader.ui.mergeSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Restoring from a full backup (`FullRestore`), and starting the app again afterwards. */
interface RestoreService {
  suspend fun inspect(uri: Uri): FullBackupManifest
  /** Extracts and checks the backup for the next start; [onProgress] gets null while there is nothing to measure. */
  suspend fun stage(uri: Uri, onProgress: (Float?) -> Unit)
  suspend fun merge(uri: Uri): MergeOutcome
  /** Starts the app again in a new process; the staged restore is swapped in before anything opens it. */
  suspend fun restart()
}

/**
 * The restore sheet, from picking a backup to restoring it. One for the whole app, shared by onboarding and Settings:
 * a restore carries on when its screen is left, and coming back must show it still running.
 */
class RestoreState(
  private val service: RestoreService,
  private val toasts: Toasts,
  private val scope: CoroutineScope,
  private val appScope: CoroutineScope,
) {
  private val _ui = MutableStateFlow<RestoreUi>(RestoreUi.Idle)
  /** Where a restore from a full backup is; drives the restore sheet. */
  val ui: StateFlow<RestoreUi> = _ui

  /** Reads the picked backup's manifest and shows what it holds, so the user can choose how to restore it. */
  fun inspect(uri: Uri) = scope.launch {
    _ui.value = RestoreUi.Reading
    _ui.value = try {
      RestoreUi.Ready(uri, service.inspect(uri))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      toasts.show((e as? BackupException)?.message ?: "Couldn't read that backup")
      RestoreUi.Idle
    }
  }

  /** Closes the restore sheet; a restore already underway carries on. */
  fun dismiss() { if (_ui.value !is RestoreUi.Working) _ui.value = RestoreUi.Idle }

  /**
   * Replaces the library, reading data, index and settings with the backup, then restarts the app so the restored files
   * are opened fresh. Runs on the app's scope: leaving the screen must not abandon a half-extracted restore.
   */
  fun replace() {
    val ready = _ui.value as? RestoreUi.Ready ?: return
    _ui.value = RestoreUi.Working("Restoring…", 0f)
    appScope.launch {
      try {
        service.stage(ready.uri) { p -> _ui.value = RestoreUi.Working(if (p == null) "Checking the backup…" else "Restoring…", p) }
        _ui.value = RestoreUi.Working("Restarting…", null)
        service.restart()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w("RestoreState", "full restore failed", e)
        _ui.value = RestoreUi.Idle
        toasts.show((e as? BackupException)?.message ?: "Couldn't restore that backup")
      }
    }
  }

  /** Adds the backup's reading data (and imported books the library lacks) to the library; nothing here is replaced. */
  fun merge() {
    val ready = _ui.value as? RestoreUi.Ready ?: return
    _ui.value = RestoreUi.Working("Adding the backup's reading data…", null)
    appScope.launch {
      try {
        toasts.show(mergeSummary(service.merge(ready.uri)))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w("RestoreState", "full-backup merge failed", e)
        toasts.show((e as? BackupException)?.message ?: "Couldn't import that backup")
      } finally {
        _ui.value = RestoreUi.Idle
      }
    }
  }
}
