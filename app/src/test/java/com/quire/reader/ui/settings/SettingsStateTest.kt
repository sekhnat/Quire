package com.quire.reader.ui.settings

import android.net.TestUri
import android.net.Uri
import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.WidenLevel
import com.quire.reader.data.backup.BackupContents
import com.quire.reader.data.backup.BackupException
import com.quire.reader.data.backup.BackupInterval
import com.quire.reader.data.backup.FullBackupManifest
import com.quire.reader.data.backup.ImportResult
import com.quire.reader.data.backup.MergeOutcome
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.db.MissingBookRow
import com.quire.reader.data.index.IndexStorageBytes
import com.quire.reader.ui.BackupSizes
import com.quire.reader.ui.BackupUi
import com.quire.reader.ui.FakeIndexer
import com.quire.reader.ui.RecordingNavigator
import com.quire.reader.ui.RecordingToasts
import com.quire.reader.ui.RestoreUi
import com.quire.reader.ui.Visit
import com.quire.reader.ui.childScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStateTest {
  private class FakeSettings : LibrarySettings {
    val defaults = MutableStateFlow(ReaderPrefs())
    var measured = 0
    override val readerDefaults: Flow<ReaderPrefs> = defaults
    override suspend fun setReaderDefaults(prefs: ReaderPrefs) { defaults.value = prefs }
    override val advancedDefaultsCustomized = flowOf(false)
    override val advancedReadingEnabled = flowOf(false)
    override suspend fun setAdvancedReadingEnabled(v: Boolean) = Unit
    override suspend fun clearAllBookPrefs() = Unit
    override val useCalibre = flowOf(true)
    override suspend fun setUseCalibre(v: Boolean) = Unit
    override val watchNewBooks = flowOf(true)
    override suspend fun setWatchNewBooks(v: Boolean) = Unit
    override val indexingEnabled = flowOf(true)
    override suspend fun setIndexingEnabled(v: Boolean) = Unit
    override val indexChargingOnly = flowOf(false)
    override suspend fun setIndexChargingOnly(v: Boolean) = Unit
    override val indexCoverage: Flow<IndexCoverage> = emptyFlow()
    override val indexedTextBytes = flowOf(0L)
    override suspend fun storageBytes(): IndexStorageBytes { measured++; return IndexStorageBytes(1, 2) }
    override val missingBooks = flowOf(emptyList<MissingBookRow>())
    override suspend fun forgetMissing(ids: List<Long>) = Unit
    override suspend fun forgetAllMissing() = Unit
  }

  private class FakeBackups : BackupService {
    var started: Uri? = null
    var importResult: ReadingDataImport = ReadingDataImport.Unreadable
    override val choices = flowOf(BackupUi(auto = true))
    override val work = flowOf(BackupUi(running = true, lastAt = 5))
    override suspend fun sizes() = BackupSizes(1, 10, 2, 20)
    override fun start(uri: Uri) { started = uri }
    override suspend fun setContents(contents: BackupContents) = Unit
    override suspend fun setAutoBackup(enabled: Boolean) = Unit
    override suspend fun setInterval(interval: BackupInterval) = Unit
    override suspend fun setKeep(keep: Int) = Unit
    override suspend fun setFolder(path: String) = Unit
    override suspend fun exportReadingData(uri: Uri) = false
    override suspend fun importReadingData(uri: Uri) = importResult
  }

  private class FakeRestore : RestoreService {
    var manifest: FullBackupManifest? = null
    var restarted = false
    val staging = CompletableDeferred<Unit>()
    override suspend fun inspect(uri: Uri) = manifest ?: throw BackupException("Not a Quire backup")
    override suspend fun stage(uri: Uri, onProgress: (Float?) -> Unit) { onProgress(0.5f); staging.await() }
    override suspend fun merge(uri: Uri) = MergeOutcome(ImportResult(0, 0, 0, 0, 0, 0), booksAdded = 2)
    override suspend fun restart() { restarted = true }
  }

  private val store = FakeSettings()
  private val backups = FakeBackups()
  private val restoreService = FakeRestore()
  private val indexer = FakeIndexer()
  private val nav = RecordingNavigator()
  private val toasts = RecordingToasts()
  private val uri = TestUri("content://backup")

  private fun TestScope.restore() = RestoreState(restoreService, toasts, backgroundScope, backgroundScope)

  private fun TestScope.settings(restore: RestoreState = restore()): SettingsState {
    val scope = backgroundScope.childScope()
    return SettingsState(store, indexer, { _, _ -> }, BackupState(backups, toasts, scope, backgroundScope), restore, nav, toasts, scope, backgroundScope, backgroundScope)
  }

  @Test fun `opening Settings measures storage and backup sizes`() = runTest {
    val settings = settings()
    runCurrent()
    assertEquals(IndexStorageBytes(1, 2), settings.storageBytes.value)
    assertEquals(BackupSizes(1, 10, 2, 20), settings.backup.sizes.value)
  }

  @Test fun `restoring the global advanced controls writes the factory values and keeps the basics`() = runTest {
    store.defaults.value = ReaderPrefs(fontSize = 22, advanced = AdvancedReaderPrefs(letterSpacing = WidenLevel.Wider))
    val settings = settings()
    runCurrent()
    settings.restoreGlobalAdvanced(); runCurrent()
    assertEquals(ReaderPrefs(fontSize = 22, advanced = AdvancedReaderPrefs()), store.defaults.value)
    assertEquals(listOf("Advanced reading settings restored"), toasts.shown)
  }

  @Test fun `rebuilding the index measures storage again`() = runTest {
    val settings = settings()
    runCurrent()
    settings.rebuildIndex(); runCurrent()
    assertEquals(1, indexer.rebuilt)
    assertEquals(2, store.measured)
    assertEquals(listOf("Rebuilding the search index"), toasts.shown)
  }

  @Test fun `leaving Settings goes back to the library`() = runTest {
    settings().leave()
    assertEquals(listOf(Visit.Library), nav.visits)
  }

  @Test fun `the backup section combines choices with what backups are doing`() = runTest {
    val settings = settings()
    backgroundScope.launch { settings.backup.backup.collect {} }
    runCurrent()
    assertEquals(BackupUi(auto = true, running = true, lastAt = 5), settings.backup.backup.value)
    settings.backup.start(uri)
    assertEquals(uri, backups.started)
    assertEquals("Backing up in the background", toasts.shown.last())
  }

  @Test fun `each reading-data import outcome has its message`() = runTest {
    val settings = settings()
    for (outcome in listOf(ReadingDataImport.Unreadable, ReadingDataImport.NewerVersion, ReadingDataImport.NotReadingData, ReadingDataImport.Failed)) {
      backups.importResult = outcome
      settings.backup.importReadingData(uri); runCurrent()
    }
    settings.backup.exportReadingData(uri); runCurrent()
    assertEquals(
      listOf("Couldn't read the file", "That file was written by a newer Quire", "That file isn't Quire reading data", "Couldn't import the reading data", "Couldn't write the file"),
      toasts.shown,
    )
  }

  @Test fun `a backup that can't be read goes back to idle with its reason`() = runTest {
    val restore = restore()
    restore.inspect(uri); runCurrent()
    assertEquals(RestoreUi.Idle, restore.ui.value)
    assertEquals(listOf("Not a Quire backup"), toasts.shown)
  }

  @Test fun `replacing from a backup shows progress, keeps the sheet open and restarts the app`() = runTest {
    restoreService.manifest = FullBackupManifest(1, 0, "1.0", 1, contents = BackupContents())
    val restore = restore()
    restore.inspect(uri); runCurrent()
    assertTrue(restore.ui.value is RestoreUi.Ready)
    restore.replace(); runCurrent()
    assertEquals(RestoreUi.Working("Restoring…", 0.5f), restore.ui.value)
    restore.dismiss()
    assertTrue("a running restore can't be dismissed", restore.ui.value is RestoreUi.Working)
    restoreService.staging.complete(Unit); runCurrent()
    assertTrue(restoreService.restarted)
  }

  @Test fun `a restore outlives the Settings visit that started it`() = runTest {
    restoreService.manifest = FullBackupManifest(1, 0, "1.0", 1, contents = BackupContents())
    val restore = restore()
    val first = settings(restore)
    first.restore.inspect(uri); runCurrent()
    first.restore.replace(); runCurrent()
    first.close()
    assertTrue(settings(restore).restore.ui.value is RestoreUi.Working)
  }

  @Test fun `merging a backup reports what it added and closes the sheet`() = runTest {
    restoreService.manifest = FullBackupManifest(1, 0, "1.0", 1, contents = BackupContents())
    val restore = restore()
    restore.inspect(uri); runCurrent()
    restore.merge(); runCurrent()
    assertEquals(RestoreUi.Idle, restore.ui.value)
    assertEquals(listOf("2 books added"), toasts.shown)
  }
}
