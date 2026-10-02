package com.quire.reader.data.scan

import java.io.File

data class FolderCandidate(val name: String, val path: String, val epubCount: Int)

/** Finds likely book folders on shared storage so onboarding can offer them as a starting point. */
object FolderDiscovery {
  private val skip = setOf("Android", "DCIM", "Pictures", "Movies", "Music", "Alarms", "Notifications", "Ringtones", "Podcasts")
  private const val MAX_DEPTH = 6

  fun discover(roots: List<File> = storageRoots()): List<FolderCandidate> {
    val out = mutableListOf<FolderCandidate>()
    for (root in roots) {
      val children = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.name !in skip }.orEmpty()
      for (dir in children) {
        val count = countEpubs(dir, depth = 1)
        if (count > 0) out += FolderCandidate(dir.name, dir.absolutePath, count)
      }
    }
    return out.sortedByDescending { it.epubCount }
  }

  /** Internal storage plus any removable volumes. */
  fun storageRoots(): List<File> {
    val roots = mutableListOf(File(StoragePaths.PRIMARY_ROOT))
    File("/storage").listFiles { f -> f.isDirectory && f.name != "emulated" && f.name != "self" }?.let { roots += it }
    return roots.filter { it.isDirectory }
  }

  /** EPUB files under [dir], looking up to six levels down (enough for Calibre's Author/Book layout). */
  fun countEpubs(dir: File, depth: Int = 1): Int {
    if (depth > MAX_DEPTH) return 0
    var n = 0
    for (f in dir.listFiles().orEmpty()) {
      when {
        f.isDirectory -> if (!f.name.startsWith(".")) n += countEpubs(f, depth + 1)
        f.name.endsWith(".epub", ignoreCase = true) -> n++
      }
    }
    return n
  }
}
