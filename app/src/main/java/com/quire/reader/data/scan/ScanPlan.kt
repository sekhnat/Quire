package com.quire.reader.data.scan

import com.quire.reader.data.db.IdentityRow
import com.quire.reader.data.db.KnownFile

/** An EPUB found on disk. */
data class FoundFile(val path: String, val folderId: Long, val sizeBytes: Long, val mtime: Long)

/** What a rescan has to do: read these files, and forget these books. */
data class ScanPlan(val toRead: List<FoundFile>, val removedIds: List<Long>)

/**
 * Decides what a rescan must touch. A file is read when it is new or its size or modified time changed;
 * a known book is removed only when its folder was reachable during the walk and the file is gone — an
 * unmounted SD card or revoked permission must never wipe the library.
 */
fun planScan(found: List<FoundFile>, known: Map<String, KnownFile>, reachableFolderIds: Set<Long>): ScanPlan =
  ScanPlan(found.filter { needsRead(it, known[it.path]) }, removedIds(found.mapTo(HashSet()) { it.path }, known, reachableFolderIds))

/** True when [file] is new, or its size or modified time changed since it was last read as [known]. */
fun needsRead(file: FoundFile, known: KnownFile?): Boolean =
  known == null || known.sizeBytes != file.sizeBytes || known.mtime != file.mtime

/** Books in the library whose folder was walked ([reachableFolderIds]) but whose file was not among [foundPaths]. */
fun removedIds(foundPaths: Set<String>, known: Map<String, KnownFile>, reachableFolderIds: Set<Long>): List<Long> =
  known.values.filter { !it.missing && it.folderId in reachableFolderIds && it.path !in foundPaths }.map { it.id }

/** The missing book [missingId] is the file that the library now holds as [liveId]. */
data class Move(val missingId: Long, val liveId: Long)

/**
 * Pairs missing books with live books that hold the same book under another path, so the missing book's history can take
 * over the live book's file. Only live books without history of their own can be taken over. The keys are tried from
 * strongest to weakest: the Calibre uuid, then the file fingerprint, then the EPUB's unique identifier, which counts only
 * when exactly one missing and one live book carry it and their titles agree (tools reuse placeholder ids and editions
 * share ISBNs). When a key
 * matches several live books, the one in the missing book's old folder is taken; if that does not settle it the next key
 * is tried, and a book that stays ambiguous is left missing rather than guessed. Each live book is taken at most once,
 * by the most recently opened missing book.
 */
fun matchMoves(missing: List<IdentityRow>, live: List<IdentityRow>): List<Move> {
  val byUuid = live.filter { it.calibreUuid != null }.groupBy { it.calibreUuid }
  val byFingerprint = live.filter { it.fingerprint != null }.groupBy { it.fingerprint }
  val byUid = live.filter { it.epubUid != null }.groupBy { it.epubUid }
  val missingUidCount = missing.mapNotNull { it.epubUid }.groupingBy { it }.eachCount()
  val taken = HashSet<Long>()
  val moves = mutableListOf<Move>()
  for (m in missing.sortedWith(compareByDescending<IdentityRow> { it.lastOpenedAt }.thenBy { it.id })) {
    val tiers = listOf(
      m.calibreUuid?.let { byUuid[it] },
      m.fingerprint?.let { byFingerprint[it] },
      m.epubUid?.takeIf { missingUidCount[it] == 1 }?.let { byUid[it] }
        ?.takeIf { it.size == 1 && it.single().title.trim().equals(m.title.trim(), ignoreCase = true) },
    )
    for (matches in tiers) {
      val open = matches.orEmpty().filter { !it.hasHistory && it.id !in taken }
      val pick = open.singleOrNull() ?: open.filter { it.folderId == m.folderId }.singleOrNull() ?: continue
      taken += pick.id
      moves += Move(m.id, pick.id)
      break
    }
  }
  return moves
}
