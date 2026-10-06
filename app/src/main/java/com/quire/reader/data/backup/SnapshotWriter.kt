package com.quire.reader.data.backup

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.MAX_SQL_ARGS
import com.quire.reader.data.db.SnapshotBookRow
import com.quire.reader.data.db.QuireDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** The snapshot lives here, inside the app's backed-up file area. */
const val BACKUP_DIR_NAME = "backup"
const val SNAPSHOT_FILE_NAME = "user-data.json"

fun backupDirectory(context: Context): File = File(context.applicationContext.filesDir, BACKUP_DIR_NAME)

fun snapshotFile(context: Context): File = File(backupDirectory(context), SNAPSHOT_FILE_NAME)

/** The chapter title a locator carries, when the reader stamped one in; null otherwise. */
fun locatorTitle(locatorJson: String): String? = runCatching {
  val obj = Json.parseToJsonElement(locatorJson) as? JsonObject ?: return@runCatching null
  (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
}.getOrNull()

/**
 * The chapter label a locator carries, best effort: its display title when the reader stamped one in,
 * else the resource it points at, else null (nothing is known). Used for snapshots and note exports.
 */
fun chapterLabel(locatorJson: String): String? = runCatching {
  val obj = Json.parseToJsonElement(locatorJson) as? JsonObject ?: return@runCatching null
  locatorTitle(locatorJson) ?: (obj["href"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?.substringAfterLast('/')?.substringBefore('#')?.ifEmpty { null }
}.getOrNull()

/**
 * Keeps `files/backup/user-data.json` in step with what the user does. User-data changes (reading
 * state, annotations, user tags, book identity or display names) and setting changes schedule a
 * write; the write happens once 30 seconds have passed quietly, so a burst of edits costs one small
 * file. Index invalidations are deliberately not watched: the rebuildable index never travels.
 *
 * Writes are atomic — a temp file is flushed to disk and renamed over the old one — and a failure
 * keeps the previous file. While a restore is pending the writer stands down: the pending copy is
 * the only copy of data this install may not have yet, and it must not be overwritten.
 */
class SnapshotWriter(
  private val db: QuireDatabase,
  private val settings: SettingsStore,
  private val directory: File,
  private val scope: CoroutineScope,
  /** True while [RestoreCoordinator] holds a staged restore; writes wait until it is done. */
  private val isRestorePending: () -> Boolean = { false },
  private val clock: () -> Long = System::currentTimeMillis,
  private val debounceMillis: Long = 30_000,
) {
  private val mutex = Mutex()
  private val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private var started = false

  /** Starts watching for changes; calling it again does nothing. The first write lands one debounce after start. */
  @OptIn(FlowPreview::class)
  fun start() {
    if (started) return
    started = true
    combine(
      db.invalidationTracker.createFlow("book", "book_state", "bookmark", "highlight", "book_tag"),
      settings.changes,
      retries.onStart { emit(Unit) },
    ) { _, _, _ -> }
      .debounce(debounceMillis)
      .onEach { if (!write()) retries.tryEmit(Unit) }
      .launchIn(scope)
  }

  /** Writes immediately, whatever the debounce is doing; the app's stop hook calls this. */
  suspend fun flush(): Boolean = write()

  /** Captures fresh user data. Exports use this, never the possibly stale file on disk. */
  suspend fun capture(): UserDataSnapshot = mutex.withLock { captureLocked() }

  /** [capture] as JSON. */
  suspend fun encoded(): String = SnapshotCodec.encode(capture())

  /** False — with nothing written — while a restore is pending or the file could not be written. */
  private suspend fun write(): Boolean = mutex.withLock {
    if (isRestorePending()) {
      Log.i(TAG, "snapshot write held back while a restore is pending")
      return@withLock false
    }
    val snapshot = captureLocked()
    val tmp = File(directory, "$SNAPSHOT_FILE_NAME.tmp")
    val target = File(directory, SNAPSHOT_FILE_NAME)
    try {
      directory.mkdirs()
      tmp.outputStream().use { out ->
        out.write(SnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
        out.flush()
        out.fd.sync()
      }
      if (!tmp.renameTo(target)) error("rename to $target failed")
      true
    } catch (e: Exception) {
      tmp.delete()
      Log.w(TAG, "snapshot write failed; the previous file is kept", e)
      false
    }
  }

  private suspend fun captureLocked(): UserDataSnapshot {
    var attempt = captureOnce()
    var tries = 1
    while (attempt.settingsChangedDuringCapture && tries < CAPTURE_ATTEMPTS) {
      attempt = captureOnce()
      tries++
    }
    return UserDataSnapshot(SNAPSHOT_SCHEMA_VERSION, clock(), attempt.settings, attempt.books)
  }

  /** One capture: a consistent Room read plus the settings around it, retried if settings moved. */
  private suspend fun captureOnce(): Captured {
    val settingsBefore = settings.capture()
    val books = withContext(Dispatchers.IO) { db.withTransaction { readBooks() } }
    val settingsAfter = settings.capture()
    return Captured(books, settingsAfter, settingsAfter != settingsBefore)
  }

  private suspend fun readBooks(): List<SnapshotBook> {
    val dao = db.snapshots()
    val rows = dao.snapshotRows()
    val ids = rows.map { it.id }
    val tags = ids.chunked(MAX_SQL_ARGS).flatMap { dao.userTags(it) }.groupBy({ it.bookId }, { it.tag })
    val bookmarks = ids.chunked(MAX_SQL_ARGS).flatMap { dao.bookmarks(it) }.groupBy { it.bookId }
    val highlights = ids.chunked(MAX_SQL_ARGS).flatMap { dao.highlights(it) }.groupBy { it.bookId }
    return rows.map { row ->
      val identity = snapshotIdentity(row.calibreUuid, row.epubUid, row.fingerprint)
      val entryKey = tombstoneEntryKey(row.path) ?: entryKeyFor(row.id, row.path)
      SnapshotBook(
        identityKey = identityKeyOf(identity, entryKey),
        entryKey = entryKey,
        identity = identity,
        title = row.title,
        author = row.author,
        addedAt = row.addedAt,
        missingSince = row.missingSince,
        state = row.stateOrNull(),
        userTags = tags[row.id].orEmpty(),
        bookmarks = bookmarks[row.id].orEmpty().map { SnapshotBookmark(it.locatorJson, it.label, it.progress, it.createdAt) },
        highlights = highlights[row.id].orEmpty().map { highlight ->
          val variants = NoteVariants.split(highlight.note)
          SnapshotHighlight(
            locatorJson = highlight.locatorJson,
            text = highlight.text,
            note = NoteVariants.join(variants),
            noteVariants = variants.takeIf { it.size > 1 },
            progress = highlight.progress,
            createdAt = highlight.createdAt,
            chapter = chapterLabel(highlight.locatorJson),
          )
        },
      )
    }
  }

  private class Captured(
    val books: List<SnapshotBook>,
    val settings: SnapshotSettings,
    val settingsChangedDuringCapture: Boolean,
  )

  private companion object {
    const val TAG = "SnapshotWriter"
    const val CAPTURE_ATTEMPTS = 4
  }
}

/**
 * A tombstone's synthetic path embeds the entry key of the snapshot entry that created it (see
 * [tombstonePath]), so a restored tombstone keeps answering to the same key however many times the
 * data moves between devices. Null for every other book.
 */
fun tombstoneEntryKey(path: String): String? =
  path.takeIf { it.startsWith("$TOMBSTONE_FOLDER_PATH/") }
    ?.substringAfterLast('/')?.removeSuffix(".epub")?.ifEmpty { null }

/** The state columns of a book with no [com.quire.reader.data.db.BookStateEntity] row at all. */
private fun SnapshotBookRow.stateOrNull(): SnapshotBookState? =
  if (locatorJson == null && lastOpenedAt == null) null
  else SnapshotBookState(locatorJson, progress, status, lastOpenedAt, finishedAt, userRating, prefsJson)
