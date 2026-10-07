package com.quire.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.quire.reader.QuireApplication
import com.quire.reader.ui.library.LibraryState
import com.quire.reader.ui.settings.RestoreState
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

  /** The restore sheet; one for the app, since a restore carries on when Settings or onboarding is left. */
  val restore: RestoreState = features.restore(toaster, viewModelScope)

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
    when (d) {
      is Destination.Reader -> d.state.close()
      is Destination.Settings -> d.state.close()
      else -> Unit
    }
  }

  val scan = repo.scanner.progress

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

  // ── reader ───────────────────────────────────────────────────────────────

  override fun openReader(request: ReaderRequest) =
    navigate { Destination.Reader(features.reader(request, library.data, this, toaster, viewModelScope.childScope(), viewModelScope)) }

  override fun onCleared() { closeHolder(_destination.value); super.onCleared() }

  // ── settings ─────────────────────────────────────────────────────────────

  override fun openSettings() = navigate { Destination.Settings(features.settings(restore, notes, this, toaster, viewModelScope.childScope(), viewModelScope)) }
}
