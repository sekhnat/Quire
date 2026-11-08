package com.quire.reader.data.scan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FolderTagsTest {
  private val downloads = File("/storage/emulated/0/Download")

  private fun tag(path: String, root: File = downloads, calibre: Boolean = false, imported: File? = null) =
    FolderTags.of(File(path), root, calibre, imported)

  @Test fun `a book directly in the watched folder is tagged with that folder`() {
    assertEquals("Download", tag("/storage/emulated/0/Download/book.epub"))
  }

  @Test fun `a book in a subfolder is tagged with the folder it sits in`() {
    assertEquals("ABC", tag("/storage/emulated/0/Download/ABC/book.epub"))
    assertEquals("DEF", tag("/storage/emulated/0/Download/ABC/DEF/book.epub"))
  }

  @Test fun `a Calibre book is tagged with the watched library folder, not its own book folder`() {
    val calibre = File("/storage/emulated/0/Calibre Library")
    assertEquals("Calibre Library", tag("/storage/emulated/0/Calibre Library/Jane Austen/Emma (12)/Emma - Jane Austen.epub", calibre, calibre = true))
  }

  @Test fun `the storage root shows as Internal storage`() {
    val root = File(StoragePaths.PRIMARY_ROOT)
    assertEquals("Internal storage", tag("/storage/emulated/0/book.epub", root))
    assertEquals("Books", tag("/storage/emulated/0/Books/book.epub", root))
  }

  @Test fun `books in the imported-books folder are tagged Imported`() {
    val imported = File("/data/user/0/com.quire.reader/files/imported")
    assertEquals(FolderTags.IMPORTED, tag("${imported.path}/book.epub", imported, imported = imported))
    assertEquals("Download", tag("/storage/emulated/0/Download/book.epub", imported = imported))
  }

  @Test fun `a trailing slash on the watched folder makes no difference`() {
    assertEquals("Download", tag("/storage/emulated/0/Download/book.epub", File("/storage/emulated/0/Download/")))
  }
}
