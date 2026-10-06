package com.quire.reader.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class FullBackupManifestTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun manifest(
    formatVersion: Int = FULL_BACKUP_FORMAT_VERSION,
    libraryDbVersion: Int = 5,
    indexDbVersion: Int? = 1,
    entries: List<BackupEntry> = listOf(BackupEntry(BackupPaths.LIBRARY_DB, 100), BackupEntry(BackupPaths.INDEX_DB, 1000)),
  ) = FullBackupManifest(
    formatVersion, createdAt = 1_700_000_000_000, appVersionName = "1.0", libraryDbVersion = libraryDbVersion,
    indexDbVersion = indexDbVersion, contents = BackupContents(), counts = BackupCounts(books = 3), entries = entries,
  )

  private fun decode(m: FullBackupManifest, current: Int = 5) = FullBackupManifest.decode(FullBackupManifest.encode(m), current)

  @Test fun `a manifest round-trips`() {
    val m = manifest()
    assertEquals(FullBackupManifest.Decoded.Ok(m), decode(m))
    assertEquals(1100L, m.totalBytes)
  }

  @Test fun `an older library schema is accepted and a newer one or a newer format is refused`() {
    assertTrue(decode(manifest(libraryDbVersion = 4)) is FullBackupManifest.Decoded.Ok)
    assertTrue(decode(manifest(libraryDbVersion = 6)) is FullBackupManifest.Decoded.Unsupported)
    assertTrue(decode(manifest(formatVersion = FULL_BACKUP_FORMAT_VERSION + 1)) is FullBackupManifest.Decoded.Unsupported)
  }

  @Test fun `text that is not a manifest, or one without a library, is malformed`() {
    assertTrue(FullBackupManifest.decode("{not json", 5) is FullBackupManifest.Decoded.Malformed)
    assertTrue(FullBackupManifest.decode("""{"formatVersion":1}""", 5) is FullBackupManifest.Decoded.Malformed)
    assertTrue(decode(manifest(entries = listOf(BackupEntry(BackupPaths.SNAPSHOT, 1)))) is FullBackupManifest.Decoded.Malformed)
  }

  @Test fun `entries outside the restored places are refused`() {
    for (path in listOf("covers/../databases/quire.db", "/data/x", "imported/a/b.epub", "covers/", "db/other.db", "imported/..")) {
      assertFalse(path, BackupPaths.isAllowed(path))
      assertTrue(path, decode(manifest(entries = listOf(BackupEntry(BackupPaths.LIBRARY_DB, 1), BackupEntry(path, 1)))) is FullBackupManifest.Decoded.Malformed)
    }
    assertTrue(BackupPaths.isAllowed("covers/0a1b.webp"))
    assertEquals("Emma.epub", BackupPaths.fileIn("imported/Emma.epub", BackupPaths.IMPORTED))
    assertNull(BackupPaths.fileIn("covers/x.webp", BackupPaths.IMPORTED))
  }

  @Test fun `the index is only usable with the same index schema`() {
    assertTrue(manifest(indexDbVersion = 1).indexUsable(1))
    assertFalse(manifest(indexDbVersion = 1).indexUsable(2))
    assertFalse(manifest(indexDbVersion = null).copy(contents = BackupContents(index = false)).indexUsable(1))
  }

  @Test fun `backups are named by date and minute, and a second one in the same minute gets a number`() {
    val at = java.time.LocalDateTime.of(2026, 10, 7, 3, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    assertEquals("Quire backup 2026-10-07 0300.zip", BackupFiles.nameFor(at, ZoneOffset.UTC))
    val folder = tmp.newFolder()
    val first = BackupFiles.freshFile(folder, at, ZoneOffset.UTC).apply { writeText("a") }
    val second = BackupFiles.freshFile(folder, at, ZoneOffset.UTC)
    assertEquals("Quire backup 2026-10-07 0300 (2).zip", second.name)
    assertTrue(BackupFiles.isBackupName(first.name))
    assertTrue(BackupFiles.isBackupName(second.name))
  }

  @Test fun `pruning keeps the newest backups and never touches other files`() {
    val folder = tmp.newFolder()
    fun file(name: String, modified: Long) = File(folder, name).apply { writeText(name); setLastModified(modified) }
    val oldest = file("Quire backup 2026-10-01 0300.zip", 1_000_000)
    val middle = file("Quire backup 2026-10-02 0300.zip", 2_000_000)
    val newest = file("Quire backup 2026-10-03 0300.zip", 3_000_000)
    val other = file("holiday photos.zip", 0)
    val partial = file("Quire backup 2026-10-04 0300.zip.partial", 0)
    val pruned = BackupFiles.toPrune(folder.listFiles()!!.toList(), keep = 2)
    assertEquals(listOf(oldest), pruned)
    assertTrue(listOf(middle, newest, other, partial).none { it in pruned })
    assertEquals(listOf(middle, oldest), BackupFiles.toPrune(folder.listFiles()!!.toList(), keep = 1))
    // Keeping zero would delete the backup just written; at least one always stays.
    assertEquals(2, BackupFiles.toPrune(folder.listFiles()!!.toList(), keep = 0).size)
  }
}
