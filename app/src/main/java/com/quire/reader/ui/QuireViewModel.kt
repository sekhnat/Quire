package com.quire.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.quire.reader.QuireApplication
import com.quire.reader.ui.library.LibraryState
import android.content.ComponentName
import android.content.Intent
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.quire.reader.MainActivity
import com.quire.reader.data.backup.BackupContents
import com.quire.reader.data.backup.BackupException
import com.quire.reader.data.backup.BackupInterval
import com.quire.reader.data.backup.BackupWorker
import com.quire.reader.data.backup.NotesExporter
import com.quire.reader.data.backup.SnapshotCodec
import com.quire.reader.data.backup.SnapshotImporter
import com.quire.reader.data.backup.identityKeyFor
import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.ParagraphPreset
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.db.MissingBookRow
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.IndexActivity
import com.quire.reader.data.index.IndexStorageBytes
import com.quire.reader.data.index.IndexTarget
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.reader.OpeningPosition
import com.quire.reader.reader.ReaderSession
import com.quire.reader.reader.SearchHit
import com.quire.reader.reader.STALE_TARGET_MESSAGE
import com.quire.reader.reader.SelectionAction
import com.quire.reader.reader.TargetOutcome
import com.quire.reader.reader.TocEntry
import com.quire.reader.reader.message
import com.quire.reader.reader.openingPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import android.util.Log
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.getOrElse
import com.quire.reader.data.scan.DiscoveryProgress
import com.quire.reader.data.scan.FolderCandidate
import com.quire.reader.data.scan.FolderDiscovery
import com.quire.reader.data.scan.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private const val FOREGROUND_SCAN_GAP_MS = 2 * 60 * 1000L

/** Holds UI state and runs the library, onboarding and detail actions. */
class QuireViewModel(private val app: QuireApplication) : ViewModel(), AppNavigator {
  private val repo = app.library
  private val settings = app.settings
  private val features = Features(app)

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state

  private val _destination = MutableStateFlow<Destination>(Destination.Splash)
  val destination: StateFlow<Destination> = _destination

  private val toaster = Toaster(viewModelScope)
  /** The message showing at the bottom of the screen, if any. */
  val toastText: StateFlow<String?> = toaster.text

  /** The library screen's state; it outlives visits to other screens, so filters and scope are still there on return. */
  val library: LibraryState = features.library(this, toaster, viewModelScope.childScope())

  private val notes: NotesExport = MarkdownNotesExport(app, toaster, viewModelScope)

  private fun navigate(next: Destination) = navigate { next }

  /**
   * Leaves the current destination, closing the state it owns, and only then builds the next one. The order matters:
   * a reader being left lets background indexing resume before a new reader takes it again. Main thread only.
   */
  private fun navigate(build: () -> Destination) {
    closeHolder(_destination.value)
    _destination.value = build()
  }

  private fun closeHolder(d: Destination) {
    if (d is Destination.Reader) d.state.close()
  }

  val scan = repo.scanner.progress

  /** The reading settings new books start with (edited in Settings). */
  val defaults: StateFlow<ReaderPrefs> = repo.readerDefaults.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())
  val useCalibreSetting: StateFlow<Boolean> = repo.useCalibre.stateIn(viewModelScope, SharingStarted.Eagerly, true)
  val watchSetting: StateFlow<Boolean> = repo.watchNewBooks.stateIn(viewModelScope, SharingStarted.Eagerly, true)
  val indexingEnabledSetting: StateFlow<Boolean> = repo.indexingEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
  val indexChargingOnlySetting: StateFlow<Boolean> = repo.indexChargingOnly.stateIn(viewModelScope, SharingStarted.Eagerly, false)

  /** How much of the library is searchable and what the indexer is doing, for Settings. */
  val indexCoverage: StateFlow<IndexCoverage?> = repo.indexCoverage.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
  val indexActivity: StateFlow<IndexActivity> = app.indexer.activity
  val indexedTextBytes: StateFlow<Long> = repo.indexedTextBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

  private val _storageBytes = MutableStateFlow<IndexStorageBytes?>(null)
  /** Disk the library and the search index each take, or null until first measured. */
  val storageBytes: StateFlow<IndexStorageBytes?> = _storageBytes

  /** Whether the global defaults carry non-factory advanced values (the global restore action). */
  val advancedDefaultsCustomized: StateFlow<Boolean> = repo.advancedDefaultsCustomized
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  /** Whether the advanced reading controls are shown at all, for Settings; independent of their values. */
  val advancedReadingEnabled: StateFlow<Boolean> = repo.advancedReadingEnabled
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  private var scanJob: Job? = null
  private var discoverJob: Job? = null

  private fun edit(block: UiState.() -> UiState) = _state.update(block)

  init {
    viewModelScope.launch {
      // Restore detection runs first: on a restored install it overrides the backed-up onboarding
      // flag and applies the snapshot's settings, which the rest of routing and scanning then follow.
      app.restore.prepare()
      val done = settings.onboardingDone.first()
      val useCalibre = settings.useCalibre.first()
      val access = StoragePaths.hasAllFilesAccess()
      // A library of folder books is unreadable without all-files access, which no restore can carry over: ask for it first.
      val needsAccess = done && !access && repo.hasWatchedFolders()
      navigate(if (done && !needsAccess) Destination.Library else Destination.Onboard)
      edit {
        copy(
          onboardStep = if (needsAccess) OnboardStep.Access else onboardStep,
          accessForLibrary = needsAccess,
          hasAccess = access, useCalibre = useCalibre,
        )
      }
    }
  }

  fun toast(text: String) = toaster.show(text)

  // ── onboarding ───────────────────────────────────────────────────────────

  fun refreshAccess() {
    val granted = StoragePaths.hasAllFilesAccess()
    edit { copy(hasAccess = granted) }
    // Coming back from the Settings screen with access granted moves on by itself.
    if (granted && _destination.value == Destination.Onboard && _state.value.onboardStep == OnboardStep.Access) {
      if (_state.value.accessForLibrary) returnToLibrary() else goToFolders()
    }
    if (granted) app.indexer.request()
  }

  fun setStep(step: OnboardStep) = edit { copy(onboardStep = step) }

  fun chooseFolders() {
    if (StoragePaths.hasAllFilesAccess()) goToFolders() else edit { copy(onboardStep = OnboardStep.Access, hasAccess = false) }
  }

  /** Access is back for a library that already exists: open it and scan its folders, which could not be read until now. */
  private fun returnToLibrary() {
    edit { copy(accessForLibrary = false) }
    navigate(Destination.Library)
    viewModelScope.launch {
      val r = repo.rescan()
      if (r.noticeable()) toast(describe(r))
    }
  }

  private fun goToFolders() {
    edit { copy(onboardStep = OnboardStep.Folders, hasAccess = true, discovery = DiscoveryProgress(0, 0, "", 0)) }
    discoverJob?.cancel()
    discoverJob = viewModelScope.launch(Dispatchers.IO) {
      // Folders join the list as they are found; once the walk ends they are put in order, most books first.
      val found = FolderDiscovery.discover { progress, candidate ->
        ensureActive()
        edit {
          if (candidate == null) copy(discovery = progress)
          else copy(discovery = progress, candidates = (candidates + candidate).distinctBy { it.path }, pickedFolders = pickedFolders + candidate.path)
        }
      }
      edit { copy(candidates = (found + candidates).distinctBy { it.path }, discovery = null) }
    }
  }

  fun toggleFolder(path: String) = edit { copy(pickedFolders = if (path in pickedFolders) pickedFolders - path else pickedFolders + path) }
  fun toggleCalibre() = edit { copy(useCalibre = !useCalibre) }
  fun toggleWatch() = edit { copy(watchFolders = !watchFolders) }

  /** A folder chosen with the system picker, added to the candidate list and selected. */
  fun addPickedFolder(path: String?) {
    if (path == null || !StoragePaths.isUsableDirectory(path)) { toast("Quire can't read that folder"); return }
    viewModelScope.launch(Dispatchers.IO) {
      val count = FolderDiscovery.countEpubs(File(path))
      edit { copy(candidates = candidates.filter { it.path != path } + FolderCandidate(StoragePaths.displayName(path), path, count), pickedFolders = pickedFolders + path) }
    }
  }

  fun startScan() {
    val s = _state.value
    edit { copy(onboardStep = OnboardStep.Scan) }
    scanJob?.cancel()
    scanJob = viewModelScope.launch {
      settings.setUseCalibre(s.useCalibre)
      settings.setWatchNewBooks(s.watchFolders)
      settings.setOnboardingDone(true)
      s.pickedFolders.forEach { repo.addFolder(it) }
      repo.rescan()
    }
  }

  override fun openLibrary() = navigate(Destination.Library)

  // ── navigation and app lifecycle ─────────────────────────────────────────

  /** The library narrowed to [scope], from a link on a book's page. */
  override fun openLibraryScope(scope: Scope) { library.setScope(scope); navigate(Destination.Library) }
  private var lastForegroundScan = 0L

  /**
   * The app went to the background: the reader's latest position is saved first, then the snapshot is
   * flushed, so the backed-up file never trails a page turn that happened just before leaving.
   */
  fun onAppStop() {
    app.appScope.launch {
      (_destination.value as? Destination.Reader)?.state?.saveNow()
      if (!app.snapshotWriter.flush()) Log.w("QuireViewModel", "snapshot flush on stop failed or is held back")
    }
  }

  /**
   * Called whenever the app comes to the foreground. Rescans (quietly, and at most every couple of minutes)
   * so books copied in while Quire was in the background show up; only speaks up if something changed.
   */
  fun onForeground() {
    app.indexer.request()
    val now = System.currentTimeMillis()
    if (now - lastForegroundScan < FOREGROUND_SCAN_GAP_MS) return
    lastForegroundScan = now
    viewModelScope.launch {
      if (!settings.onboardingDone.first() || !StoragePaths.hasAllFilesAccess() || !settings.watchNewBooks.first()) return@launch
      val r = repo.rescan()
      if (r.noticeable()) toast(describe(r))
    }
  }

  /** Books whose file is gone but whose reading history is kept; Settings lists them. */
  val missingBooks: StateFlow<List<MissingBookRow>> = repo.missingBooks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  fun forgetMissing(id: Long) = viewModelScope.launch { repo.forgetMissing(listOf(id)); toast("Reading history deleted") }
  fun forgetAllMissing() = viewModelScope.launch { repo.forgetAllMissing(); toast("Reading history of missing books deleted") }

  /** Onboarding's "Import EPUB files": books that come in finish onboarding and open the library. */
  fun importFiles(uris: List<android.net.Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      val n = repo.importFiles(uris)
      if (n > 0) { settings.setOnboardingDone(true); navigate(Destination.Library) }
      toast(if (n > 0) "Imported ${books(n)}" else "Nothing could be imported")
    }
  }

  // ── detail ───────────────────────────────────────────────────────────────

  override fun openDetail(bookId: Long) = navigate { Destination.Detail(features.detail(bookId, notes, this, toaster, viewModelScope)) }

  // ── export and import ────────────────────────────────────────────────────

  /** Writes the reading-data snapshot to a file the user picked (Settings → Export reading data). */
  fun exportReadingData(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
    val ok = runCatching {
      app.contentResolver.openOutputStream(uri)?.use { it.write(app.snapshotWriter.encoded().toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)
    toast(if (ok) "Reading data exported" else "Couldn't write the file")
  }

  /** Merges a picked reading-data file into the library; what the library has is never overwritten. */
  fun importReadingData(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
    val text = runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
    if (text == null) { toast("Couldn't read the file"); return@launch }
    when (val decoded = SnapshotCodec.decode(text)) {
      is SnapshotCodec.Decoded.Ok -> {
        try {
          val result = SnapshotImporter(app.database, app.settings).import(decoded.snapshot, applySettings = false)
          app.snapshotWriter.flush()
          toast(importSummary(result))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w("QuireViewModel", "reading-data import failed", e)
          toast("Couldn't import the reading data")
        }
      }
      is SnapshotCodec.Decoded.UnsupportedVersion -> toast("That file was written by a newer Quire")
      is SnapshotCodec.Decoded.Malformed -> toast("That file isn't Quire reading data")
    }
  }

  fun exportNotes(bookId: Long, uri: Uri) = notes.export(bookId, uri)

  // ── full backup ──────────────────────────────────────────────────────────

  private val backupPrefs = combine(
    settings.backupContents, settings.autoBackupEnabled, settings.autoBackupInterval, settings.autoBackupFolder, settings.autoBackupKeep,
  ) { contents, auto, interval, folder, keep -> BackupUi(contents, auto, interval, folder, keep) }

  private val backupWork = WorkManager.getInstance(app).let { wm ->
    combine(
      wm.getWorkInfosForUniqueWorkFlow(BackupWorker.MANUAL_WORK),
      wm.getWorkInfosForUniqueWorkFlow(BackupWorker.PERIODIC_WORK),
      settings.lastBackupAt,
      settings.lastBackupError,
    ) { manual, periodic, lastAt, lastError ->
      val running = (manual + periodic).firstOrNull { it.state == WorkInfo.State.RUNNING }
      val queued = manual.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
      BackupUi(
        running = running != null || queued,
        progress = running?.progress?.takeIf { it.keyValueMap.containsKey(BackupWorker.KEY_PROGRESS) }?.getFloat(BackupWorker.KEY_PROGRESS, 0f),
        lastAt = lastAt, lastError = lastError,
      )
    }
  }

  /** Full-backup settings and the state of the last or running backup. */
  val backup: StateFlow<BackupUi> = combine(backupPrefs, backupWork) { prefs, work ->
    prefs.copy(running = work.running, progress = work.progress, lastAt = work.lastAt, lastError = work.lastError)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUi())

  private val _backupSizes = MutableStateFlow<BackupSizes?>(null)
  /** What covers and imported books add to a full backup, or null until measured. */
  val backupSizes: StateFlow<BackupSizes?> = _backupSizes

  fun refreshBackupSizes() = viewModelScope.launch(Dispatchers.IO) {
    fun measure(dir: File) = dir.listFiles()?.filter { it.isFile }.orEmpty().let { it.size to it.sumOf(File::length) }
    val (covers, coverBytes) = measure(app.backupLocations.covers)
    val (imported, importedBytes) = measure(app.backupLocations.imported)
    _backupSizes.value = BackupSizes(covers, coverBytes, imported, importedBytes)
  }

  fun setBackupContents(contents: BackupContents) = viewModelScope.launch { settings.setBackupContents(contents) }
  fun setAutoBackup(enabled: Boolean) = viewModelScope.launch { settings.setAutoBackupEnabled(enabled) }
  fun setAutoBackupInterval(interval: BackupInterval) = viewModelScope.launch { settings.setAutoBackupInterval(interval) }
  fun setAutoBackupKeep(keep: Int) = viewModelScope.launch { settings.setAutoBackupKeep(keep) }
  fun setAutoBackupFolder(path: String) = viewModelScope.launch { settings.setAutoBackupFolder(path) }

  /** Starts a full backup into the document the user just created; it runs in the background with a notification. */
  fun startBackup(uri: Uri) {
    // The worker may start after this screen is gone; a persistable grant keeps the document writable until it is done.
    runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    BackupWorker.startManual(app, uri)
    toast("Backing up in the background")
  }

  private val _restore = MutableStateFlow<RestoreUi>(RestoreUi.Idle)
  /** Where a restore from a full backup is; drives the restore sheet. */
  val restore: StateFlow<RestoreUi> = _restore

  /** Reads the picked backup's manifest and shows what it holds, so the user can choose how to restore it. */
  fun inspectBackup(uri: Uri) = viewModelScope.launch {
    _restore.value = RestoreUi.Reading
    _restore.value = try {
      RestoreUi.Ready(uri, app.fullRestore.inspect(uri))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      toast((e as? BackupException)?.message ?: "Couldn't read that backup")
      RestoreUi.Idle
    }
  }

  /** Closes the restore sheet; a restore already underway carries on. */
  fun dismissRestore() { if (_restore.value !is RestoreUi.Working) _restore.value = RestoreUi.Idle }

  /**
   * Replaces the library, reading data, index and settings with the backup, then restarts the app so the restored files
   * are opened fresh. Runs on the app's scope: leaving the screen must not abandon a half-extracted restore.
   */
  fun replaceFromBackup() {
    val ready = _restore.value as? RestoreUi.Ready ?: return
    _restore.value = RestoreUi.Working("Restoring…", 0f)
    app.appScope.launch {
      try {
        app.fullRestore.stage(ready.uri) { p -> _restore.value = RestoreUi.Working(if (p == null) "Checking the backup…" else "Restoring…", p) }
        _restore.value = RestoreUi.Working("Restarting…", null)
        withContext(Dispatchers.Main) { restartApp() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w("QuireViewModel", "full restore failed", e)
        _restore.value = RestoreUi.Idle
        toast((e as? BackupException)?.message ?: "Couldn't restore that backup")
      }
    }
  }

  /** Adds the backup's reading data (and imported books the library lacks) to the library; nothing here is replaced. */
  fun mergeFromBackup() {
    val ready = _restore.value as? RestoreUi.Ready ?: return
    _restore.value = RestoreUi.Working("Adding the backup's reading data…", null)
    app.appScope.launch {
      try {
        val outcome = app.fullRestore.merge(ready.uri)
        toast(mergeSummary(outcome))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w("QuireViewModel", "full-backup merge failed", e)
        toast((e as? BackupException)?.message ?: "Couldn't import that backup")
      } finally {
        _restore.value = RestoreUi.Idle
      }
    }
  }

  /** Starts the app again in a new process; the staged restore is swapped in before anything opens it. */
  private fun restartApp() {
    app.startActivity(Intent.makeRestartActivityTask(ComponentName(app, MainActivity::class.java)))
    Runtime.getRuntime().exit(0)
  }


  // ── reader ───────────────────────────────────────────────────────────────

  override fun openReader(request: ReaderRequest) =
    navigate { Destination.Reader(features.reader(request, library.data, this, toaster, viewModelScope.childScope(), viewModelScope)) }

  override fun onCleared() { closeHolder(_destination.value); super.onCleared() }

  // settings screen

  override fun openSettings() { navigate(Destination.Settings); refreshDatabaseBytes(); refreshBackupSizes() }
  fun closeSettings() = navigate(Destination.Library)
  fun updateDefaults(change: (ReaderPrefs) -> ReaderPrefs) {
    val next = change(defaults.value)
    viewModelScope.launch { repo.setReaderDefaults(next) }
  }

  /** Restores the global advanced controls to the factory values; book overrides stay. */
  fun restoreGlobalAdvanced() = viewModelScope.launch {
    repo.setReaderDefaults(defaults.value.copy(advanced = AdvancedReaderPrefs()))
    toast("Advanced reading settings restored")
  }

  fun setAdvancedReadingEnabled(v: Boolean) = viewModelScope.launch { repo.setAdvancedReadingEnabled(v) }
  fun resetAllBookPrefs() = viewModelScope.launch { repo.clearAllBookPrefs(); toast("Every book now uses your defaults") }
  fun setUseCalibreSetting(v: Boolean) = viewModelScope.launch { repo.setUseCalibre(v); toast("Takes effect on the next full rescan") }
  fun setWatchSetting(v: Boolean) = viewModelScope.launch { repo.setWatchNewBooks(v) }
  fun setIndexingEnabledSetting(v: Boolean) = viewModelScope.launch { repo.setIndexingEnabled(v) }
  fun setIndexChargingOnlySetting(v: Boolean) = viewModelScope.launch { repo.setIndexChargingOnly(v) }

  /** Measures the library and index files on disk, off the main thread. */
  fun refreshDatabaseBytes() = viewModelScope.launch(Dispatchers.IO) { _storageBytes.value = repo.storageBytes() }

  /** Clears the search index and indexes the library again under the current charging and reader rules. Books and reading state stay. */
  fun rebuildIndex() {
    // On the app's scope: the clear must finish and the run be requested even if this screen goes away meanwhile.
    app.appScope.launch {
      app.indexer.rebuild()
      refreshDatabaseBytes()
    }
    toast("Rebuilding the search index")
  }

  /** Turns indexing off and deletes the search index. Books, metadata and reading state are untouched. */
  fun deleteSearchIndex() {
    app.appScope.launch {
      app.indexer.deleteIndex()
      refreshDatabaseBytes()
    }
    toast("Search index deleted")
  }

}

