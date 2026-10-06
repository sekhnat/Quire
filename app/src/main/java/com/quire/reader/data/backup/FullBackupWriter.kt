package com.quire.reader.data.backup

import androidx.room.useWriterConnection
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.QuireDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A backup that could not be written, with a message for the user. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Writes a full backup: one zip archive holding the library database, the settings, the portable snapshot and, when asked,
 * the search index, cover thumbnails and imported books (see [BackupPaths]).
 *
 * Entries go in the order a restore wants them: the manifest first, so a backup can be inspected by reading one entry;
 * then the small user data and imported books, so a merge can stop before the databases; the databases last. Each
 * database is first copied with `VACUUM INTO`, which gives a consistent, compact single file while the app keeps
 * running, into [BackupLocations.scratch], so its size is known before the manifest is written. The library and the index
 * are copied a moment apart; that is harmless, as the indexer forgets index rows of books that are gone and indexes books
 * it has no rows for.
 */
class FullBackupWriter(
  private val db: QuireDatabase,
  private val indexDb: IndexDatabase,
  private val snapshots: SnapshotWriter,
  private val locations: BackupLocations,
  private val appVersionName: String,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val mutex = Mutex()

  /**
   * Writes the archive to [out] and closes it; reports progress from 0 to 1. Throws [BackupException] with a message for
   * the user when the backup cannot be made; the stream may then hold a partial archive, which the caller deletes.
   */
  suspend fun write(out: OutputStream, contents: BackupContents, progress: (Float) -> Unit = {}): FullBackupManifest = mutex.withLock {
    withContext(Dispatchers.IO) {
      val scratch = locations.scratch
      scratch.deleteRecursively()
      if (!scratch.mkdirs()) throw BackupException("Couldn't prepare the backup")
      try {
        writeLocked(out, contents, scratch, progress)
      } finally {
        scratch.deleteRecursively()
      }
    }
  }

  private suspend fun writeLocked(out: OutputStream, contents: BackupContents, scratch: File, progress: (Float) -> Unit): FullBackupManifest {
    val createdAt = clock()
    val snapshot = snapshots.capture()
    val snapshotBytes = SnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8)

    val library = File(scratch, "quire.db")
    ensureSpace(scratch, sizeOf(locations.libraryDb), "the library")
    db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf<Any?>(library.path))

    val index = if (contents.index && locations.indexDb.isFile) {
      File(scratch, "quire-index.db").also { copy ->
        ensureSpace(scratch, sizeOf(locations.indexDb), "the search index", hint = "; turn off the search index or free up space")
        indexDb.useWriterConnection { c -> c.usePrepared("VACUUM INTO ?") { st -> st.bindText(1, copy.path); st.step() } }
      }
    } else null
    val indexedBooks = if (index != null) indexDb.states().searchable().size else 0

    val settings = locations.settings.takeIf { it.isFile }?.readBytes()
    val covers = if (contents.covers) filesIn(locations.covers) else emptyList()
    val imported = if (contents.imported) filesIn(locations.imported) else emptyList()

    val entries = buildList {
      add(BackupEntry(BackupPaths.SNAPSHOT, snapshotBytes.size.toLong()))
      settings?.let { add(BackupEntry(BackupPaths.SETTINGS, it.size.toLong())) }
      imported.forEach { add(BackupEntry(BackupPaths.IMPORTED + it.name, it.length())) }
      covers.forEach { add(BackupEntry(BackupPaths.COVERS + it.name, it.length())) }
      add(BackupEntry(BackupPaths.LIBRARY_DB, library.length()))
      index?.let { add(BackupEntry(BackupPaths.INDEX_DB, it.length())) }
    }
    val manifest = FullBackupManifest(
      formatVersion = FULL_BACKUP_FORMAT_VERSION,
      createdAt = createdAt,
      appVersionName = appVersionName,
      libraryDbVersion = QuireDatabase.VERSION,
      indexDbVersion = if (index != null) IndexDatabase.VERSION else null,
      contents = contents.copy(index = index != null),
      counts = BackupCounts(
        books = db.books().totalCount(),
        highlights = snapshot.books.sumOf { it.highlights.size },
        bookmarks = snapshot.books.sumOf { it.bookmarks.size },
        indexedBooks = indexedBooks,
        covers = covers.size,
        importedBooks = imported.size,
      ),
      entries = entries,
    )

    val total = manifest.totalBytes.coerceAtLeast(1)
    var written = 0L
    val counted: (Int) -> Unit = { n -> written += n; progress((written.toDouble() / total).toFloat().coerceIn(0f, 1f)) }
    ZipOutputStream(BufferedOutputStream(out, BUFFER)).use { zip ->
      zip.put(BackupPaths.MANIFEST, Deflater.DEFAULT_COMPRESSION, FullBackupManifest.encode(manifest).toByteArray(Charsets.UTF_8).inputStream()) {}
      zip.put(BackupPaths.SNAPSHOT, Deflater.DEFAULT_COMPRESSION, snapshotBytes.inputStream(), counted)
      settings?.let { zip.put(BackupPaths.SETTINGS, Deflater.DEFAULT_COMPRESSION, it.inputStream(), counted) }
      // EPUBs and WebP covers are compressed already; deflating them again costs time and saves nothing.
      imported.forEach { file -> file.inputStream().use { zip.put(BackupPaths.IMPORTED + file.name, Deflater.NO_COMPRESSION, it, counted) } }
      covers.forEach { file -> file.inputStream().use { zip.put(BackupPaths.COVERS + file.name, Deflater.NO_COMPRESSION, it, counted) } }
      library.inputStream().use { zip.put(BackupPaths.LIBRARY_DB, Deflater.DEFAULT_COMPRESSION, it, counted) }
      // The index can be gigabytes: the fastest level keeps most of the saving at a fraction of the time.
      index?.inputStream()?.use { zip.put(BackupPaths.INDEX_DB, Deflater.BEST_SPEED, it, counted) }
    }
    progress(1f)
    return manifest
  }

  private suspend fun ZipOutputStream.put(path: String, level: Int, source: InputStream, counted: (Int) -> Unit) {
    setLevel(level)
    putNextEntry(ZipEntry(path))
    val buffer = ByteArray(BUFFER)
    var sinceCheck = 0
    while (true) {
      val n = source.read(buffer)
      if (n < 0) break
      write(buffer, 0, n)
      counted(n)
      sinceCheck += n
      if (sinceCheck >= CANCEL_CHECK_BYTES) { currentCoroutineContext().ensureActive(); sinceCheck = 0 }
    }
    closeEntry()
  }

  private fun filesIn(dir: File): List<File> =
    dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") && BackupPaths.isPlainName(it.name) }
      ?.sortedBy { it.name }.orEmpty()

  private fun sizeOf(db: File): Long = listOf("", "-wal").sumOf { File(db.path + it).length() }

  private fun ensureSpace(dir: File, needed: Long, what: String, hint: String = "") {
    val free = dir.usableSpace
    if (free < needed + SPACE_MARGIN) {
      throw BackupException("Not enough free space to back up $what: it needs ${humanBytes(needed + SPACE_MARGIN - free)} more$hint")
    }
  }

  companion object {
    private const val BUFFER = 1 shl 16
    private const val CANCEL_CHECK_BYTES = 8 shl 20
    private const val SPACE_MARGIN = 64L shl 20
  }
}

/** A byte count for messages: "1.6 GB", "320 MB", "12 KB". */
internal fun humanBytes(bytes: Long): String = when {
  bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
  bytes >= 1L shl 20 -> "${bytes shr 20} MB"
  else -> "${(bytes shr 10).coerceAtLeast(1)} KB"
}
