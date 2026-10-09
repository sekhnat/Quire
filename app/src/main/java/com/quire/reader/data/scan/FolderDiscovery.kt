package com.quire.reader.data.scan

import java.io.File

data class FolderCandidate(val name: String, val path: String, val epubCount: Int)

/** How far [FolderDiscovery.discover] has got: top-level folders [done] of [total], the one it is in now, and books seen so far. */
data class DiscoveryProgress(val done: Int, val total: Int, val current: String, val epubs: Int) {
  val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/** Finds likely book folders on shared storage so onboarding can offer them as a starting point. */
object FolderDiscovery {
  private val skip = setOf("Android", "DCIM", "Pictures", "Movies", "Music", "Alarms", "Notifications", "Ringtones", "Podcasts")
  private const val MAX_DEPTH = 6
  private const val EPUB_REPORT_STEP = 25

  /**
   * The top-level folders of [roots] that hold books, most first. [onProgress] hears about each folder as it is
   * entered, every [EPUB_REPORT_STEP] books inside it, and once more when it is done, with the folder's candidate
   * if it had any books.
   */
  fun discover(
    roots: List<File> = storageRoots(),
    onProgress: (DiscoveryProgress, FolderCandidate?) -> Unit = { _, _ -> },
  ): List<FolderCandidate> {
    val dirs = roots.flatMap { root -> root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.name !in skip }.orEmpty().asList() }
    val out = mutableListOf<FolderCandidate>()
    var epubs = 0
    dirs.forEachIndexed { i, dir ->
      onProgress(DiscoveryProgress(i, dirs.size, dir.name, epubs), null)
      var seen = 0
      val count = countEpubs(dir) {
        seen++
        if (seen % EPUB_REPORT_STEP == 0) onProgress(DiscoveryProgress(i, dirs.size, dir.name, epubs + seen), null)
      }
      epubs += count
      val candidate = if (count > 0) FolderCandidate(dir.name, dir.absolutePath, count).also { out += it } else null
      onProgress(DiscoveryProgress(i + 1, dirs.size, dir.name, epubs), candidate)
    }
    return out.sortedByDescending { it.epubCount }
  }

  /** Internal storage plus any removable volumes. */
  fun storageRoots(): List<File> {
    val roots = mutableListOf(File(StoragePaths.PRIMARY_ROOT))
    File("/storage").listFiles { f -> f.isDirectory && f.name != "emulated" && f.name != "self" }?.let { roots += it }
    return roots.filter { it.isDirectory }
  }

  /**
   * Books under [dir] (EPUB, AZW3 and MOBI files, a book kept in several formats once), looking up to six levels down
   * (enough for Calibre's Author/Book layout). [onEpub] runs for each.
   */
  fun countEpubs(dir: File, depth: Int = 1, onEpub: () -> Unit = {}): Int {
    if (depth > MAX_DEPTH) return 0
    var n = 0
    val files = ArrayList<File>()
    for (f in dir.listFiles().orEmpty()) {
      if (f.isDirectory) { if (!f.name.startsWith(".")) n += countEpubs(f, depth + 1, onEpub) } else files += f
    }
    repeat(BookFormats.preferred(files).size) { n++; onEpub() }
    return n
  }
}
