package com.quire.reader.data.scan

import java.io.File

/** The tag a scan gives each book for the folder it was found in (stored with origin `folder`). */
object FolderTags {
  /** What books in the imported-books folder are tagged with, rather than that folder's own name. */
  const val IMPORTED = "Imported"

  /**
   * The folder tag of [book], found under the watched folder [root]: the folder the file sits in, so
   * `Downloads/ABC/DEF/x.epub` is tagged `DEF`. A book directly in [root], or in a Calibre book folder ([calibreLayout]:
   * `metadata.opf` beside it, where every book has a folder of its own), is tagged with the watched folder's name.
   * [importedDir] is the app's imported-books folder, whose books are tagged [IMPORTED]. Null when there is no name.
   */
  fun of(book: File, root: File, calibreLayout: Boolean, importedDir: File? = null): String? {
    if (importedDir != null && root.absoluteFile == importedDir.absoluteFile) return IMPORTED
    val parent = book.absoluteFile.parentFile
    val tag = if (calibreLayout || parent == null || parent == root.absoluteFile) StoragePaths.displayName(root.absolutePath) else parent.name
    return tag.trim().takeIf { it.isNotEmpty() }
  }
}
