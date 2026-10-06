package com.quire.reader.data.backup

import com.quire.reader.data.ReaderPrefs
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** The snapshot schema this build writes; older readers reject newer files (see [SnapshotCodec]). */
const val SNAPSHOT_SCHEMA_VERSION = 1

/**
 * Everything the user authored, in one portable JSON document: reading state, annotations, user tags
 * and settings. Rebuildable data (the text index, cover files, Calibre's own tags) is deliberately
 * absent — a restore rescans the library and re-derives it.
 */
@Serializable
data class UserDataSnapshot(
  val schemaVersion: Int,
  /** When the snapshot was written, epoch millis; informational only. */
  val exportedAt: Long,
  val settings: SnapshotSettings,
  /** Only books with user-authored data; books the user never touched are not stored. */
  val books: List<SnapshotBook> = emptyList(),
)

/** The portable subset of settings. Setup/maintenance flags (onboarding, backfills) are not settings and never travel. */
@Serializable
data class SnapshotSettings(
  val useCalibre: Boolean? = null,
  val watchNewBooks: Boolean? = null,
  val indexingEnabled: Boolean? = null,
  val indexChargingOnly: Boolean? = null,
  val brightness: Int? = null,
  val readerDefaults: ReaderPrefs? = null,
  /** Search-order preference introduced by search index v2; absent in older snapshots. */
  val textSearchOrder: String? = null,
)

/** A book's identity keys, mirroring what a scan can read again from the file (see `BookIdentity`). */
@Serializable
data class SnapshotIdentity(
  /** Calibre's book uuid from `metadata.opf`; the strongest key. */
  val calibreUuid: String? = null,
  /** The EPUB's own `unique-identifier`; weak, only trusted with a matching title. */
  val epubUid: String? = null,
  /** `size:sha1` of the file's tail; equal for byte-identical copies. */
  val fingerprint: String? = null,
)

/** One book's user data. [identityKey] identifies the book across devices; [entryKey] distinguishes this entry. */
@Serializable
data class SnapshotBook(
  /** Namespaced from the strongest identity available when the snapshot was written; see [identityKeyOf]. */
  val identityKey: String,
  /** Opaque per-entry key derived from the source row, so colliding identities stay separate and repeated imports target the same tombstone. */
  val entryKey: String,
  val identity: SnapshotIdentity,
  val title: String,
  val author: String,
  /** Epoch millis; kept so a restored tombstone reads as a real book. */
  val addedAt: Long? = null,
  /** Non-null when the book was already missing when the snapshot was written. */
  val missingSince: Long? = null,
  val state: SnapshotBookState? = null,
  /** Tags added in Quire; Calibre's own tags are re-read from the files on scan. */
  val userTags: List<String> = emptyList(),
  val bookmarks: List<SnapshotBookmark> = emptyList(),
  val highlights: List<SnapshotHighlight> = emptyList(),
)

/** Reading position and status; every field is optional so partial state survives schema growth. */
@Serializable
data class SnapshotBookState(
  val locatorJson: String? = null,
  /** 0..1 across the whole book. */
  val progress: Float? = null,
  /** `unread`, `reading` or `finished`. */
  val status: String? = null,
  val lastOpenedAt: Long? = null,
  val finishedAt: Long? = null,
  /** Rating set inside Quire, 0–5. */
  val userRating: Int? = null,
  /** Per-book reader settings as the raw JSON the app stores, null = use the defaults. */
  val prefsJson: String? = null,
)

@Serializable
data class SnapshotBookmark(
  val locatorJson: String,
  /** Chapter title and progress for display in the list. */
  val label: String,
  val progress: Float,
  val createdAt: Long,
)

/** One highlight. [noteVariants] carries distinct conflicting notes exactly, [note] their readable joined text. */
@Serializable
data class SnapshotHighlight(
  val locatorJson: String,
  val text: String,
  val note: String? = null,
  /** The distinct notes this highlight has accumulated; null while there is at most one. */
  val noteVariants: List<String>? = null,
  val progress: Float,
  val createdAt: Long,
  /** Chapter label where the highlight was made, best effort; null when it was never known. */
  val chapter: String? = null,
)

/** The identity keys of a book row, as the database holds them. */
fun snapshotIdentity(calibreUuid: String?, epubUid: String?, fingerprint: String?) =
  SnapshotIdentity(calibreUuid = calibreUuid, epubUid = epubUid, fingerprint = fingerprint)

/**
 * Namespaces [identity] from its strongest key, falling back to [entryKey] for books without any
 * readable identity (older files). The namespace prefixes keep unlike keys from colliding.
 */
fun identityKeyOf(identity: SnapshotIdentity, entryKey: String): String = when {
  identity.calibreUuid != null -> "calibre:${identity.calibreUuid}"
  identity.fingerprint != null -> "fingerprint:${identity.fingerprint}"
  identity.epubUid != null -> "epubUid:${identity.epubUid}"
  else -> "entry:$entryKey"
}

/**
 * An opaque key for one snapshot entry, hashed from the source book's row id and path. It has no
 * meaning outside this snapshot: it distinguishes entries whose identity keys collide (byte-identical
 * copies) and lets a repeated import find the tombstone a previous import of the same file created.
 */
fun entryKeyFor(id: Long, path: String): String {
  val digest = MessageDigest.getInstance("SHA-1").digest("$id\u0000$path".toByteArray(Charsets.UTF_8))
  return digest.joinToString("") { "%02x".format(it) }.take(20)
}
