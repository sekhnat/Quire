package com.quire.reader.data.backup

import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.DbTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** The restore protocol against real files: staging, gating, importing after a scan, and resuming. */
class RestoreCoordinatorTest : DbTestCase() {
  private val db = open()
  private val root = File(target.cacheDir, "restore-tests/${UUID.randomUUID()}")

  private fun coordinator(): RestoreCoordinator {
    val dir = root
    return RestoreCoordinator(
      target, db, SettingsStore(target), SnapshotImporter(db, SettingsStore(target), clock = { 9_000 }),
      snapshotLocation = File(dir, "backup/user-data.json"),
      pendingDirectory = File(dir, "pending").apply { mkdirs() },
      clock = { 9_000 },
    )
  }

  private fun pending(coordinator: RestoreCoordinator) = File(root, "pending/pending-restore.json")
  private fun phase(coordinator: RestoreCoordinator) = File(root, "pending/restore-phase")

  private fun writeSnapshot(entry: SnapshotBook) {
    val file = File(root, "backup/user-data.json")
    file.parentFile.mkdirs()
    file.writeText(SnapshotCodec.encode(UserDataSnapshot(1, 1, SnapshotSettings(useCalibre = false), listOf(entry))))
  }

  private fun entry(uuid: String = "u1", entryKey: String = "k1") = SnapshotBook(
    identityKey = "calibre:$uuid", entryKey, identity = SnapshotIdentity(calibreUuid = uuid),
    title = "Emma", author = "Jane Austen", addedAt = 5,
    state = SnapshotBookState(progress = 0.4f, status = "reading", lastOpenedAt = 100),
  )

  @Test fun `a snapshot on an empty library is staged and its settings applied`() = runBlocking {
    val settings = SettingsStore(target)
    settings.setOnboardingDone(true) // what a backup restores; it must not skip the scan
    settings.setUseCalibre(true)
    settings.setIndexOptimized(true)
    settings.setCoversBackfilled(true)
    settings.setTextSearchOrder(com.quire.reader.data.index.SearchOrder.Library)
    writeSnapshot(entry())

    val coordinator = coordinator()
    coordinator.prepare()

    assertTrue(coordinator.state.value is RestoreState.Pending)
    assertTrue(coordinator.isPending)
    assertTrue(pending(coordinator).isFile)
    assertEquals("staged", phase(coordinator).readText())
    assertEquals(false, settings.useCalibre.first()) // the snapshot's own setting, applied at staging
    assertEquals(false, settings.onboardingDone.first())
    assertFalse(settings.indexOptimized.first())
    assertFalse(settings.coversBackfilled.first())
    // Older snapshots omit the search order; the portable DataStore preference must survive.
    assertEquals(com.quire.reader.data.index.SearchOrder.Library, settings.textSearchOrder.first())
  }

  @Test fun `a completed scan imports the staged snapshot and clears the stage`() = runBlocking {
    writeSnapshot(entry())
    val coordinator = coordinator()
    coordinator.prepare()
    val bookId = runBlocking {
      db.books().save(bookEntity(folder(db), "Emma").copy(calibreUuid = "u1"), emptyList())
    }

    val result = coordinator.onScanCompleted()

    assertEquals(1, result!!.matched)
    assertEquals(false, coordinator.isPending)
    assertFalse(pending(coordinator).exists())
    assertFalse(phase(coordinator).exists())
    assertEquals(0.4f, db.states().get(bookId)!!.progress, 0f)
    // A scan with nothing staged does nothing.
    assertNull(coordinator.onScanCompleted())
  }

  @Test fun `an entry nothing matches becomes a missing book when the scan is done`() = runBlocking {
    writeSnapshot(entry(uuid = "u9", entryKey = "k9"))
    val coordinator = coordinator()
    coordinator.prepare()
    runBlocking { db.books().save(bookEntity(folder(db), "Other").copy(calibreUuid = "u2"), emptyList()) }

    val result = coordinator.onScanCompleted()

    assertEquals(1, result!!.tombstoned)
    assertNotNull(db.books().byPath(tombstonePath("k9")))
    assertFalse(coordinator.isPending)
  }

  @Test fun `a snapshot on an existing library without a staged copy is the writers own output`() = runBlocking {
    runBlocking { db.books().save(bookEntity(folder(db), "Emma"), emptyList()) }
    writeSnapshot(entry())

    val coordinator = coordinator()
    coordinator.prepare()

    assertEquals(RestoreState.None, coordinator.state.value)
    assertFalse(pending(coordinator).exists())
    assertNull(coordinator.onScanCompleted())
  }

  @Test fun `an unreadable snapshot on an empty library is reported and never overwritten`() = runBlocking {
    val file = File(root, "backup/user-data.json")
    file.parentFile.mkdirs()
    file.writeText("{not a snapshot")
    val coordinator = coordinator()
    coordinator.prepare()

    val state = coordinator.state.value
    assertTrue(state is RestoreState.Unreadable)
    assertFalse(coordinator.isPending)
    assertEquals("{not a snapshot", file.readText()) // untouched, for recovery
    assertTrue(coordinator.isWriteBlocked)
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    val writer = SnapshotWriter(db, SettingsStore(target), file.parentFile, scope, isRestorePending = { coordinator.isWriteBlocked })
    assertFalse(writer.flush())
    assertEquals("{not a snapshot", file.readText())
    assertNull(coordinator.onScanCompleted())
  }

  @Test fun `a snapshot from a newer schema is reported and never imported`() = runBlocking {
    val file = File(root, "backup/user-data.json")
    file.parentFile.mkdirs()
    file.writeText(SnapshotCodec.encode(UserDataSnapshot(1, 1, SnapshotSettings(), listOf(entry()))).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2"))
    val coordinator = coordinator()
    coordinator.prepare()
    assertTrue(coordinator.state.value is RestoreState.Unreadable)
    assertTrue(file.readText().contains("\"schemaVersion\":2"))
  }

  @Test fun `an interrupted import resumes on the next start`() = runBlocking {
    writeSnapshot(entry())
    val first = coordinator()
    first.prepare()
    assertTrue(first.isPending)

    // A second process starts with the same staged copy: it is pending again, not re-staged.
    val second = coordinator()
    second.prepare()
    assertTrue(second.isPending)

    // The import finished but cleanup crashed: the phase marker resolves it without another import.
    phase(first).writeText("imported")
    val third = coordinator()
    third.prepare()
    assertEquals(RestoreState.None, third.state.value)
    assertFalse(pending(third).exists())
  }

  @Test fun `both staged copies being gone stands the restore down so the writer resumes`() = runBlocking {
    writeSnapshot(entry())
    val coordinator = coordinator()
    coordinator.prepare()
    assertTrue(coordinator.isPending)
    pending(coordinator).delete()
    File(root, "backup/user-data.json").delete()

    assertNull(coordinator.onScanCompleted())
    assertEquals(RestoreState.None, coordinator.state.value)
    assertFalse(coordinator.isPending)
  }
}
