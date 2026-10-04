package com.quire.reader.data.scan

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

/** Known books whose folder was walked ([reachableFolderIds]) but whose file was not among [foundPaths]. */
fun removedIds(foundPaths: Set<String>, known: Map<String, KnownFile>, reachableFolderIds: Set<Long>): List<Long> =
  known.values.filter { it.folderId in reachableFolderIds && it.path !in foundPaths }.map { it.id }
