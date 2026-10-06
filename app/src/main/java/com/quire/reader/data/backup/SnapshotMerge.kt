package com.quire.reader.data.backup

import com.quire.reader.data.db.BookStateEntity

/**
 * Everything merging two sets of user data decides, with no database or file access, so the rules are
 * unit-testable on their own. The importer (Room) and the writer (files) apply these results.
 */
object SnapshotMerge {
  /**
   * Merges the incoming reading state into the local one.
   *
   * A book with no local state takes the incoming state in full (a clean restore); otherwise the
   * incoming position — locator, progress, status and finished date, which move together — wins only
   * when it was strictly more recently opened, and an incoming state without a timestamp never
   * displaces a local one. Ratings and per-book reader settings are local-first: the incoming value
   * fills the field only when the local row has none. Returns null when there is nothing to write.
   */
  fun mergeState(bookId: Long, local: BookStateEntity?, incoming: SnapshotBookState): BookStateEntity? {
    if (local == null) {
      if (incoming.isEmpty) return null
      return BookStateEntity(
        bookId = bookId,
        locatorJson = incoming.locatorJson,
        progress = incoming.progress ?: 0f,
        status = incoming.status ?: BookStateEntity.STATUS_UNREAD,
        lastOpenedAt = incoming.lastOpenedAt ?: 0,
        finishedAt = incoming.finishedAt ?: 0,
        userRating = incoming.userRating,
        prefsJson = incoming.prefsJson,
      )
    }
    val incomingOpenedAt = incoming.lastOpenedAt ?: 0
    val adopt = incomingOpenedAt > local.lastOpenedAt
    return local.copy(
      locatorJson = if (adopt) incoming.locatorJson ?: local.locatorJson else local.locatorJson,
      progress = if (adopt) incoming.progress ?: local.progress else local.progress,
      status = if (adopt) incoming.status ?: local.status else local.status,
      finishedAt = if (adopt) incoming.finishedAt ?: local.finishedAt else local.finishedAt,
      lastOpenedAt = maxOf(local.lastOpenedAt, incomingOpenedAt),
      userRating = local.userRating ?: incoming.userRating,
      prefsJson = local.prefsJson ?: incoming.prefsJson,
    )
  }

  /**
   * The note a highlight keeps after merging: the distinct notes seen so far, local first, joined
   * readably. Exact equality deduplicates; a shorter note that is a substring of a longer one is a
   * distinct variant. An incoming note that is itself a joined variant list is merged part by part,
   * so importing an exported file again never nests separators.
   */
  fun unionNote(local: String?, incoming: String?, incomingVariants: List<String>? = null): String? {
    val variants = LinkedHashSet<String>()
    variants.addAll(NoteVariants.split(local))
    variants.addAll(incomingVariants?.takeIf { it.isNotEmpty() } ?: NoteVariants.split(incoming))
    return NoteVariants.join(variants.toList())
  }

  /** User tags of both sides, deduplicated and trimmed, local order first. */
  fun unionTags(local: List<String>, incoming: List<String>): List<String> =
    buildList {
      local.forEach { add(it.trim()) }
      incoming.forEach { add(it.trim()) }
    }.filter { it.isNotEmpty() }.distinct()

  /**
   * The settings after an import. `applyIncoming` is true only for a clean restore (an empty
   * library): an existing library keeps its settings, and absent incoming fields always keep the
   * local value, whatever the direction of the merge.
   */
  fun mergeSettings(local: SnapshotSettings, incoming: SnapshotSettings, applyIncoming: Boolean): SnapshotSettings {
    if (!applyIncoming) return local
    return SnapshotSettings(
      useCalibre = incoming.useCalibre ?: local.useCalibre,
      watchNewBooks = incoming.watchNewBooks ?: local.watchNewBooks,
      indexingEnabled = incoming.indexingEnabled ?: local.indexingEnabled,
      indexChargingOnly = incoming.indexChargingOnly ?: local.indexChargingOnly,
      brightness = incoming.brightness ?: local.brightness,
      readerDefaults = incoming.readerDefaults ?: local.readerDefaults,
      advancedReadingEnabled = incoming.advancedReadingEnabled ?: local.advancedReadingEnabled,
    )
  }
}

/** True when a state carries nothing at all, so there is no row to write. */
private val SnapshotBookState.isEmpty: Boolean
  get() = locatorJson == null && progress == null && status == null && lastOpenedAt == null &&
    finishedAt == null && userRating == null && prefsJson == null

/** Joins and separates the note variants stored on one highlight. */
object NoteVariants {
  /** Separates joined variants in a stored note. Three black diamonds never occur in prose. */
  const val SEPARATOR = "\n\n◆◆◆\n\n"

  /** The distinct non-blank variants a stored note holds, in order. */
  fun split(note: String?): List<String> =
    note?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

  /** The readable note for [variants]: joined when there are several, null when there are none. */
  fun join(variants: List<String>): String? =
    variants.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(SEPARATOR).ifEmpty { null }
}

/** A live book an entry may merge into, reduced to what matching may look at. */
data class MatchCandidate(
  val bookId: Long,
  val title: String,
  val calibreUuid: String?,
  val epubUid: String?,
  val fingerprint: String?,
)

/** What an import does with one snapshot entry: merge it into a library book, or keep it as a tombstone. */
sealed interface EntryMatch {
  data class Matched(val bookId: Long) : EntryMatch
  /** The entry becomes (or already is) a missing book holding the user data until its file turns up. */
  data object Tombstone : EntryMatch
}

/**
 * Pairs snapshot entries with library books, with the same conservative tier semantics as
 * `matchMoves`: the Calibre uuid first, then the file fingerprint, then the EPUB's unique identifier
 * — which only counts when the entry's uid is unique among the entries, exactly one candidate carries
 * it and the titles agree. A key that matches several candidates is ambiguous and is not guessed;
 * neither is a title, which alone never matches. Entries may share a candidate: duplicate copies of
 * one book union their annotations into it.
 */
fun matchEntries(entries: List<SnapshotBook>, candidates: List<MatchCandidate>): List<EntryMatch> {
  val byUuid = candidates.filter { it.calibreUuid != null }.groupBy { it.calibreUuid!! }
  val byFingerprint = candidates.filter { it.fingerprint != null }.groupBy { it.fingerprint!! }
  val byUid = candidates.filter { it.epubUid != null }.groupBy { it.epubUid!! }
  val entryUidCounts = entries.mapNotNull { it.identity.epubUid }.groupingBy { it }.eachCount()
  return entries.map { entry ->
    val identity = entry.identity
    val tiers = listOfNotNull(
      identity.calibreUuid?.let(byUuid::get),
      identity.fingerprint?.let(byFingerprint::get),
      identity.epubUid
        ?.takeIf { entryUidCounts[it] == 1 }
        ?.let(byUid::get)
        ?.filter { it.title.trim().equals(entry.title.trim(), ignoreCase = true) },
    )
    tiers.firstNotNullOfOrNull { group -> group.singleOrNull()?.bookId }
      ?.let(EntryMatch::Matched) ?: EntryMatch.Tombstone
  }
}

/**
 * Where a snapshot's synthetic folder and its tombstone books live: a path on no filesystem, inside a
 * folder that is never watched, so the scanner never sees it. [entryKey] in the path makes repeated
 * imports of the same entry land on the same row instead of piling up duplicates.
 */
const val TOMBSTONE_FOLDER_PATH = "/Quire missing books"

fun tombstonePath(entryKey: String): String = "$TOMBSTONE_FOLDER_PATH/$entryKey.epub"
