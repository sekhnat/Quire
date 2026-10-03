package com.quire.reader.data.index

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.QuireApplication
import com.quire.reader.data.db.BookEntity
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.IndexStateEntity
import com.quire.reader.data.scan.StoragePaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Runs the real worker chain inside the app under test, which must be a throwaway application id (`.dbtest`):
 * the books and settings used here live in that app's own database, not the user's. The worker needs all-files access,
 * and granting it kills the app's process, so it cannot be granted from here: install the debug and test APKs, run
 * `adb shell appops set <applicationId> MANAGE_EXTERNAL_STORAGE allow`, then `adb shell am instrument`. Without the
 * grant these tests are skipped.
 */
class IndexWorkerTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as QuireApplication
  private val dir = File(app.cacheDir, "db-tests/worker").apply { mkdirs() }
  private var folderId = 0L
  private val bookIds = mutableListOf<Long>()

  @Before fun setUp() {
    assumeTrue("all-files access is not granted to ${app.packageName}", StoragePaths.hasAllFilesAccess())
    runBlocking {
      app.settings.setIndexingEnabled(true)
      app.settings.setIndexChargingOnly(false)
      app.database.index().clearAll()
      folderId = app.database.folders().insert(FolderEntity(path = dir.absolutePath))
    }
  }

  @After fun tearDown() {
    runBlocking {
      app.settings.setIndexingEnabled(true)
      app.database.books().delete(bookIds)
      app.database.folders().delete(folderId)
    }
    dir.deleteRecursively()
  }

  private suspend fun addBook(name: String, text: String): Long {
    val file = EpubFixtures.write(File(dir, "$name.epub"), listOf(EpubFixtures.chapter("One", text)))
    val entity = BookEntity(
      path = file.absolutePath, folderId = folderId, sizeBytes = file.length(), mtime = file.lastModified(), title = name, sortTitle = name,
      author = "Author", primaryAuthor = "Author", authorSort = "Author", addedAt = 0,
    )
    return app.database.books().save(entity, emptyList()).also { bookIds += it }
  }

  private suspend fun status(bookId: Long): String? =
    app.database.openHelper.readableDatabase.query("SELECT status FROM index_state WHERE bookId = ?", arrayOf<Any>(bookId)).use { if (it.moveToFirst()) it.getString(0) else null }

  private suspend fun awaitStatus(bookId: Long, expected: String) = withTimeout(60_000) {
    while (status(bookId) != expected) delay(250)
  }

  @Test fun `a request makes the worker index the eligible books`() = runBlocking {
    val id = addBook("emma", "Highbury was quiet that morning.")

    app.indexer.request()

    awaitStatus(id, IndexStateEntity.STATUS_DONE)
    assertEquals(IndexActivity.Idle, withTimeout(30_000) { app.indexer.activity.first { it == IndexActivity.Idle } })
  }

  @Test fun `a disabled setting stops new books being indexed until it is enabled again`() = runBlocking {
    app.settings.setIndexingEnabled(false)
    delay(1_000)
    val id = addBook("persuasion", "Anne walked to Lyme.")

    app.indexer.request()
    delay(3_000)
    assertNull(status(id))
    assertEquals(IndexActivity.Disabled, withTimeout(10_000) { app.indexer.activity.first { it == IndexActivity.Disabled } })

    app.settings.setIndexingEnabled(true)
    awaitStatus(id, IndexStateEntity.STATUS_DONE)
  }

  @Test fun `an open reader holds work back until it is released and a request is made`() = runBlocking {
    app.indexer.setReaderBusy(true)
    val id = addBook("northanger", "Catherine read the novel.")

    app.indexer.request()
    delay(3_000)
    assertNull(status(id))

    app.indexer.setReaderBusy(false)
    app.indexer.request()
    awaitStatus(id, IndexStateEntity.STATUS_DONE)
  }

  /** Runs a shell command as the shell user and waits for it to finish. */
  private fun shell(command: String) {
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
  }

  @Test fun `charging-only holds the work until the device is charging`() {
    try {
      shell("dumpsys battery unplug")
      runBlocking {
        app.settings.setIndexChargingOnly(true)
        delay(1_000)
        val id = addBook("mansfield", "Fanny sat in the east room.")

        app.indexer.request()
        withTimeout(30_000) { app.indexer.activity.first { it == IndexActivity.WaitingForCharging } }
        assertNull(status(id))

        shell("dumpsys battery set ac 1")
        awaitStatus(id, IndexStateEntity.STATUS_DONE)
      }
    } finally {
      shell("dumpsys battery reset")
      runBlocking { app.settings.setIndexChargingOnly(false) }
    }
  }
}
