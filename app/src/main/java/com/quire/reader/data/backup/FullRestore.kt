package com.quire.reader.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.MAX_SQL_ARGS
import com.quire.reader.data.db.QuireDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** What merging a full backup's reading data did. */
data class MergeOutcome(val result: ImportResult, val booksAdded: Int)

/**
 * Restores full backups written by [FullBackupWriter], in one of two ways.
 *
 * **Replace everything** makes this install an exact copy of the backup: [stage] extracts and validates the archive while
 * the app runs, the app restarts, [applyStagedIfAny] swaps the files in before anything opens them (see [StagedRestore]),
 * and [finishAfterStart] tidies up once the databases and settings are open again.
 *
 * **Merge reading data** ([merge]) keeps the library and adds the backup's reading data to it, through the same importer
 * as a reading-data file; imported books the library lacks are copied in first, so their data finds them.
 */
class FullRestore(
  private val context: Context,
  private val locations: BackupLocations,
  private val db: QuireDatabase,
  private val settings: SettingsStore,
  /** Adds the imported-books folder to the library and scans it (`LibraryRepository.scanImported`). */
  private val scanImported: suspend () -> Unit,
  private val importer: SnapshotImporter,
  private val snapshots: SnapshotWriter,
) {
  private val staged = StagedRestore(locations)

  /** Reads only the manifest of the archive at [uri]. Throws [BackupException] when it is not a usable backup. */
  suspend fun inspect(uri: Uri): FullBackupManifest = withContext(Dispatchers.IO) {
    zip(uri) { zip -> readManifest(zip) }
  }

  /**
   * Extracts the archive at [uri] into the staging folder and checks it: the reading data decodes, the library database
   * opens with this build's migrations, and the index (when it can be used) passes a quick check. On success the restore
   * is marked staged and the app must restart for it to take effect. Throws [BackupException], leaving nothing staged.
   */
  suspend fun stage(uri: Uri, progress: (Float?) -> Unit = {}): RestoreMarker = withContext(Dispatchers.IO) {
    staged.clear()
    try {
      stageLocked(uri, progress)
    } catch (e: Throwable) {
      staged.clear()
      if (e is BackupException || e is CancellationException) throw e
      Log.w(TAG, "staging the restore failed", e)
      throw BackupException("Couldn't restore that backup", e)
    }
  }

  private suspend fun stageLocked(uri: Uri, progress: (Float?) -> Unit): RestoreMarker {
    val (manifest, useIndex) = zip(uri) { zip ->
      val manifest = readManifest(zip)
      val needed = manifest.totalBytes + SPACE_MARGIN
      val free = locations.restoreDir.apply { mkdirs() }.usableSpace
      if (free < needed) throw BackupException("Not enough free space to restore: it needs ${humanBytes(needed - free)} more")
      val useIndex = manifest.indexUsable(IndexDatabase.VERSION)
      if (manifest.contents.covers) staged.stagedCovers.mkdirs()
      val total = manifest.totalBytes.coerceAtLeast(1)
      var done = 0L
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.isDirectory) continue
        val path = entry.name
        val target = when {
          path == BackupPaths.SNAPSHOT -> staged.stagedSnapshot
          path == BackupPaths.SETTINGS -> staged.stagedSettings
          path == BackupPaths.LIBRARY_DB -> staged.stagedLibrary
          path == BackupPaths.INDEX_DB -> if (useIndex) staged.stagedIndex else null
          else -> BackupPaths.fileIn(path, BackupPaths.COVERS)?.let { File(staged.stagedCovers, it) }
            ?: BackupPaths.fileIn(path, BackupPaths.IMPORTED)?.let { File(staged.stagedImported, it) }
        } ?: continue
        target.parentFile?.mkdirs()
        target.outputStream().use { out -> copy(zip, out) { n -> done += n; progress((done.toDouble() / total).toFloat().coerceIn(0f, 1f)) } }
      }
      manifest to useIndex
    }
    progress(null)

    val snapshot = runCatching { staged.stagedSnapshot.readText() }.map(SnapshotCodec::decode).getOrNull()
    if (snapshot !is SnapshotCodec.Decoded.Ok) throw BackupException("The backup's reading data is damaged")
    if (!staged.stagedLibrary.isFile) throw BackupException("The backup has no library")
    validateLibrary(staged.stagedLibrary)
    val indexOk = useIndex && staged.stagedIndex.isFile && validateIndex(staged.stagedIndex)
    if (!indexOk) staged.stagedIndex.delete()

    val marker = RestoreMarker(
      phase = RestoreMarker.STAGED,
      index = indexOk,
      covers = manifest.contents.covers && staged.stagedCovers.isDirectory,
      settings = staged.stagedSettings.isFile,
    )
    staged.writeMarker(marker)
    Log.i(TAG, "staged a full restore from ${manifest.createdAt}: ${manifest.counts}, index=${marker.index}, covers=${marker.covers}")
    return marker
  }

  /** Opens the staged library with this build's migrations and schema checks, then leaves it as a single file. */
  private suspend fun validateLibrary(file: File) {
    val opened = QuireDatabase.create(context, file.absolutePath)
    try {
      opened.books().totalCount()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      throw BackupException("The backup's library can't be opened by this version of Quire", e)
    } finally {
      opened.close()
    }
    // Leave write-ahead logging, so everything is in the main file and the swap moves exactly one file.
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
      sqlite.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() }
    }
    dropEmptySidecars(file)
  }

  /** True when the staged index passes a quick check and has this build's schema; it is then a single file. */
  private fun validateIndex(file: File): Boolean = runCatching {
    val connection = BundledSQLiteDriver().open(file.path)
    try {
      val ok = connection.prepare("PRAGMA quick_check").use { it.step() && it.getText(0) == "ok" }
      val version = connection.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
      connection.execSQL("PRAGMA journal_mode = DELETE")
      ok && version == IndexDatabase.VERSION.toLong()
    } finally {
      connection.close()
    }
  }.onFailure { Log.w(TAG, "the backup's search index failed its check; it will be rebuilt", it) }
    .getOrDefault(false)
    .also { if (it) dropEmptySidecars(file) }

  private fun dropEmptySidecars(file: File) {
    for (suffix in listOf("-wal", "-shm", "-journal")) {
      val sidecar = File(file.path + suffix)
      if (!sidecar.exists()) continue
      if (suffix != "-shm" && sidecar.length() > 0) throw BackupException("The backup's database could not be prepared")
      sidecar.delete()
    }
  }

  /**
   * Adds the reading data of the archive at [uri] to the library, keeping everything the library has. Imported books it
   * lacks are copied in and scanned first. The search index, covers and settings of the backup are not used.
   */
  suspend fun merge(uri: Uri): MergeOutcome = withContext(Dispatchers.IO) {
    var snapshot: UserDataSnapshot? = null
    var added = 0
    zip(uri) { zip ->
      readManifest(zip)
      while (true) {
        val entry = zip.nextEntry ?: break
        val path = entry.name
        when {
          path == BackupPaths.SNAPSHOT -> {
            val decoded = SnapshotCodec.decode(zip.readBytes().decodeToString())
            snapshot = (decoded as? SnapshotCodec.Decoded.Ok)?.snapshot ?: throw BackupException("The backup's reading data is damaged")
          }
          path == BackupPaths.SETTINGS -> Unit
          BackupPaths.fileIn(path, BackupPaths.IMPORTED) != null -> if (copyImported(zip, BackupPaths.fileIn(path, BackupPaths.IMPORTED)!!)) added++
          // Covers and the databases come after everything a merge reads; stop rather than read through them.
          else -> break
        }
      }
    }
    val data = snapshot ?: throw BackupException("The backup has no reading data")
    if (added > 0) scanImported()
    val result = importer.import(data, applySettings = false)
    snapshots.flush()
    MergeOutcome(result, added)
  }

  /** Copies an imported book from the archive unless a file of that name and size is already there; true if copied. */
  private suspend fun copyImported(zip: ZipInputStream, name: String): Boolean {
    val dir = locations.imported.apply { mkdirs() }
    val existing = File(dir, name)
    // Streamed zip entries do not declare their size up front, so the copy is made first and compared.
    val tmp = File(dir, "$name.tmp")
    tmp.outputStream().use { copy(zip, it) {} }
    if (existing.isFile && existing.length() == tmp.length()) { tmp.delete(); return false }
    var target = existing
    var n = 2
    while (target.exists()) target = File(dir, "${existing.nameWithoutExtension} ($n).${existing.extension}").also { n++ }
    if (!tmp.renameTo(target)) { tmp.delete(); throw BackupException("Couldn't copy $name") }
    return true
  }

  /**
   * Finishes a restore swapped in at this start, once the databases and settings can be opened: maintenance flags that
   * describe files the backup did not carry are reset, books whose cover file did not come back are queued to have it
   * read again, and the replaced files are deleted. Runs again on the next start if the process dies first.
   */
  suspend fun finishAfterStart() = withContext(Dispatchers.IO) {
    val marker = staged.marker() ?: return@withContext
    if (marker.phase != RestoreMarker.SWAPPED) {
      // Still staged: the swap at start failed and logged why. Leave it for the next start to try again.
      return@withContext
    }
    // The backup's own flag only describes its own index; without both, merge the index again when it is next due.
    if (!marker.index || !marker.settings) settings.setIndexOptimized(false)
    if (!marker.covers) {
      val gone = db.books().withCovers().filter { it.coverPath != null && !File(it.coverPath).isFile }.map { it.id }
      gone.chunked(MAX_SQL_ARGS).forEach { db.books().clearCovers(it) }
      settings.setCoversBackfilled(false)
    }
    staged.clear()
    Log.i(TAG, "full restore finished (index=${marker.index}, covers=${marker.covers}, settings=${marker.settings})")
  }

  private inline fun <T> zip(uri: Uri, block: (ZipInputStream) -> T): T {
    val input: InputStream = try {
      context.contentResolver.openInputStream(uri)
    } catch (e: Exception) {
      throw BackupException("Couldn't open the file", e)
    } ?: throw BackupException("Couldn't open the file")
    return ZipInputStream(BufferedInputStream(input, BUFFER)).use(block)
  }

  private fun readManifest(zip: ZipInputStream): FullBackupManifest {
    val first = runCatching { zip.nextEntry }.getOrNull()
    if (first?.name != BackupPaths.MANIFEST) throw BackupException("That file isn't a Quire backup")
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    while (true) {
      val n = zip.read(buffer)
      if (n < 0) break
      out.write(buffer, 0, n)
      if (out.size() > MAX_MANIFEST_BYTES) throw BackupException("That file isn't a Quire backup")
    }
    return when (val decoded = FullBackupManifest.decode(out.toByteArray().decodeToString(), QuireDatabase.VERSION)) {
      is FullBackupManifest.Decoded.Ok -> decoded.manifest
      is FullBackupManifest.Decoded.Unsupported -> throw BackupException("That backup was made by a newer version of Quire")
      is FullBackupManifest.Decoded.Malformed -> throw BackupException("That backup is damaged (${decoded.reason})")
    }
  }

  private suspend fun copy(input: InputStream, out: java.io.OutputStream, counted: (Int) -> Unit) {
    val buffer = ByteArray(BUFFER)
    var sinceCheck = 0
    while (true) {
      val n = input.read(buffer)
      if (n < 0) break
      out.write(buffer, 0, n)
      counted(n)
      sinceCheck += n
      if (sinceCheck >= 8 shl 20) { currentCoroutineContext().ensureActive(); sinceCheck = 0 }
    }
  }

  companion object {
    private const val TAG = "FullRestore"
    private const val BUFFER = 1 shl 16
    private const val SPACE_MARGIN = 64L shl 20
    private const val MAX_MANIFEST_BYTES = 4 shl 20

    /**
     * Swaps a staged restore into place. Must run first thing in `Application.onCreate`, before anything opens a database
     * or the settings; it only renames files, so it is quick. A failure is logged and retried on the next start.
     */
    fun applyStagedIfAny(context: Context) {
      runCatching { StagedRestore(BackupLocations.of(context)).swapIfStaged() }
        .onSuccess { if (it?.phase == RestoreMarker.SWAPPED) Log.i(TAG, "swapped in a staged full restore") }
        .onFailure { Log.e(TAG, "swapping in the staged restore failed; retrying on the next start", it) }
    }
  }
}
