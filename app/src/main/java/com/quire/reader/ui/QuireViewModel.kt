package com.quire.reader.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quire.reader.QuireApplication
import com.quire.reader.data.scan.StoragePaths
import com.quire.reader.ui.library.LibraryState
import com.quire.reader.ui.onboarding.OnboardingUiState
import com.quire.reader.ui.settings.RestoreState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val FOREGROUND_SCAN_GAP_MS = 2 * 60 * 1000L

/**
 * The app's UI state that belongs to no single screen: where the app is, the toast, the restore sheet, and what happens
 * when the app starts, comes to the foreground or goes to the background. Each screen's own state lives in a holder its
 * [Destination] owns; this builds them (through [Features]) and closes them when their screen is left.
 */
class QuireViewModel(private val app: QuireApplication) : ViewModel(), AppNavigator {
  private val features = Features(app)

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

  private var lastForegroundScan = 0L

  init {
    viewModelScope.launch {
      // Restore detection runs first: on a restored install it overrides the backed-up onboarding
      // flag and applies the snapshot's settings, which the rest of routing and scanning then follow.
      app.restore.prepare()
      val done = app.settings.onboardingDone.first()
      val useCalibre = app.settings.useCalibre.first()
      val access = StoragePaths.hasAllFilesAccess()
      // A library of folder books is unreadable without all-files access, which no restore can carry over: ask for it first.
      val needsAccess = done && !access && app.library.hasWatchedFolders()
      if (done && !needsAccess) navigate(Destination.Library)
      else openOnboarding(
        OnboardingUiState(
          onboardStep = if (needsAccess) OnboardStep.Access else OnboardStep.Welcome,
          accessForLibrary = needsAccess, hasAccess = access, useCalibre = useCalibre,
        ),
      )
    }
  }

  // ── navigation ───────────────────────────────────────────────────────────

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
      is Destination.Onboard -> d.state.close()
      is Destination.Reader -> d.state.close()
      is Destination.Settings -> d.state.close()
      Destination.Splash, Destination.Library, is Destination.Detail -> Unit
    }
  }

  private fun openOnboarding(initial: OnboardingUiState) =
    navigate { Destination.Onboard(features.onboarding(initial, restore, this, toaster, viewModelScope.childScope(), viewModelScope)) }

  override fun openLibrary() = navigate(Destination.Library)

  override fun openLibraryScope(scope: Scope) { library.setScope(scope); navigate(Destination.Library) }

  override fun openDetail(bookId: Long) = navigate { Destination.Detail(features.detail(bookId, notes, this, toaster, viewModelScope)) }

  override fun openReader(request: ReaderRequest) =
    navigate { Destination.Reader(features.reader(request, library.data, this, toaster, viewModelScope.childScope(), viewModelScope)) }

  override fun openSettings() =
    navigate { Destination.Settings(features.settings(restore, notes, this, toaster, viewModelScope.childScope(), viewModelScope)) }

  // ── app lifecycle ────────────────────────────────────────────────────────

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
      if (!app.settings.onboardingDone.first() || !StoragePaths.hasAllFilesAccess() || !app.settings.watchNewBooks.first()) return@launch
      val r = app.library.rescan()
      if (r.noticeable()) toaster.show(describe(r))
    }
  }

  override fun onCleared() { closeHolder(_destination.value); super.onCleared() }
}
