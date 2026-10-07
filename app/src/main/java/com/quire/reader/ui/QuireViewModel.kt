package com.quire.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.quire.reader.QuireApplication
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
class QuireViewModel(private val app: QuireApplication) : ViewModel() {
  private val repo = app.library
  private val settings = app.settings

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state

  private val _destination = MutableStateFlow<Destination>(Destination.Splash)
  val destination: StateFlow<Destination> = _destination

  private val toaster = Toaster(viewModelScope)
  /** The message showing at the bottom of the screen, if any. */
  val toastText: StateFlow<String?> = toaster.text

  private fun navigate(next: Destination) { _destination.value = next }

  val library: StateFlow<LibraryData> = combine(repo.books, repo.folders) { books, folders -> LibraryData(books, folders) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryData.Empty)

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

  /**
   * The library's "Inside books" search: the status for the typed text and the active filters, with the index
   * coverage and indexer activity that explain partial or missing results. Only runs while something observes it.
   */
  val textSearch: StateFlow<LibraryTextSearch> = combine(
    textSearchStatus(_state.map(::textSearchInput), search = repo::searchText),
    repo.indexCoverage,
    app.indexer.activity,
  ) { status, coverage, activity -> LibraryTextSearch(status, coverage, activity) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryTextSearch())

  private val openBookId = MutableStateFlow(0L)
  /** Whether the open book has its own reading settings instead of the defaults. */
  val hasBookOverride: StateFlow<Boolean> = openBookId.flatMapLatest { id -> if (id == 0L) flowOf(false) else repo.hasBookOverride(id) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  /** Whether the open book carries an advanced object of its own (the book restore action). */
  val hasBookAdvancedOverride: StateFlow<Boolean> = openBookId.flatMapLatest { id -> if (id == 0L) flowOf(false) else repo.hasBookAdvancedOverride(id) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  /** Whether the global defaults carry non-factory advanced values (the global restore action). */
  val advancedDefaultsCustomized: StateFlow<Boolean> = repo.advancedDefaultsCustomized
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  /** Whether the advanced reading controls are shown at all; independent of their values. */
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
    viewModelScope.launch { settings.textSearchOrder.collect { edit { copy(textSearchOrder = it) } } }
    // Only the first saved value is applied: later changes come from this screen, and a shelf's forced grid must not be undone by them.
    viewModelScope.launch { settings.libraryLayout.first().let { saved -> edit { copy(layout = libLayoutOf(saved)) } } }
    viewModelScope.launch {
      val sort = sortKeyOf(settings.librarySort.first())
      val ascending = settings.librarySortAscending.first() ?: false
      // A sort picked while the saved one was loading is newer, and stays.
      if (!sortChosen) edit { copy(sort = sort, sortAscending = ascending) }
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

  fun openLibrary() = navigate(Destination.Library)

  // ── library ──────────────────────────────────────────────────────────────

  fun setView(v: LibView) = edit { copy(view = v) }
  fun setFilter(f: LibFilter) = edit { copy(filter = f) }
  fun setScope(scope: Scope?, view: LibView = LibView.Books) =
    edit { copy(scope = scope, view = view, query = if (scope != null) "" else query) }

  /** The library narrowed to [scope], from a link on a book's page. */
  fun openLibraryScope(scope: Scope) { setScope(scope); navigate(Destination.Library) }
  fun showFilter(f: LibFilter) = edit { copy(view = LibView.Books, filter = f, scope = null) }
  fun showShelf(filter: LibFilter? = null, scope: Scope? = null) = edit { copy(filter = filter ?: LibFilter.All, scope = scope, layout = LibLayout.Grid) }
  fun toggleSearch() = edit { copy(searchOpen = !searchOpen, query = "", textLibraryQuery = "") }
  fun setQuery(q: String) = edit { copy(query = q, view = LibView.Books) }
  fun setSearchScope(scope: SearchScope) = edit { copy(searchScope = scope) }
  fun setTextLibraryQuery(q: String) = edit { copy(textLibraryQuery = q, view = LibView.Books) }
  fun setTextSearchOrder(order: SearchOrder) {
    edit { copy(textSearchOrder = order) }
    viewModelScope.launch { settings.setTextSearchOrder(order) }
  }
  fun openImport(open: Boolean) = edit { copy(importOpen = open) }
  fun openSort(open: Boolean) = edit { copy(sortOpen = open) }
  fun setSortAscending(asc: Boolean) { edit { copy(sortAscending = asc) }; saveSort() }
  fun flipSort() { edit { copy(sortAscending = !sortAscending) }; saveSort() }
  fun pickSort(k: SortKey) {
    edit { copy(sort = k, sortOpen = false, view = LibView.Books, layout = if (layout == LibLayout.Shelves) LibLayout.Grid else layout) }
    saveSort()
  }

  /** Set once a sort is picked here; the saved sort, loaded at start, never replaces it. Main thread only. */
  private var sortChosen = false

  /** Remembers the sort and its direction, so the library opens the same way next time. */
  private fun saveSort() {
    val s = _state.value
    sortChosen = true
    viewModelScope.launch { settings.setLibrarySort(s.sort.name, s.sortAscending) }
  }

  fun cycleLayout() {
    val next = when (_state.value.layout) {
      LibLayout.Grid -> LibLayout.List
      LibLayout.List -> LibLayout.Comfortable
      LibLayout.Comfortable -> LibLayout.Shelves
      LibLayout.Shelves -> LibLayout.Grid
    }
    edit { copy(layout = next, view = LibView.Books) }
    viewModelScope.launch { settings.setLibraryLayout(next.name) }
    toast(when (next) { LibLayout.Grid -> "Grid"; LibLayout.List -> "Dense list"; LibLayout.Comfortable -> "Comfortable list"; LibLayout.Shelves -> "Shelves" } + " layout")
  }

  /** Rescans every watched folder and reports what changed. */
  fun rescan() {
    edit { copy(importOpen = false) }
    viewModelScope.launch {
      if (!StoragePaths.hasAllFilesAccess()) { toast("Allow access to your files first"); return@launch }
      toast("Scanning…")
      toast(describe(repo.rescan()))
    }
  }

  private var lastForegroundScan = 0L

  /**
   * The app went to the background: the reader's latest position is saved first, then the snapshot is
   * flushed, so the backed-up file never trails a page turn that happened just before leaving.
   */
  fun onAppStop() {
    app.appScope.launch {
      (_reader.value as? ReaderLoad.Ready)?.session?.let { session ->
        session.current.value?.let { locator -> runCatching { savePosition(session, locator) } }
      }
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

  fun addFolder(path: String?) {
    viewModelScope.launch {
      if (path == null || !repo.addFolder(path)) { toast("Quire can't add that folder"); return@launch }
      edit { copy(importOpen = false) }
      toast("Scanning…")
      toast(describe(repo.rescan()))
    }
  }

  fun removeFolder(id: Long) = viewModelScope.launch {
    val kept = repo.removeFolder(id)
    toast(if (kept == 0) "Folder removed from the library" else "Folder removed · ${books(kept)} with reading history kept under Missing books")
  }

  /** Books whose file is gone but whose reading history is kept; Settings lists them. */
  val missingBooks: StateFlow<List<MissingBookRow>> = repo.missingBooks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  fun forgetMissing(id: Long) = viewModelScope.launch { repo.forgetMissing(listOf(id)); toast("Reading history deleted") }
  fun forgetAllMissing() = viewModelScope.launch { repo.forgetAllMissing(); toast("Reading history of missing books deleted") }

  fun importFiles(uris: List<android.net.Uri>, fromOnboarding: Boolean = false) {
    if (uris.isEmpty()) return
    edit { copy(importOpen = false) }
    viewModelScope.launch {
      val n = repo.importFiles(uris)
      if (n > 0 && fromOnboarding) { settings.setOnboardingDone(true); navigate(Destination.Library) }
      toast(if (n > 0) "Imported $n ${if (n == 1) "book" else "books"}" else "Nothing could be imported")
    }
  }

  // ── detail ───────────────────────────────────────────────────────────────

  fun openBook(id: Long) { edit { copy(editOpen = false) }; navigate(Destination.Detail(id)) }
  fun goLibrary() { edit { copy(chrome = false) }; navigate(Destination.Library) }
  fun openEdit(open: Boolean) = edit { copy(editOpen = open) }
  fun setFinished(id: Long, finished: Boolean) = viewModelScope.launch { repo.setFinished(id, finished); toast(if (finished) "Marked as finished" else "Marked as unread") }
  fun setRating(id: Long, rating: Int?) = viewModelScope.launch { repo.setUserRating(id, rating) }
  fun addTag(id: Long, tag: String) = viewModelScope.launch { repo.addTag(id, tag) }
  fun removeTag(id: Long, tag: String) = viewModelScope.launch { repo.removeTag(id, tag) }

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

  /** Exports a book's highlights as Markdown; missing books export what they kept. */
  fun exportNotes(bookId: Long, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
    val book = repo.book(bookId)
    if (book == null) { toast("That book is no longer in the library"); return@launch }
    val highlights = repo.highlights(bookId).first()
    if (highlights.isEmpty()) { toast("No highlights to export"); return@launch }
    val chapterTitles = if (book.missingSince == null && File(book.path).isFile) {
      NotesExporter.chapterTitles(app.publicationLoader, book.path)
    } else emptyMap()
    val markdown = NotesExporter.markdown(book.title, book.author, identityKeyFor(book), highlights, chapterTitles)
    val ok = runCatching {
      app.contentResolver.openOutputStream(uri)?.use { it.write(markdown.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)
    toast(if (ok) "Notes exported" else "Couldn't write the file")
  }

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

  private val _reader = MutableStateFlow<ReaderLoad>(ReaderLoad.Idle)
  val reader: StateFlow<ReaderLoad> = _reader

  private val _prefs = MutableStateFlow(ReaderPrefs())
  /** The open book's reading settings (its own, or the defaults). */
  val prefs: StateFlow<ReaderPrefs> = _prefs

  private val _bookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())
  val bookmarks: StateFlow<List<BookmarkEntity>> = _bookmarks
  private val _highlights = MutableStateFlow<List<HighlightEntity>>(emptyList())
  val highlights: StateFlow<List<HighlightEntity>> = _highlights

  private val _search = MutableStateFlow(SearchUi())
  val search: StateFlow<SearchUi> = _search

  /** The reader overlay's library-search results while [UiState.bookSearch] is set. */
  private val _bookSearch = MutableStateFlow(BookSearchUi())
  val bookSearchUi: StateFlow<BookSearchUi> = _bookSearch

  private var readerJobs: Job? = null
  private var searchJob: Job? = null
  private var bookSearchJob: Job? = null
  private var targetJob: Job? = null

  /** Opens a book in the reader. [restart] ignores the saved position (the "Read again" button). */
  fun read(id: Long, restart: Boolean = false) = startReading(id, restart, target = null, libraryQuery = null)

  /**
   * Opens a library text-search result: the book at the matched passage, underlined, in place of its saved position
   * for this opening only. A result for a file that has changed since it was indexed is not opened; the book's index
   * is refreshed instead.
   */
  fun openTextHit(target: IndexTarget) {
    viewModelScope.launch {
      if (!repo.isCurrent(target)) { staleTarget(); return@launch }
      startReading(target.bookId, restart = false, target = target, libraryQuery = null)
    }
  }

  /** "Show all in this book": opens the book at its saved position with the search overlay in library-search mode for [query]. */
  fun openBookSearch(bookId: Long, query: String) = startReading(bookId, restart = false, target = null, libraryQuery = query)

  private fun staleTarget() {
    toast(STALE_TARGET_MESSAGE)
    app.indexer.request()
  }

  private fun startReading(id: Long, restart: Boolean, target: IndexTarget?, libraryQuery: String?) {
    // Before anything is loaded, so background indexing steps aside while the book opens.
    app.indexer.setReaderBusy(true)
    closeReaderSession(release = false)
    openBookId.value = id
    navigate(Destination.Reader)
    edit { copy(chrome = false, sheet = null, textSearchOpen = false, textQuery = "", bookSearch = null, activeHighlight = null, noteFor = null, showZones = false) }
    _reader.value = ReaderLoad.Loading
    viewModelScope.launch {
      val book = repo.book(id)
      if (book == null) { _reader.value = ReaderLoad.Failed("This book is no longer in the library."); releaseIndexer(); return@launch }
      val opened = app.publicationLoader.open(File(book.path))
      val publication = opened.getOrElse {
        repo.markUnreadable(id)
        _reader.value = ReaderLoad.Failed("This book can’t be opened. The file may be damaged or protected.")
        releaseIndexer()
        return@launch
      }
      val positions = withContext(Dispatchers.IO) { runCatching { publication.positions() }.getOrDefault(emptyList()) }
      val saved = repo.readingState(id)
      val initial = when (openingPosition(restart, hasTarget = target != null)) {
        OpeningPosition.Target -> target?.let { ReaderSession.targetLocator(it, positions) }
        OpeningPosition.Saved -> ReaderSession.parseLocator(saved?.locatorJson)
        OpeningPosition.Start -> null
      }
      repo.markOpened(id)
      repo.updatePageCount(id, positions.size)
      val libraryBook = library.value.byId[id]
      if (libraryBook == null) { publication.close(); _reader.value = ReaderLoad.Failed("This book is no longer in the library."); releaseIndexer(); return@launch }
      val session = ReaderSession(libraryBook, publication, positions, initial)
      _reader.value = ReaderLoad.Ready(session)
      edit { copy(brightness = 100, advancedOpen = false) }
      startReaderJobs(session)
      if (target != null) targetJob = launch { reportOutcome(session.goToTarget(target)) }
      if (libraryQuery != null) enterBookSearch(id, libraryQuery)
    }
  }

  private fun reportOutcome(outcome: TargetOutcome) { outcome.message()?.let(::toast) }

  @OptIn(FlowPreview::class)
  private fun startReaderJobs(session: ReaderSession) {
    val id = session.book.id
    readerJobs = viewModelScope.launch {
      launch { repo.readerPrefs(id).collect { _prefs.value = it } }
      launch { repo.bookmarks(id).collect { _bookmarks.value = it } }
      launch { repo.highlights(id).collect { _highlights.value = it } }
      launch { repo.brightness.collect { b -> edit { copy(brightness = b) } } }
      // Save the position a moment after the reader stops moving.
      launch {
        session.current.filterNotNull().drop(1).debounce(800).collect { savePosition(session, it) }
      }
      launch { runCatching { session.resolveChapterAnchors() } }
      // Keep highlight decorations in step with the database and with the navigator coming and going.
      launch {
        _highlights.collect { list -> runCatching { session.applyHighlights(list) } }
      }
    }
  }

  private suspend fun savePosition(session: ReaderSession, locator: Locator) {
    repo.savePosition(session.book.id, locator.toJSON().toString(), (locator.locations.totalProgression ?: 0.0).toFloat())
  }

  /** Leaves the reader, saving where it was. */
  fun closeReader() {
    closeReaderSession()
    edit { copy(chrome = false, sheet = null, textSearchOpen = false, bookSearch = null, activeHighlight = null, noteFor = null) }
    navigate(Destination.Library)
  }

  /** Lets background indexing resume now that no reader is opening or open. */
  private fun releaseIndexer() {
    app.indexer.setReaderBusy(false)
    app.indexer.request()
  }

  private fun closeReaderSession(release: Boolean = true) {
    readerJobs?.cancel(); searchJob?.cancel(); bookSearchJob?.cancel(); targetJob?.cancel()
    (_reader.value as? ReaderLoad.Ready)?.session?.let { session ->
      val locator = session.current.value
      // Written outside viewModelScope's cancellation so the last page turn is never lost.
      if (locator != null) app.appScope.launch { savePosition(session, locator) }
      session.close()
    }
    _reader.value = ReaderLoad.Idle
    _search.value = SearchUi()
    _bookSearch.value = BookSearchUi()
    _highlights.value = emptyList(); _bookmarks.value = emptyList()
    if (release) releaseIndexer()
  }

  override fun onCleared() { closeReaderSession(); super.onCleared() }

  private fun session(): ReaderSession? = (_reader.value as? ReaderLoad.Ready)?.session

  fun setChrome(show: Boolean) = edit { copy(chrome = show) }
  fun openSheet(sheet: Sheet?, tab: TocTab? = null) = edit { copy(sheet = sheet, tocTab = tab ?: tocTab) }
  fun setTocTab(tab: TocTab) = edit { copy(tocTab = tab) }
  fun setAdvancedOpen(open: Boolean) = edit { copy(advancedOpen = open) }
  fun showZones(show: Boolean) = edit { copy(showZones = show, sheet = if (show) null else sheet, chrome = if (show) false else chrome) }
  fun closeReaderOverlays() = edit { copy(sheet = null, chrome = false, textSearchOpen = false, showZones = false, activeHighlight = null, noteFor = null) }

  // reading settings

  /**
   * Applies a change to the open book's basic settings: the accepted state is published
   * once, and a persistence echo equal to it is just an acknowledgment (the state flow
   * drops it); an echo that differs is the stored truth and wins.
   */
  fun updatePrefs(change: (ReaderPrefs) -> ReaderPrefs) {
    val id = session()?.book?.id ?: return
    val next = change(_prefs.value)
    _prefs.value = next
    viewModelScope.launch { repo.setBookPrefs(id, next) }
  }

  /** Applies a change to the open book's advanced controls; its basic group is untouched. */
  fun updateBookAdvanced(change: (AdvancedReaderPrefs) -> AdvancedReaderPrefs) {
    val id = session()?.book?.id ?: return
    val next = change(_prefs.value.advanced)
    _prefs.value = _prefs.value.copy(advanced = next)
    viewModelScope.launch { repo.setBookAdvancedPrefs(id, next) }
  }

  /** Chooses a paragraph preset, setting indent and spacing in one reduction. */
  fun chooseParagraphPreset(preset: ParagraphPreset) {
    val levels = AdvancedReaderPrefs.levelsFor(preset) ?: return
    updateBookAdvanced { it.copy(paragraphIndent = levels.first, paragraphSpacing = levels.second) }
  }

  fun resetBookPrefs() {
    val id = session()?.book?.id ?: return
    viewModelScope.launch { repo.clearBookPrefs(id); toast("Using your default settings") }
  }

  /** Restores this book's advanced controls to the globals; its basic override stays. */
  fun restoreBookAdvanced() {
    val id = session()?.book?.id ?: return
    _prefs.value = _prefs.value.copy(advanced = defaults.value.advanced)
    viewModelScope.launch {
      repo.clearBookAdvancedPrefs(id)
      toast("This book's advanced settings follow your defaults")
    }
  }

  // settings screen

  fun openSettings() { navigate(Destination.Settings); refreshDatabaseBytes(); refreshBackupSizes() }
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

  fun useForAllBooks() {
    val id = session()?.book?.id ?: return
    viewModelScope.launch { repo.useForAllBooks(id, _prefs.value); toast("These settings are now the default for every book") }
  }

  fun setBrightness(v: Int) { edit { copy(brightness = v) }; viewModelScope.launch { repo.setBrightness(v) } }

  // bookmarks

  /** Whether the page showing now is bookmarked: a bookmark within half a position of the reader's place. */
  fun isBookmarked(session: ReaderSession, marks: List<BookmarkEntity>): Boolean {
    val here = session.totalProgress
    val tolerance = 0.5f / session.positions.size.coerceAtLeast(1)
    return marks.any { kotlin.math.abs(it.progress - here) <= tolerance }
  }

  fun toggleBookmark() {
    val session = session() ?: return
    val locator = session.current.value ?: return
    val existing = _bookmarks.value.filter { kotlin.math.abs(it.progress - session.totalProgress) <= 0.5f / session.positions.size.coerceAtLeast(1) }
    viewModelScope.launch {
      if (existing.isNotEmpty()) existing.forEach { repo.deleteBookmark(it.id) }
      else repo.addBookmark(BookmarkEntity(bookId = session.book.id, locatorJson = locator.toJSON().toString(), label = session.chapterTitle().ifEmpty { "Bookmark" }, progress = session.totalProgress, createdAt = System.currentTimeMillis()))
    }
  }

  fun deleteBookmark(id: Long) = viewModelScope.launch { repo.deleteBookmark(id) }

  // highlights and notes

  fun onSelectionAction(action: SelectionAction) {
    val session = session() ?: return
    viewModelScope.launch {
      val locator = session.currentSelection() ?: return@launch
      when (action) {
        SelectionAction.Copy -> {
          val text = locator.text.highlight.orEmpty()
          val cm = app.getSystemService(android.content.ClipboardManager::class.java)
          cm.setPrimaryClip(android.content.ClipData.newPlainText("Quire", text))
          session.clearSelection(); toast("Copied")
        }
        SelectionAction.Highlight, SelectionAction.Note -> {
          // The chapter goes into the locator's display title: note exports can label this highlight
          // forever, even once the book is gone. Canonical keys ignore the title, so merging is unaffected.
          val stamped = locator.copy(title = session.chapterTitle(locator).ifEmpty { locator.title })
          val id = repo.addHighlight(
            HighlightEntity(
              bookId = session.book.id, locatorJson = stamped.toJSON().toString(), text = locator.text.highlight.orEmpty(),
              progress = (locator.locations.totalProgression ?: 0.0).toFloat(), createdAt = System.currentTimeMillis(),
            ),
          )
          session.clearSelection()
          if (action == SelectionAction.Note) edit { copy(noteFor = id) } else toast("Highlighted. Find it under Contents → Highlights")
        }
      }
    }
  }

  fun setActiveHighlight(id: Long?) = edit { copy(activeHighlight = id, chrome = if (id != null) false else chrome) }
  fun editNote(id: Long?) = edit { copy(noteFor = id, activeHighlight = null) }
  fun saveNote(id: Long, note: String) { viewModelScope.launch { repo.setHighlightNote(id, note) }; edit { copy(noteFor = null) } }
  fun deleteHighlight(id: Long) { viewModelScope.launch { repo.deleteHighlight(id) }; edit { copy(activeHighlight = null, noteFor = null) } }

  fun copyHighlight(id: Long) {
    val text = _highlights.value.firstOrNull { it.id == id }?.text ?: return
    app.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Quire", text))
    edit { copy(activeHighlight = null) }; toast("Copied")
  }

  // search inside the book

  fun setTextSearch(open: Boolean) {
    edit { copy(textSearchOpen = open, chrome = false, bookSearch = if (open) bookSearch else null) }
    if (!open) { searchJob?.cancel(); bookSearchJob?.cancel(); _search.value = SearchUi(); _bookSearch.value = BookSearchUi(); viewModelScope.launch { session()?.applySearchHits(emptyList()) } }
  }

  fun setTextQuery(q: String) {
    edit { copy(textQuery = q) }
    searchJob?.cancel()
    val session = session() ?: return
    if (q.trim().length < 2) { _search.value = SearchUi(); return }
    searchJob = viewModelScope.launch {
      delay(250) // wait for the user to pause typing
      _search.value = SearchUi(query = q.trim(), running = true)
      runCatching { session.search(q.trim()) { hits -> _search.value = SearchUi(q.trim(), hits, running = true) } }
      _search.value = _search.value.copy(running = false)
    }
  }

  /** Jumps to a search result and underlines the matches. */
  fun openSearchHit(hit: SearchHit) {
    val session = session() ?: return
    edit { copy(textSearchOpen = false, chrome = false) }
    session.go(hit.locator)
    viewModelScope.launch { session.applySearchHits(_search.value.hits) }
  }

  // search inside the book, library-search mode

  private fun enterBookSearch(bookId: Long, query: String) {
    edit { copy(textSearchOpen = true, chrome = false, bookSearch = BookSearchMode(bookId, query)) }
    runBookSearch(bookId, query)
  }

  /** Leaves library-search mode; the overlay stays open as the ordinary in-book search. */
  fun closeBookSearch() {
    bookSearchJob?.cancel()
    _bookSearch.value = BookSearchUi()
    edit { copy(bookSearch = null) }
    viewModelScope.launch { session()?.applySearchHits(emptyList()) }
  }

  /** Edits the library-search query. It keeps library semantics, and only this book, until the mode is closed. */
  fun setBookSearchQuery(q: String) {
    val mode = _state.value.bookSearch ?: return
    edit { copy(bookSearch = mode.copy(query = q)) }
    runBookSearch(mode.bookId, q)
  }

  private fun runBookSearch(bookId: Long, text: String) {
    bookSearchJob?.cancel()
    val plan = planBookSearch(text)
    _bookSearch.value = plan.ui
    val query = plan.query ?: return
    bookSearchJob = viewModelScope.launch {
      delay(250) // wait for the user to pause typing
      val page = try { repo.searchBookPage(bookId, query) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
      if (page == null) { _bookSearch.value = BookSearchUi(); toast("Couldn’t search this book"); return@launch }
      _bookSearch.value = bookSearchFirstPage(page)
    }
  }

  /** Loads the next page of library-search matches; called as the list scrolls near its end. */
  fun loadMoreBookSearch() {
    val mode = _state.value.bookSearch ?: return
    val current = _bookSearch.value
    val after = current.nextAfterSeq ?: return
    if (current.loadingMore || current.status != BookSearchStatus.Results) return
    val query = (FtsQuery.parse(mode.query) as? FtsQuery.Result.Query) ?: return
    _bookSearch.value = current.copy(loadingMore = true)
    // The same job as the first page, so a newer query cancels a page that is still loading.
    bookSearchJob = viewModelScope.launch {
      val page = try { repo.searchBookPage(mode.bookId, query, after) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
      _bookSearch.update { if (page == null) it.copy(loadingMore = false) else it.withPage(after, page) }
    }
  }

  /** Jumps to a library-search match: its own passage is underlined, and the overlay steps aside. */
  fun openBookSearchHit(target: IndexTarget) {
    val session = session() ?: return
    if (target.bookId != session.book.id) return
    targetJob?.cancel()
    targetJob = viewModelScope.launch {
      if (!repo.isCurrent(target)) { staleTarget(); return@launch }
      edit { copy(textSearchOpen = false, chrome = false) }
      reportOutcome(session.goToTarget(target))
    }
  }

  fun goTo(entry: TocEntry) { session()?.go(entry.link); closeReaderOverlays() }
  fun goTo(locator: Locator) { session()?.go(locator); closeReaderOverlays() }
}

sealed interface ReaderLoad {
  data object Idle : ReaderLoad
  data object Loading : ReaderLoad
  data class Failed(val message: String) : ReaderLoad
  data class Ready(val session: ReaderSession) : ReaderLoad
}

data class SearchUi(val query: String = "", val hits: List<SearchHit> = emptyList(), val running: Boolean = false)
