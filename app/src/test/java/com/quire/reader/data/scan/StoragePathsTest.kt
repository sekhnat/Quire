package com.quire.reader.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoragePathsTest {
  @Test fun `primary volume maps to emulated storage`() {
    assertEquals("/storage/emulated/0/Books", StoragePaths.docIdToPath("primary:Books"))
    assertEquals("/storage/emulated/0/Calibre Library/Authors", StoragePaths.docIdToPath("primary:Calibre Library/Authors"))
    assertEquals("/storage/emulated/0", StoragePaths.docIdToPath("primary:"))
  }

  @Test fun `removable volume maps to its mount`() {
    assertEquals("/storage/1A2B-3C4D/Novels", StoragePaths.docIdToPath("1A2B-3C4D:Novels"))
  }

  @Test fun `non-storage providers and path tricks are rejected`() {
    assertNull(StoragePaths.docIdToPath("msf:1234"))
    assertNull(StoragePaths.docIdToPath("raw:/storage/emulated/0/Download"))
    assertNull(StoragePaths.docIdToPath("primary:../../data"))
    assertNull(StoragePaths.docIdToPath("no-colon"))
  }

  @Test fun `root displays as Internal storage`() {
    assertEquals("Internal storage", StoragePaths.displayName("/storage/emulated/0"))
    assertEquals("Internal storage", StoragePaths.displayName("/storage/emulated/0/"))
  }

  @Test fun `named folders keep their name`() {
    assertEquals("Download", StoragePaths.displayName("/storage/emulated/0/Download"))
    assertEquals("Calibre Library", StoragePaths.displayName("/storage/1A2B-3C4D/Calibre Library"))
  }
}
