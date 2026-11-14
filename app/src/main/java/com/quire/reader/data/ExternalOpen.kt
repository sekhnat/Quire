package com.quire.reader.data

/**
 * Opening a book file handed to Quire by another app ("Open with", tapping a file, the share sheet). Quire only opens
 * books that are in its library, so a file is matched to a library book, brought into the library, or refused.
 */
sealed interface OpenPlan {
  /** The file is a book already in the library (same path, or the same file by fingerprint). */
  data class Open(val bookId: Long) : OpenPlan

  /** The file is in a watched folder but not in the library yet: scan, then open the book at [path]. */
  data class ScanThenOpen(val path: String) : OpenPlan

  /** The file is one the user removed from the library: bring it back (as Settings → Removed books does), then open it. */
  data class Restore(val path: String) : OpenPlan

  /** The file is from outside the library: copy it into the imported-books folder, scan, then open the copy. */
  data object Import : OpenPlan
}

/** A watched folder, with its path as the library stores it and as the file system resolves it (symlinks followed). */
data class WatchedRoot(val stored: String, val canonical: String)

object ExternalOpenRules {

  /**
   * The path the library would store for the file at [canonicalPath], or null when it is in no watched folder. Folders
   * are kept as the user added them (`/sdcard/Books`), while a file handed over by another app arrives under its real
   * path (`/storage/emulated/0/Books/x.epub`), so both are compared resolved and the folder's own spelling is kept.
   */
  fun libraryPath(canonicalPath: String, roots: List<WatchedRoot>): String? {
    val root = roots
      .filter { canonicalPath.startsWith(it.canonical.trimEnd('/') + "/") }
      .maxByOrNull { it.canonical.length } ?: return null
    return root.stored.trimEnd('/') + "/" + canonicalPath.removePrefix(root.canonical.trimEnd('/') + "/")
  }

  /**
   * What to do with a handed-over file. [libraryPath] is from [libraryPath]; [byPath] and [byFingerprint] are the
   * library's present books at that path and with the file's fingerprint; [removed] is whether [libraryPath] is a file
   * the user removed. A book already in the library always wins, so nothing is copied twice.
   */
  fun plan(libraryPath: String?, byPath: Long?, byFingerprint: Long?, removed: Boolean): OpenPlan = when {
    byPath != null -> OpenPlan.Open(byPath)
    byFingerprint != null -> OpenPlan.Open(byFingerprint)
    libraryPath != null && removed -> OpenPlan.Restore(libraryPath)
    libraryPath != null -> OpenPlan.ScanThenOpen(libraryPath)
    else -> OpenPlan.Import
  }
}

/** What opening a handed-over file came to. */
sealed interface ExternalOpenResult {
  /** Open [bookId]; [added] when it was copied into the library just now ("Added to your library"). */
  data class Opened(val bookId: Long, val added: Boolean) : ExternalOpenResult
  data object Unreadable : ExternalOpenResult
}
