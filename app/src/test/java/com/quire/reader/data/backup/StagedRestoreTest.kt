package com.quire.reader.data.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StagedRestoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private val root by lazy { tmp.newFolder("app") }
  private val at by lazy {
    BackupLocations(
      libraryDb = File(root, "databases/quire.db"),
      indexDb = File(root, "databases/quire-index.db"),
      settings = File(root, "files/datastore/settings.preferences_pb"),
      covers = File(root, "files/covers"),
      imported = File(root, "files/imported"),
      snapshot = File(root, "files/backup/user-data.json"),
      pendingRestoreFiles = listOf(File(root, "no_backup/pending-restore.json"), File(root, "no_backup/restore-phase")),
      restoreDir = File(root, "no_backup/full-restore"),
      scratch = File(root, "cache/full-backup"),
    )
  }

  private fun File.put(text: String) = apply { parentFile!!.mkdirs(); writeText(text) }

  /** The install as it is before the restore: its own databases (with a log), settings, covers and imported books. */
  private fun current() {
    at.libraryDb.put("old library")
    File(at.libraryDb.path + "-wal").put("old library log")
    File(at.libraryDb.path + "-shm").put("old shm")
    at.indexDb.put("old index")
    at.settings.put("old settings")
    File(at.covers, "a.webp").put("old cover a")
    File(at.covers, "only-here.webp").put("old cover")
    File(at.imported, "Emma.epub").put("emma")
    File(at.imported, "Mine.epub").put("imported since the backup")
    File(at.imported, "Twin.epub").put("local twin")
    at.snapshot.put("old snapshot")
    at.pendingRestoreFiles.forEach { it.put("pending") }
  }

  private fun stage(restore: StagedRestore, marker: RestoreMarker) {
    restore.stagedLibrary.put("new library")
    if (marker.index) restore.stagedIndex.put("new index")
    if (marker.settings) restore.stagedSettings.put("new settings")
    restore.stagedSnapshot.put("new snapshot")
    if (marker.covers) File(restore.stagedCovers, "a.webp").put("new cover a")
    File(restore.stagedImported, "Emma.epub").put("emma")
    File(restore.stagedImported, "Persuasion.epub").put("persuasion")
    File(restore.stagedImported, "Mine.epub").put("a different book with the same name")
    File(restore.stagedImported, "Twin.epub").put("other twin")
    runBlocking { restore.dropImportedAlreadyPresent() }
    restore.writeMarker(marker)
  }

  /** The imported books after a swap: the backup's at the names its library expects, and the different local ones moved aside. */
  private fun assertImportedSwapped() {
    assertEquals(
      setOf("Emma.epub", "Mine.epub", "Mine (before restore).epub", "Persuasion.epub", "Twin.epub", "Twin (before restore).epub"),
      at.imported.list()!!.toSet(),
    )
    assertEquals("emma", File(at.imported, "Emma.epub").readText())
    assertEquals("a different book with the same name", File(at.imported, "Mine.epub").readText())
    assertEquals("imported since the backup", File(at.imported, "Mine (before restore).epub").readText())
    assertEquals("other twin", File(at.imported, "Twin.epub").readText())
    assertEquals("local twin", File(at.imported, "Twin (before restore).epub").readText())
  }

  private val everything = RestoreMarker(RestoreMarker.STAGED, index = true, covers = true, settings = true)

  @Test fun `nothing staged swaps nothing`() {
    current()
    assertNull(StagedRestore(at).swapIfStaged())
    assertEquals("old library", at.libraryDb.readText())
  }

  @Test fun `a staged restore replaces databases, settings, covers and the snapshot, and deletes no imported book`() {
    current()
    val restore = StagedRestore(at)
    stage(restore, everything)
    val swapped = restore.swapIfStaged()!!
    assertEquals(RestoreMarker.SWAPPED, swapped.phase)
    assertEquals("new library", at.libraryDb.readText())
    assertFalse("the old log must not be replayed into the new library", File(at.libraryDb.path + "-wal").exists())
    assertFalse(File(at.libraryDb.path + "-shm").exists())
    assertEquals("new index", at.indexDb.readText())
    assertEquals("new settings", at.settings.readText())
    assertEquals("new snapshot", at.snapshot.readText())
    assertEquals(setOf("a.webp"), at.covers.list()!!.toSet())
    assertEquals("new cover a", File(at.covers, "a.webp").readText())
    assertImportedSwapped()
    assertTrue(at.pendingRestoreFiles.none { it.exists() })
    assertFalse(restore.stagedDir.exists())
    // The replaced files are kept until the restored app has started, then cleared.
    assertEquals("old library", File(restore.previousDir, "quire.db").readText())
    assertEquals(swapped, restore.marker())
    restore.clear()
    assertFalse(at.restoreDir.list().orEmpty().any())
  }

  @Test fun `without the index in the backup the current one is removed, and current covers and settings stay`() {
    current()
    val restore = StagedRestore(at)
    stage(restore, RestoreMarker(RestoreMarker.STAGED, index = false, covers = false, settings = false))
    restore.swapIfStaged()
    assertFalse("its book ids belong to the library being replaced", at.indexDb.exists())
    assertEquals("old settings", at.settings.readText())
    assertEquals(setOf("a.webp", "only-here.webp"), at.covers.list()!!.toSet())
    assertEquals("new library", at.libraryDb.readText())
  }

  @Test fun `a swap interrupted at any step finishes on the next start`() {
    // Simulate a process that died after each possible rename by replaying the swap from every partial state.
    val restore = StagedRestore(at)
    current()
    stage(restore, everything)
    // Died after moving the current library aside but before moving the new one in.
    File(restore.previousDir, "quire.db").put("old library")
    at.libraryDb.delete()
    File(at.libraryDb.path + "-wal").delete()
    // ...and after half of the imported books, and between moving a local book aside and moving the backup's in.
    File(restore.stagedImported, "Persuasion.epub").renameTo(File(at.imported, "Persuasion.epub"))
    File(at.imported, "Mine.epub").renameTo(File(at.imported, "Mine (before restore).epub"))
    restore.swapIfStaged()
    assertEquals("new library", at.libraryDb.readText())
    assertEquals("new index", at.indexDb.readText())
    assertImportedSwapped()
    // Swapping again after it finished changes nothing.
    assertEquals(RestoreMarker.SWAPPED, restore.swapIfStaged()!!.phase)
    assertEquals("new library", at.libraryDb.readText())
  }

  @Test fun `staging keeps only the imported books that differ from the local one of their name`() {
    current()
    val restore = StagedRestore(at)
    stage(restore, everything)
    assertEquals(setOf("Mine.epub", "Persuasion.epub", "Twin.epub"), restore.stagedImported.list()!!.toSet())
  }

  @Test fun `a book moved aside by an earlier restore is not overwritten by the next`() {
    current()
    File(at.imported, "Mine (before restore).epub").put("aside from an earlier restore")
    val restore = StagedRestore(at)
    stage(restore, everything)
    restore.swapIfStaged()
    assertEquals("aside from an earlier restore", File(at.imported, "Mine (before restore).epub").readText())
    assertEquals("imported since the backup", File(at.imported, "Mine (before restore 2).epub").readText())
    assertEquals("a different book with the same name", File(at.imported, "Mine.epub").readText())
  }

  @Test fun `a database created by a start whose swap failed is replaced by the retried swap`() {
    current()
    val restore = StagedRestore(at)
    stage(restore, everything)
    at.libraryDb.delete()
    at.libraryDb.put("empty library Room created meanwhile")
    restore.swapIfStaged()
    assertEquals("new library", at.libraryDb.readText())
  }
}
