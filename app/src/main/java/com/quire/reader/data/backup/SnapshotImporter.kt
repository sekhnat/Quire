package com.quire.reader.data.backup

import androidx.room.withTransaction
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.BookEntity
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.db.IdentityRow
import com.quire.reader.data.db.BookTagEntity
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.scan.LibraryScanner

/** What one import changed. [changed] is true when anything at all was written. */
data class ImportResult(
  val matched: Int,
  val tombstoned: Int,
  val statesWritten: Int,
  val bookmarksAdded: Int,
  val highlightsAdded: Int,
  val tagsAdded: Int,
) {
  val changed: Boolean get() = matched + tombstoned + statesWritten + bookmarksAdded + highlightsAdded + tagsAdded > 0
}

/**
 * Writes a snapshot's user data into the library, in one transaction, on top of what the scan found.
 *
 * Matching is conservative (see [matchEntries]): an entry joins the book its identity points at, and
 * an entry nothing matches becomes a synthetic missing book — a tombstone that holds the user data
 * until its file turns up again, at which point the scanner's normal adoption reattaches it. Merging
 * into an existing library keeps the local ratings, per-book settings and app settings, fills absent
 * values, unions user tags and annotations, and takes a position only when it was opened more
 * recently. Running the same import twice changes nothing (see [SnapshotMerge]).
 */
class SnapshotImporter(
  private val db: QuireDatabase,
  private val settings: SettingsStore,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  /** Imports [snapshot]; [applySettings] is true only for a clean restore, never a manual import. */
  suspend fun import(snapshot: UserDataSnapshot, applySettings: Boolean): ImportResult {
    if (applySettings) settings.applySettings(snapshot.settings)
    if (snapshot.books.isEmpty()) return ImportResult(0, 0, 0, 0, 0, 0)
    return db.withTransaction { importInTransaction(snapshot) }
  }

  private suspend fun importInTransaction(snapshot: UserDataSnapshot): ImportResult {
    val entries = snapshot.books
    val targets = arrayOfNulls<Long>(entries.size)
    var matched = 0

    // Live books first: an entry merges into the book its file is now at.
    val liveMatches = matchEntries(entries, db.books().liveIdentities().map { it.candidate() })
    liveMatches.forEachIndexed { index, match ->
      if (match is EntryMatch.Matched) {
        targets[index] = match.bookId
        matched++
      }
    }

    // Entries no live book claimed may belong to books already missing — including tombstones an
    // earlier import of this same snapshot created, which keeps repeated imports on one row.
    val unmatched = entries.indices.filter { targets[it] == null }
    val missingMatches = matchEntries(unmatched.map { entries[it] }, db.books().missingIdentities().map { it.candidate() })
    var tombstoned = 0
    unmatched.forEachIndexed { position, entryIndex ->
      val match = missingMatches[position]
      targets[entryIndex] = when (match) {
        is EntryMatch.Matched -> {
          matched++
          match.bookId
        }
        EntryMatch.Tombstone -> {
          tombstoned++
          tombstoneTarget(entries[entryIndex])
        }
      }
    }

    var states = 0
    var bookmarks = 0
    var highlights = 0
    var tags = 0
    entries.forEachIndexed { index, entry ->
      val bookId = targets[index] ?: return@forEachIndexed
      val state = entry.state?.let { SnapshotMerge.mergeState(bookId, db.states().get(bookId), it) }
      if (state != null && state != db.states().get(bookId)) {
        db.states().put(state)
        states++
      }
      tags += mergeTags(bookId, entry.userTags)
      bookmarks += mergeBookmarks(bookId, entry.bookmarks)
      highlights += mergeHighlights(bookId, entry.highlights)
    }
    return ImportResult(matched, tombstoned, states, bookmarks, highlights, tags)
  }

  /** The book id that holds this unmatched entry: its earlier tombstone, or a newly created one. */
  private suspend fun tombstoneTarget(entry: SnapshotBook): Long {
    val path = tombstonePath(entry.entryKey)
    db.books().byPath(path)?.let { return it.id }
    val folder = db.folders().byPath(TOMBSTONE_FOLDER_PATH)
      ?: run {
        db.folders().insert(FolderEntity(path = TOMBSTONE_FOLDER_PATH, watched = false))
        db.folders().byPath(TOMBSTONE_FOLDER_PATH)!!
      }
    val now = clock()
    val book = BookEntity(
      path = path,
      folderId = folder.id,
      sizeBytes = 0,
      mtime = 0,
      title = entry.title,
      sortTitle = LibraryScanner.sortKey(entry.title),
      author = entry.author,
      primaryAuthor = entry.author,
      authorSort = LibraryScanner.authorSortFrom(entry.author).lowercase(),
      calibreRating = 0,
      addedAt = entry.addedAt ?: now,
      source = BookEntity.SOURCE_FILE,
      readable = false,
      calibreUuid = entry.identity.calibreUuid,
      epubUid = entry.identity.epubUid,
      fingerprint = entry.identity.fingerprint,
      missingSince = entry.missingSince ?: now,
    )
    return db.books().upsert(book)
  }

  private suspend fun mergeTags(bookId: Long, incoming: List<String>): Int {
    val present = db.books().userTags(bookId).toSet()
    val additions = SnapshotMerge.unionTags(present.toList(), incoming).filter { it !in present }
    if (additions.isNotEmpty()) {
      db.books().insertTags(additions.map { BookTagEntity(bookId, it, BookTagEntity.ORIGIN_USER) })
    }
    return additions.size
  }

  private suspend fun mergeBookmarks(bookId: Long, incoming: List<SnapshotBookmark>): Int {
    val present = db.annotations().bookmarksOf(bookId)
    val presentKeys = present.mapTo(HashSet()) { key(it.locatorJson) }
    var added = 0
    for (bookmark in incoming) {
      if (key(bookmark.locatorJson) in presentKeys) continue
      db.annotations().addBookmark(
        BookmarkEntity(
          bookId = bookId, locatorJson = bookmark.locatorJson, label = bookmark.label,
          progress = bookmark.progress, createdAt = bookmark.createdAt,
        ),
      )
      presentKeys += key(bookmark.locatorJson)
      added++
    }
    return added
  }

  private suspend fun mergeHighlights(bookId: Long, incoming: List<SnapshotHighlight>): Int {
    val present = db.annotations().highlightsOf(bookId)
    val presentKeys = present.associateBy { key(it.locatorJson) to it.text }
    var added = 0
    var merged = 0
    for (highlight in incoming) {
      val existing = presentKeys[key(highlight.locatorJson) to highlight.text]
      if (existing == null) {
        val variants = highlight.noteVariants ?: highlight.note?.let(NoteVariants::split).orEmpty()
        db.annotations().addHighlight(
          HighlightEntity(
            bookId = bookId, locatorJson = highlight.locatorJson, text = highlight.text,
            note = NoteVariants.join(variants), progress = highlight.progress, createdAt = highlight.createdAt,
          ),
        )
        added++
        continue
      }
      val note = SnapshotMerge.unionNote(existing.note, highlight.note, highlight.noteVariants)
      if (note != existing.note) {
        db.annotations().setHighlightNote(existing.id, note)
        merged++
      }
    }
    return added + merged
  }

  /** A locator's identity: canonically keyed JSON when it parses, the raw text when it does not. */
  private fun key(locatorJson: String): String = SnapshotCodec.canonicalLocatorJson(locatorJson) ?: locatorJson

  private fun IdentityRow.candidate() = MatchCandidate(id, title, calibreUuid, epubUid, fingerprint)
}
