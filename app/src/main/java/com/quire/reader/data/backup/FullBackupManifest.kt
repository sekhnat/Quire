package com.quire.reader.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The full-backup archive format this build writes; archives with a newer format are refused. */
const val FULL_BACKUP_FORMAT_VERSION = 1

/** What a full backup holds besides the library database, the settings and the portable snapshot, which it always has. */
@Serializable
data class BackupContents(val index: Boolean = true, val covers: Boolean = true, val imported: Boolean = true)

/** How often scheduled full backups run. */
enum class BackupInterval(val days: Long) { Daily(1), Weekly(7) }

/** What a backup held when it was written, for the restore sheet; informational only. */
@Serializable
data class BackupCounts(
  val books: Int = 0,
  val highlights: Int = 0,
  val bookmarks: Int = 0,
  val indexedBooks: Int = 0,
  val covers: Int = 0,
  val importedBooks: Int = 0,
)

/** One archive entry after the manifest, with its uncompressed size. */
@Serializable
data class BackupEntry(val path: String, val bytes: Long)

/**
 * The first entry of a full-backup archive. It says what follows and how big it is uncompressed, so a restore can check
 * the version and the free space before extracting anything.
 */
@Serializable
data class FullBackupManifest(
  val formatVersion: Int,
  /** When the backup was written, epoch millis. */
  val createdAt: Long,
  val appVersionName: String,
  /** The library database's schema version; Room migrates older ones on restore, newer ones are refused. */
  val libraryDbVersion: Int,
  /** The index database's schema version, or null when the index is not in the archive. */
  val indexDbVersion: Int? = null,
  val contents: BackupContents,
  val counts: BackupCounts = BackupCounts(),
  val entries: List<BackupEntry> = emptyList(),
) {
  val totalBytes: Long get() = entries.sumOf { it.bytes }

  /** True when the archive's index can be used by a build whose index schema is [currentIndexVersion]. */
  fun indexUsable(currentIndexVersion: Int): Boolean = contents.index && indexDbVersion == currentIndexVersion

  sealed interface Decoded {
    data class Ok(val manifest: FullBackupManifest) : Decoded
    data class Malformed(val reason: String) : Decoded
    /** Written by a newer Quire: a newer archive format or library schema. */
    data class Unsupported(val reason: String) : Decoded
  }

  companion object {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun encode(manifest: FullBackupManifest): String = json.encodeToString(manifest)

    /** Decodes and checks a manifest against this build's [libraryDbVersion]. */
    fun decode(text: String, libraryDbVersion: Int): Decoded {
      val manifest = runCatching { json.decodeFromString<FullBackupManifest>(text) }
        .getOrElse { return Decoded.Malformed(it.message ?: "not valid JSON") }
      if (manifest.formatVersion > FULL_BACKUP_FORMAT_VERSION) return Decoded.Unsupported("backup format ${manifest.formatVersion} is newer than this app")
      if (manifest.formatVersion < 1) return Decoded.Malformed("backup format ${manifest.formatVersion}")
      if (manifest.libraryDbVersion > libraryDbVersion) return Decoded.Unsupported("library schema ${manifest.libraryDbVersion} is newer than this app")
      manifest.entries.firstOrNull { !BackupPaths.isAllowed(it.path) }?.let { return Decoded.Malformed("unexpected entry ${it.path}") }
      manifest.entries.firstOrNull { it.bytes < 0 }?.let { return Decoded.Malformed("negative size for ${it.path}") }
      if (manifest.entries.none { it.path == BackupPaths.LIBRARY_DB }) return Decoded.Malformed("no library database")
      return Decoded.Ok(manifest)
    }
  }
}

/** Entry names inside a full-backup archive. */
object BackupPaths {
  const val MANIFEST = "manifest.json"
  const val SNAPSHOT = "user-data.json"
  const val LIBRARY_DB = "db/quire.db"
  const val INDEX_DB = "db/quire-index.db"
  const val SETTINGS = "settings/settings.preferences_pb"
  const val COVERS = "covers/"
  const val IMPORTED = "imported/"

  private val FIXED = setOf(MANIFEST, SNAPSHOT, LIBRARY_DB, INDEX_DB, SETTINGS)

  /**
   * True for the entries a restore will write: the fixed names, and plain file names directly inside `covers/` or
   * `imported/`. Anything else (another folder, `..`, an absolute path) is refused, so an archive can never write outside
   * the places it is restored to.
   */
  fun isAllowed(path: String): Boolean = path in FIXED || fileIn(path, COVERS) != null || fileIn(path, IMPORTED) != null

  /** The file name of [path] when it is a plain file directly inside [folder], else null. */
  fun fileIn(path: String, folder: String): String? =
    path.takeIf { it.startsWith(folder) }?.removePrefix(folder)?.takeIf(::isPlainName)

  /** A file name with no path in it: no separators, not `.` or `..`. */
  fun isPlainName(name: String): Boolean =
    name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name
}

/** Names of scheduled backup files and which of them to delete when only the newest few are kept. */
object BackupFiles {
  const val MIME = "application/zip"
  private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
  private val NAME = Regex("""Quire backup \d{4}-\d{2}-\d{2} \d{4}( \(\d+\))?\.zip""")

  /** "Quire backup 2026-10-07 0300.zip" for a backup written at [at]. */
  fun nameFor(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "Quire backup ${STAMP.format(Instant.ofEpochMilli(at).atZone(zone))}.zip"

  /** True for files this app names as backups; nothing else in a backup folder is ever deleted. */
  fun isBackupName(name: String): Boolean = NAME.matches(name)

  /** A file in [folder] named for [at] that does not exist yet: a second backup in the same minute gets " (2)". */
  fun freshFile(folder: File, at: Long, zone: ZoneId = ZoneId.systemDefault()): File {
    val base = nameFor(at, zone).removeSuffix(".zip")
    var file = File(folder, "$base.zip")
    var n = 2
    while (file.exists()) file = File(folder, "$base (${n++}).zip")
    return file
  }

  /** The backups in [files] past the newest [keep], newest by modification time then name; other files are never listed. */
  fun toPrune(files: List<File>, keep: Int): List<File> =
    files.filter { it.isFile && isBackupName(it.name) }
      .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
      .drop(keep.coerceAtLeast(1))
}
