package com.quire.reader.data.backup

import android.content.Context
import android.util.Log
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.QuireDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** What [RestoreCoordinator] found at startup. */
sealed interface RestoreState {
  /** [RestoreCoordinator.prepare] has not run yet. */
  data object Uninitialized : RestoreState
  /** Nothing to restore. */
  data object None : RestoreState
  /** A snapshot is staged and waits for the first completed scan to import it. */
  data class Pending(val entries: Int) : RestoreState
  /** A snapshot file is present but cannot be used; it is kept untouched for recovery, never overwritten. */
  data class Unreadable(val reason: String) : RestoreState
}

/**
 * Detects a snapshot that Android restored onto this install, and keeps it safe until the library it
 * describes has been scanned again.
 *
 * The snapshot file in `files/backup/` is normally the writer's own output and means nothing on a
 * running install. It means a restore when the database is empty (Android just restored the backup
 * onto a clean install) or when a staged copy already exists (an earlier process died mid-restore).
 * In both cases a byte-identical copy is staged into `noBackupFilesDir`, where the writer and future
 * backups can never touch it, and the portable settings are applied so onboarding and scanning start
 * from the user's own choices. A backed-up "onboarding done" flag is overridden: routing must not
 * skip the scan the restore needs.
 *
 * The staged copy plus a phase marker make the restore resumable: onboarding, scanning and importing
 * can each be interrupted and the next start picks up where the last one left off.
 */
class RestoreCoordinator(
  private val context: Context,
  private val db: QuireDatabase,
  private val settings: SettingsStore,
  private val importer: SnapshotImporter,
  /** The snapshot the writer maintains; tests point this at a scratch file. */
  private val snapshotLocation: File = snapshotFile(context),
  /** Where the staged copy and its phase marker live; tests point this at a scratch directory. */
  private val pendingDirectory: File = context.noBackupFilesDir,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val mutex = Mutex()
  private val _state = MutableStateFlow<RestoreState>(RestoreState.Uninitialized)
  val state: StateFlow<RestoreState> = _state.asStateFlow()

  /** True while a staged restore waits; [SnapshotWriter] stands down until the import is done. */
  val isPending: Boolean get() = _state.value is RestoreState.Pending

  /** Invalid or newer snapshots are recovery artifacts too, not permission to overwrite them. */
  val isWriteBlocked: Boolean get() = _state.value != RestoreState.None

  private val pendingFile get() = File(pendingDirectory, PENDING_FILE_NAME)
  private val phaseFile get() = File(pendingDirectory, PHASE_FILE_NAME)

  /**
   * Runs once per process, before onboarding routing or any background work is scheduled. Every
   * caller shares one run; later calls return immediately.
   */
  suspend fun prepare() = mutex.withLock {
    if (_state.value != RestoreState.Uninitialized) return@withLock
    val text = runCatching { snapshotLocation.takeIf { it.isFile }?.readText() }.getOrNull()
    if (text == null) {
      _state.value = RestoreState.None
      return@withLock
    }
    when (val decoded = SnapshotCodec.decode(text)) {
      is SnapshotCodec.Decoded.Ok -> {
        if (pendingFile.isFile && phaseFile.readTextOrNull() == PHASE_IMPORTED) {
          // The previous process imported and died before cleaning up.
          clearPending()
          _state.value = RestoreState.None
          return@withLock
        }
        val emptyLibrary = db.books().totalCount() == 0
        if (!emptyLibrary && !pendingFile.isFile) {
          // A normal install: the file is the writer's own output.
          _state.value = RestoreState.None
          return@withLock
        }
        if (stage(decoded)) {
          if (emptyLibrary) {
            settings.applySettings(decoded.snapshot.settings)
            // DataStore travels but the index and covers do not: never inherit maintenance completion.
            settings.setIndexOptimized(false)
            settings.setCoversBackfilled(false)
            // A backed-up "onboarding done" must not skip the scan the restore needs.
            if (settings.onboardingDone.first()) settings.setOnboardingDone(false)
          }
          _state.value = RestoreState.Pending(decoded.snapshot.books.size)
        } else {
          _state.value = RestoreState.Unreadable("could not stage the snapshot")
        }
      }
      is SnapshotCodec.Decoded.Malformed ->
        _state.value = if (db.books().totalCount() == 0) RestoreState.Unreadable(decoded.reason) else RestoreState.None
      is SnapshotCodec.Decoded.UnsupportedVersion ->
        _state.value = if (db.books().totalCount() == 0) {
          RestoreState.Unreadable("snapshot schema ${decoded.found} is newer than this app")
        } else {
          RestoreState.None
        }
    }
  }

  /**
   * Copies the snapshot into `noBackupFilesDir` and marks the restore staged, unless a valid staged
   * copy from an earlier attempt is already there. False when staging failed; the snapshot file and
   * any broken stage are left as they are.
   */
  private suspend fun stage(decoded: SnapshotCodec.Decoded.Ok): Boolean {
    val stagedValid = pendingFile.isFile && runCatching { pendingFile.readText() }.map { SnapshotCodec.decode(it) }
      .getOrNull() is SnapshotCodec.Decoded.Ok
    if (stagedValid) return true
    return runCatching {
      check(pendingDirectory.isDirectory || pendingDirectory.mkdirs()) { "could not create $pendingDirectory" }
      val tmp = File(pendingDirectory, "$PENDING_FILE_NAME.tmp")
      tmp.outputStream().use { out ->
        out.write(SnapshotCodec.encode(decoded.snapshot).toByteArray(Charsets.UTF_8))
        out.flush()
        out.fd.sync()
      }
      check(tmp.renameTo(pendingFile)) { "rename to $pendingFile failed" }
      phaseFile.writeText(PHASE_STAGED)
      true
    }.onFailure { Log.w(TAG, "staging the restored snapshot failed", it) }
      .getOrElse { false }
  }

  /** Drops the staged copy and marker; the import they guarded has finished (or was never needed). */
  internal fun clearPending() {
    runCatching {
      pendingFile.delete()
      phaseFile.delete()
    }.onFailure { Log.w(TAG, "clearing the staged restore failed", it) }
  }

  /**
   * Called after every completed scan. When a restore is staged, its copy is imported into whatever
   * the scan found: matched books get their data, and entries nothing matched become tombstones.
   * False-safe on every path: without a staged copy, or with an unreadable one, nothing happens.
   */
  suspend fun onScanCompleted(): ImportResult? = mutex.withLock {
    if (_state.value !is RestoreState.Pending) return@withLock null
    val staged = runCatching { pendingFile.readText() }.map { SnapshotCodec.decode(it) }.getOrNull()
    val decoded = staged as? SnapshotCodec.Decoded.Ok
      // The staged copy is a safety duplicate; the snapshot file itself is still there and the
      // writer has been standing down, so it is just as trustworthy when the copy went missing.
      ?: runCatching { snapshotLocation.readText() }.map { SnapshotCodec.decode(it) }.getOrNull()
    val snapshot = decoded?.takeIf { it is SnapshotCodec.Decoded.Ok }?.let { (it as SnapshotCodec.Decoded.Ok).snapshot } ?: run {
      Log.w(TAG, "the staged restore and the snapshot file are both unreadable; standing the restore down")
      _state.value = RestoreState.None
      clearPending()
      return@withLock null
    }
    val result = importer.import(snapshot, applySettings = false)
    Log.i(TAG, "restored user data: ${result.matched} matched, ${result.tombstoned} kept as missing books")
    // The phase marker goes down before the copy: a crash in between resolves to "imported" next start.
    markImported()
    _state.value = RestoreState.None
    clearPending()
    result
  }

  /** Marks the staged restore imported, so a crash before cleanup resolves to "done" on the next start. */
  internal fun markImported() {
    runCatching { phaseFile.writeText(PHASE_IMPORTED) }.onFailure { Log.w(TAG, "marking the restore imported failed", it) }
  }

  companion object {
    private const val TAG = "RestoreCoordinator"
    /** The staged copy and its phase marker, in `noBackupFilesDir`; a full restore clears them (see [StagedRestore]). */
    const val PENDING_FILE_NAME = "pending-restore.json"
    const val PHASE_FILE_NAME = "restore-phase"
    private const val PHASE_STAGED = "staged"
    private const val PHASE_IMPORTED = "imported"
  }
}

private fun File.readTextOrNull(): String? = runCatching { takeIf { it.isFile }?.readText() }.getOrNull()
