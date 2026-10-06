package com.quire.reader.data.backup

import android.content.Context
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.QuireDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Where the app keeps everything a full backup carries, and where backups and restores do their work. */
data class BackupLocations(
  val libraryDb: File,
  val indexDb: File,
  /** DataStore's settings file. */
  val settings: File,
  val covers: File,
  val imported: File,
  /** The portable snapshot [SnapshotWriter] keeps. */
  val snapshot: File,
  /** [RestoreCoordinator]'s staged copy and phase marker; a full restore supersedes them. */
  val pendingRestoreFiles: List<File>,
  /** `noBackupFilesDir/full-restore`: the staged archive, the files it replaced, and the marker. */
  val restoreDir: File,
  /** `cacheDir/full-backup`: database copies while a backup is written. */
  val scratch: File,
) {
  companion object {
    fun of(context: Context): BackupLocations {
      val app = context.applicationContext
      return BackupLocations(
        libraryDb = app.getDatabasePath(QuireDatabase.FILE_NAME),
        indexDb = app.getDatabasePath(IndexDatabase.FILE_NAME),
        settings = File(app.filesDir, "datastore/settings.preferences_pb"),
        covers = File(app.filesDir, "covers"),
        imported = File(app.filesDir, "imported"),
        snapshot = snapshotFile(app),
        pendingRestoreFiles = listOf(RestoreCoordinator.PENDING_FILE_NAME, RestoreCoordinator.PHASE_FILE_NAME).map { File(app.noBackupFilesDir, it) },
        restoreDir = File(app.noBackupFilesDir, "full-restore"),
        scratch = File(app.cacheDir, "full-backup"),
      )
    }
  }
}

/** What a staged full restore replaces; written before the app restarts, read when it starts again. */
@Serializable
data class RestoreMarker(
  val phase: String,
  /** The archive's index is staged and replaces the current one; otherwise the current one is deleted (its book ids are not the restored library's). */
  val index: Boolean,
  /** The archive's covers are staged and replace the current ones; otherwise the current ones stay. */
  val covers: Boolean,
  /** The archive's settings file is staged and replaces the current one. */
  val settings: Boolean,
) {
  companion object {
    /** Extracted and validated, waiting for the next start to swap it in. */
    const val STAGED = "staged"
    /** Swapped in; the follow-up that needs the databases and settings open has not finished yet. */
    const val SWAPPED = "swapped"
  }
}

/**
 * The file side of a full "replace everything" restore, with no Android dependencies so it can be tested on its own.
 *
 * The archive is extracted into [stagedDir] while the app runs. On the next start, before anything opens a database or the
 * settings, [swapIfStaged] moves the current files aside into [previousDir] and renames the staged ones into place. Every
 * file lives on the app's internal storage, so each move is an atomic rename, and every step checks what is already done,
 * so a start that dies half way through simply finishes the swap on the next one. The staged databases carry no
 * write-ahead log (staging checkpoints them), so each database is a single file to move.
 */
class StagedRestore(private val at: BackupLocations) {
  val stagedDir = File(at.restoreDir, "staged")
  val previousDir = File(at.restoreDir, "previous")
  private val markerFile = File(at.restoreDir, "restore.json")

  fun marker(): RestoreMarker? = runCatching { markerFile.takeIf { it.isFile }?.readText()?.let { json.decodeFromString<RestoreMarker>(it) } }.getOrNull()

  fun writeMarker(marker: RestoreMarker) {
    at.restoreDir.mkdirs()
    val tmp = File(at.restoreDir, "restore.json.tmp")
    tmp.writeText(json.encodeToString(marker))
    if (!tmp.renameTo(markerFile)) throw IOException("could not write $markerFile")
  }

  /** Drops everything a restore left behind: the staged files, the replaced ones and the marker. */
  fun clear() {
    stagedDir.deleteRecursively()
    previousDir.deleteRecursively()
    markerFile.delete()
    File(at.restoreDir, "restore.json.tmp").delete()
  }

  /** Staged file locations, as staging writes them. */
  val stagedLibrary get() = File(stagedDir, "quire.db")
  val stagedIndex get() = File(stagedDir, "quire-index.db")
  val stagedSettings get() = File(stagedDir, "settings.preferences_pb")
  val stagedSnapshot get() = File(stagedDir, "user-data.json")
  val stagedCovers get() = File(stagedDir, "covers")
  val stagedImported get() = File(stagedDir, "imported")

  /**
   * Swaps a staged restore into place and marks it [RestoreMarker.SWAPPED]; returns the marker, or null when nothing is
   * staged. Safe to call again after an interruption at any point. Imported books are only ever added: a book imported
   * since the backup was made stays, and is found as a new book by the next scan.
   */
  fun swapIfStaged(): RestoreMarker? {
    val marker = marker() ?: return null
    if (marker.phase != RestoreMarker.STAGED) return marker
    replaceDatabase(stagedLibrary, at.libraryDb)
    if (marker.index) replaceDatabase(stagedIndex, at.indexDb) else retireDatabase(at.indexDb)
    if (marker.settings) replaceFile(stagedSettings, at.settings)
    if (marker.covers) replaceDirectory(stagedCovers, at.covers)
    addImported(stagedImported, at.imported)
    if (stagedSnapshot.isFile) replaceFile(stagedSnapshot, at.snapshot)
    at.pendingRestoreFiles.forEach { it.delete() }
    val swapped = marker.copy(phase = RestoreMarker.SWAPPED)
    writeMarker(swapped)
    stagedDir.deleteRecursively()
    return swapped
  }

  private fun replaceDatabase(staged: File, target: File) {
    if (!staged.isFile) return
    retireDatabase(target)
    move(staged, target)
  }

  /** Moves a database and its journal files aside. */
  private fun retireDatabase(target: File) {
    for (suffix in DB_FILES) {
      val file = File(target.path + suffix)
      if (file.exists()) retire(file)
    }
  }

  private fun replaceFile(staged: File, target: File) {
    if (!staged.isFile) return
    if (target.exists()) retire(target)
    move(staged, target)
  }

  private fun replaceDirectory(staged: File, target: File) {
    if (!staged.isDirectory) return
    if (target.exists()) retire(target)
    move(staged, target)
  }

  private fun addImported(staged: File, target: File) {
    val files = staged.listFiles()?.filter { it.isFile } ?: return
    target.mkdirs()
    for (file in files) {
      val existing = File(target, file.name)
      when {
        !existing.exists() -> move(file, existing)
        existing.length() == file.length() -> file.delete()
        else -> move(file, uniqueSibling(existing))
      }
    }
    staged.deleteRecursively()
  }

  private fun retire(file: File) {
    previousDir.mkdirs()
    val dest = File(previousDir, file.name)
    if (dest.exists()) dest.deleteRecursively()
    move(file, dest)
  }

  private fun move(from: File, to: File) {
    to.parentFile?.mkdirs()
    if (!from.renameTo(to)) throw IOException("could not move $from to $to")
  }

  private fun uniqueSibling(file: File): File {
    val base = file.nameWithoutExtension
    val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
    var n = 1
    var candidate: File
    do candidate = File(file.parentFile, "$base (restored${if (n > 1) " $n" else ""})$ext").also { n++ } while (candidate.exists())
    return candidate
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
    val DB_FILES = listOf("", "-wal", "-shm", "-journal")
  }
}
