package com.quire.reader.ui.onboarding

import android.net.Uri
import com.quire.reader.data.scan.DiscoveryProgress
import com.quire.reader.data.scan.FolderCandidate
import com.quire.reader.data.scan.ScanProgress
import com.quire.reader.data.scan.ScanResult
import com.quire.reader.ui.AppNavigator
import com.quire.reader.ui.IndexerControl
import com.quire.reader.ui.OnboardStep
import com.quire.reader.ui.Toasts
import com.quire.reader.ui.books
import com.quire.reader.ui.describe
import com.quire.reader.ui.noticeable
import com.quire.reader.ui.settings.RestoreState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What onboarding writes and scans (`SettingsStore`, `LibraryRepository`). */
interface OnboardingStore {
  val scan: StateFlow<ScanProgress>
  suspend fun setUseCalibre(v: Boolean)
  suspend fun setWatchNewBooks(v: Boolean)
  suspend fun setOnboardingDone(v: Boolean)
  suspend fun addFolder(path: String): Boolean
  suspend fun rescan(): ScanResult
  /** Returns how many files were imported. */
  suspend fun importFiles(uris: List<Uri>): Int
}

/** Shared storage: the all-files permission and the folders that hold books (`StoragePaths`, `FolderDiscovery`). */
interface StorageAccess {
  fun hasAllFilesAccess(): Boolean
  fun isUsableDirectory(path: String): Boolean
  fun displayName(path: String): String
  /** Walks shared storage for folders with books, reporting each as it is found; returns them all, most books first. */
  suspend fun discover(onFound: (DiscoveryProgress, FolderCandidate?) -> Unit): List<FolderCandidate>
  suspend fun countEpubs(path: String): Int
}

data class OnboardingUiState(
  val onboardStep: OnboardStep = OnboardStep.Welcome,
  val hasAccess: Boolean = false,
  /**
   * The Access step is guarding an existing library (a full restore brings back "onboarding done" but never the
   * permission): granting access returns to the library instead of moving on to choosing folders.
   */
  val accessForLibrary: Boolean = false,
  val candidates: List<FolderCandidate> = emptyList(),
  /** Progress of the automatic folder discovery on the Folders step; null when it isn't running. */
  val discovery: DiscoveryProgress? = null,
  val pickedFolders: Set<String> = emptySet(),
  val useCalibre: Boolean = true,
  val watchFolders: Boolean = true,
) {
  val discovering get() = discovery != null
}

/**
 * First start (and getting all-files access back for an existing library): welcome, permission, choosing folders, and
 * the first scan. Made when onboarding is shown and closed when it is left; the first scan and imports run on
 * [persist], so they carry on into the library.
 */
class OnboardingState(
  initial: OnboardingUiState,
  private val store: OnboardingStore,
  private val storage: StorageAccess,
  private val indexer: IndexerControl,
  val restore: RestoreState,
  private val nav: AppNavigator,
  private val toasts: Toasts,
  private val scope: CoroutineScope,
  private val persist: CoroutineScope,
) {
  private val _state = MutableStateFlow(initial)
  val state: StateFlow<OnboardingUiState> = _state

  val scan: StateFlow<ScanProgress> = store.scan

  private var discoverJob: Job? = null
  private var scanJob: Job? = null

  private fun edit(block: OnboardingUiState.() -> OnboardingUiState) = _state.update(block)

  /** Stops folder discovery; a scan or import already started carries on. */
  fun close() = scope.cancel()

  fun refreshAccess() {
    val granted = storage.hasAllFilesAccess()
    edit { copy(hasAccess = granted) }
    // Coming back from the Settings screen with access granted moves on by itself.
    if (granted && _state.value.onboardStep == OnboardStep.Access) {
      if (_state.value.accessForLibrary) returnToLibrary() else goToFolders()
    }
    if (granted) indexer.request()
  }

  fun setStep(step: OnboardStep) = edit { copy(onboardStep = step) }

  fun chooseFolders() {
    if (storage.hasAllFilesAccess()) goToFolders() else edit { copy(onboardStep = OnboardStep.Access, hasAccess = false) }
  }

  /** Access is back for a library that already exists: open it and scan its folders, which could not be read until now. */
  private fun returnToLibrary() {
    edit { copy(accessForLibrary = false) }
    nav.openLibrary()
    persist.launch {
      val r = store.rescan()
      if (r.noticeable()) toasts.show(describe(r))
    }
  }

  private fun goToFolders() {
    edit { copy(onboardStep = OnboardStep.Folders, hasAccess = true, discovery = DiscoveryProgress(0, 0, "", 0)) }
    discoverJob?.cancel()
    discoverJob = scope.launch {
      // Folders join the list as they are found; once the walk ends they are put in order, most books first.
      val found = storage.discover { progress, candidate ->
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
    if (path == null || !storage.isUsableDirectory(path)) { toasts.show("Quire can't read that folder"); return }
    scope.launch {
      val count = storage.countEpubs(path)
      edit { copy(candidates = candidates.filter { it.path != path } + FolderCandidate(storage.displayName(path), path, count), pickedFolders = pickedFolders + path) }
    }
  }

  fun startScan() {
    val s = _state.value
    edit { copy(onboardStep = OnboardStep.Scan) }
    scanJob?.cancel()
    scanJob = persist.launch {
      store.setUseCalibre(s.useCalibre)
      store.setWatchNewBooks(s.watchFolders)
      store.setOnboardingDone(true)
      s.pickedFolders.forEach { store.addFolder(it) }
      store.rescan()
    }
  }

  fun openLibrary() = nav.openLibrary()

  /** "Import EPUB files": books that come in finish onboarding and open the library. */
  fun importFiles(uris: List<Uri>) {
    if (uris.isEmpty()) return
    persist.launch {
      val n = store.importFiles(uris)
      if (n > 0) { store.setOnboardingDone(true); nav.openLibrary() }
      toasts.show(if (n > 0) "Imported ${books(n)}" else "Nothing could be imported")
    }
  }
}
