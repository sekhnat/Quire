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
fun planScan(found: List<FoundFile>, known: Map<String, KnownFile>, reachableFolderIds: Set<Long>): ScanPlan {
  val foundPaths = found.mapTo(HashSet()) { it.path }
  val toRead = found.filter { f ->
    val k = known[f.path]
    k == null || k.sizeBytes != f.sizeBytes || k.mtime != f.mtime
  }
  val removed = known.values.filter { it.folderId in reachableFolderIds && it.path !in foundPaths }.map { it.id }
  return ScanPlan(toRead, removed)
}
