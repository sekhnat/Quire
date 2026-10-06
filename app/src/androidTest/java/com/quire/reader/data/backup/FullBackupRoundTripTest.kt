package com.quire.reader.data.backup

import android.net.Uri
import androidx.room.useReaderConnection
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.BookStateEntity
import com.quire.reader.data.db.DbTestCase
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.IndexChunk
import com.quire.reader.data.index.IndexStore
import com.quire.reader.data.index.RoomIndexSql
import com.quire.reader.data.index.SourceElement
import com.quire.reader.data.index.TextChunker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A full backup written from one install and restored onto another: what the archive holds, that the restored databases
 * are intact and answer the same searches, and how a merge and the follow-up after a restart behave.
 */
class FullBackupRoundTripTest : DbTestCase() {
  private val root = File(target.cacheDir, "full-backup-tests/${UUID.randomUUID()}")
  private val settings = SettingsStore(target)

  @After fun cleanUp() { root.deleteRecursively() }

  /** An install's files, all inside the test's scratch folder. */
  private fun install(name: String) = File(root, name).let { dir ->
    BackupLocations(
      libraryDb = File(dir, "databases/quire.db"),
      indexDb = File(dir, "databases/quire-index.db"),
      settings = File(dir, "files/datastore/settings.preferences_pb"),
      covers = File(dir, "files/covers"),
      imported = File(dir, "files/imported"),
      snapshot = File(dir, "files/backup/user-data.json"),
      pendingRestoreFiles = listOf(File(dir, "no_backup/pending-restore.json")),
      restoreDir = File(dir, "no_backup/full-restore"),
      scratch = File(dir, "cache/full-backup"),
    ).also { it.libraryDb.parentFile!!.mkdirs() }
  }

  private fun chunks(vararg texts: String): List<IndexChunk> = TextChunker.chunk(
    texts.map { SourceElement("ch1.xhtml", it, false, "application/xhtml+xml", 0.5, 0.5, "One", chapterStart = true) },
  ).chunks

  private class Source(val at: BackupLocations, val db: QuireDatabase, val index: IndexDatabase, val bookId: Long)

  /** A small library with reading data, an indexed book, settings, a cover and an imported book. */
  private fun source(): Source = runBlocking {
    val at = install("source")
    val db = open(at.libraryDb)
    val index = openIndex(at.indexDb)
    val folderId = folder(db)
    val book = bookEntity(folderId, "Emma").copy(calibreUuid = "uuid-emma", fingerprint = "100:aaaa", coverPath = File(at.covers, "c1.webp").path)
    val id = db.books().save(book, listOf("Classics"))
    db.books().save(bookEntity(folderId, "Persuasion"), emptyList())
    db.states().put(BookStateEntity(id, locatorJson = """{"href":"c1.xhtml"}""", progress = 0.4f, status = "reading", lastOpenedAt = 500))
    db.annotations().addHighlight(HighlightEntity(bookId = id, locatorJson = """{"href":"c1.xhtml"}""", text = "Highbury", note = "quiet", progress = 0.3f, createdAt = 12))
    IndexStore(RoomIndexSql(index)).replaceBook(id, book.mtime, book.sizeBytes, chunks("Highbury was quiet", "Mr Knightley called"), false, 0, fingerprint = "100:aaaa")
    at.settings.apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
    File(at.covers, "c1.webp").apply { parentFile!!.mkdirs(); writeText("cover bytes") }
    File(at.imported, "Imported.epub").apply { parentFile!!.mkdirs(); writeText("imported book bytes") }
    Source(at, db, index, id)
  }

  private fun writer(s: Source) =
    FullBackupWriter(s.db, s.index, SnapshotWriter(s.db, settings, File(root, "snapshots"), CoroutineScope(Dispatchers.IO)), s.at, "test")

  private fun backUp(s: Source, contents: BackupContents = BackupContents()): Pair<File, FullBackupManifest> = runBlocking {
    val archive = File(root, "backup-${UUID.randomUUID()}.zip")
    val manifest = writer(s).write(archive.outputStream(), contents)
    archive to manifest
  }

  private fun restorer(at: BackupLocations, db: QuireDatabase? = null, scanned: MutableList<Unit> = mutableListOf()): FullRestore {
    val library = db ?: open(File(root, "unused-${UUID.randomUUID()}.db"))
    return FullRestore(
      target, at, library, settings, { scanned += Unit }, SnapshotImporter(library, settings),
      SnapshotWriter(library, settings, File(root, "snapshots-restore"), CoroutineScope(Dispatchers.IO)),
    )
  }

  private fun IndexDatabase.pragma(name: String): Long = runBlocking { useReaderConnection { c -> c.usePrepared("PRAGMA $name") { it.step(); it.getLong(0) } } }

  @Test fun `the archive starts with its manifest and keeps user data before the databases and records what it holds`() {
    val s = source()
    val (archive, manifest) = backUp(s)
    val names = ZipFile(archive).use { zip -> zip.entries().toList().map { it.name } }
    assertEquals(BackupPaths.MANIFEST, names.first())
    assertEquals(listOf(BackupPaths.LIBRARY_DB, BackupPaths.INDEX_DB), names.takeLast(2))
    assertTrue(names.indexOf(BackupPaths.IMPORTED + "Imported.epub") < names.indexOf(BackupPaths.LIBRARY_DB))
    assertEquals(names.drop(1), manifest.entries.map { it.path })
    assertEquals(BackupCounts(books = 2, highlights = 1, bookmarks = 0, indexedBooks = 1, covers = 1, importedBooks = 1), manifest.counts)
    assertEquals(QuireDatabase.VERSION, manifest.libraryDbVersion)
    assertEquals(IndexDatabase.VERSION, manifest.indexDbVersion)
    assertFalse("the scratch copies are removed", s.at.scratch.exists())
  }

  @Test fun `leaving the index out keeps it out of the archive and the manifest`() {
    val (archive, manifest) = backUp(source(), BackupContents(index = false, covers = false, imported = false))
    val names = ZipFile(archive).use { zip -> zip.entries().toList().map { it.name } }
    assertFalse(BackupPaths.INDEX_DB in names)
    assertTrue(names.none { it.startsWith(BackupPaths.COVERS) || it.startsWith(BackupPaths.IMPORTED) })
    assertNull(manifest.indexDbVersion)
    assertFalse(manifest.contents.index)
  }

  @Test fun `a replace restore brings back intact databases that answer the same searches`() = runBlocking {
    val s = source()
    val (archive) = backUp(s)
    val at = install("target")
    at.libraryDb.writeText("not a database")
    File(at.libraryDb.path + "-wal").writeText("stale log")

    val marker = restorer(at).stage(Uri.fromFile(archive))
    assertEquals(RestoreMarker(RestoreMarker.STAGED, index = true, covers = true, settings = true), marker)
    assertTrue("staged databases carry no journal files", StagedRestore(at).stagedDir.walk().none { it.name.endsWith("-wal") || it.name.endsWith("-journal") })
    StagedRestore(at).swapIfStaged()

    val db = open(at.libraryDb)
    assertEquals(2, db.books().totalCount())
    val emma = db.books().byId(s.bookId)!!
    assertEquals("uuid-emma", emma.calibreUuid)
    assertEquals(0.4f, db.states().get(s.bookId)!!.progress)
    assertEquals(listOf("Highbury"), db.annotations().highlightsOf(s.bookId).map { it.text })
    assertEquals("ok", db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })

    val index = openIndex(at.indexDb)
    assertEquals(s.index.hits("knightley"), index.hits("knightley"))
    assertEquals(1, index.hits("highbury").size)
    index.checkIntegrity()
    assertEquals(16_384L, index.pragma("page_size"))
    assertEquals("incremental", 2L, index.pragma("auto_vacuum"))
    assertEquals("100:aaaa", IndexStore(RoomIndexSql(index)).sourceFingerprint(s.bookId))

    assertArrayEquals(byteArrayOf(1, 2, 3, 4), at.settings.readBytes())
    assertEquals("cover bytes", File(at.covers, "c1.webp").readText())
    assertEquals("imported book bytes", File(at.imported, "Imported.epub").readText())
    assertTrue(SnapshotCodec.decode(at.snapshot.readText()) is SnapshotCodec.Decoded.Ok)
  }

  @Test fun `an index from another index schema is left out of the restore`() = runBlocking {
    val (archive) = backUp(source())
    val rewritten = rewrite(archive) { it.copy(indexDbVersion = IndexDatabase.VERSION + 1) }
    val at = install("target")
    val marker = restorer(at).stage(Uri.fromFile(rewritten))
    assertFalse(marker.index)
    assertFalse(StagedRestore(at).stagedIndex.exists())
  }

  @Test fun `an archive from a newer library schema or not a backup at all and stages nothing`() = runBlocking {
    val (archive) = backUp(source())
    val newer = rewrite(archive) { it.copy(libraryDbVersion = QuireDatabase.VERSION + 1) }
    val notBackup = File(root, "photos.zip").apply { ZipOutputStream(outputStream()).use { it.putNextEntry(ZipEntry("photo.jpg")); it.write(1) } }
    for (file in listOf(newer, notBackup)) {
      val at = install("target-${file.name}")
      try {
        restorer(at).stage(Uri.fromFile(file))
        fail("${file.name} was staged")
      } catch (e: BackupException) {
        assertNull(StagedRestore(at).marker())
        assertFalse(StagedRestore(at).stagedDir.exists())
      }
    }
  }

  @Test fun `a merge adds the reading data and the missing imported books to the current library`() = runBlocking {
    val s = source()
    val (archive) = backUp(s)
    val at = install("target")
    val db = open(at.libraryDb)
    val folderId = folder(db)
    val local = db.books().save(bookEntity(folderId, "Emma").copy(calibreUuid = "uuid-emma"), emptyList())
    val scanned = mutableListOf<Unit>()

    val outcome = restorer(at, db, scanned).merge(Uri.fromFile(archive))

    assertEquals(1, outcome.booksAdded)
    assertEquals(1, scanned.size)
    assertEquals("imported book bytes", File(at.imported, "Imported.epub").readText())
    assertEquals(listOf("Highbury"), db.annotations().highlightsOf(local).map { it.text })
    assertEquals(0.4f, db.states().get(local)!!.progress)
    // Merging again adds nothing new.
    assertEquals(0, restorer(at, db, scanned).merge(Uri.fromFile(archive)).booksAdded)
    assertEquals(1, db.annotations().highlightsOf(local).size)
  }

  @Test fun `after a restore without covers and books whose cover did not come back are queued for a new one`() = runBlocking {
    val at = install("target")
    val db = open(at.libraryDb)
    val folderId = folder(db)
    val kept = File(at.covers, "kept.webp").apply { parentFile!!.mkdirs(); writeText("x") }
    val withCover = db.books().save(bookEntity(folderId, "Emma").copy(coverPath = kept.path), emptyList())
    val lost = db.books().save(bookEntity(folderId, "Persuasion").copy(coverPath = File(at.covers, "gone.webp").path), emptyList())
    settings.setCoversBackfilled(true)
    StagedRestore(at).writeMarker(RestoreMarker(RestoreMarker.SWAPPED, index = true, covers = false, settings = true))

    restorer(at, db).finishAfterStart()

    assertEquals(kept.path, db.books().byId(withCover)!!.coverPath)
    assertNull(db.books().byId(lost)!!.coverPath)
    assertFalse(settings.coversBackfilled.first())
    assertNull(StagedRestore(at).marker())
  }

  /** A copy of [archive] whose manifest is changed by [change]. */
  private fun rewrite(archive: File, change: (FullBackupManifest) -> FullBackupManifest): File {
    val out = File(root, "rewritten-${UUID.randomUUID()}.zip")
    ZipFile(archive).use { zip ->
      ZipOutputStream(out.outputStream()).use { dest ->
        for (entry in zip.entries()) {
          dest.putNextEntry(ZipEntry(entry.name))
          val bytes = zip.getInputStream(entry).readBytes()
          if (entry.name == BackupPaths.MANIFEST) {
            val manifest = (FullBackupManifest.decode(bytes.decodeToString(), QuireDatabase.VERSION) as FullBackupManifest.Decoded.Ok).manifest
            dest.write(FullBackupManifest.encode(change(manifest)).toByteArray())
          } else dest.write(bytes)
          dest.closeEntry()
        }
      }
    }
    return out
  }
}
