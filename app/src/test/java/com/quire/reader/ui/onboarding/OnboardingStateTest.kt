package com.quire.reader.ui.onboarding

import android.net.TestUri
import android.net.Uri
import com.quire.reader.data.scan.DiscoveryProgress
import com.quire.reader.data.scan.FolderCandidate
import com.quire.reader.data.scan.ScanProgress
import com.quire.reader.data.scan.ScanResult
import com.quire.reader.ui.FakeIndexer
import com.quire.reader.ui.OnboardStep
import com.quire.reader.ui.RecordingNavigator
import com.quire.reader.ui.RecordingToasts
import com.quire.reader.ui.Visit
import com.quire.reader.ui.childScope
import com.quire.reader.ui.settings.RestoreService
import com.quire.reader.ui.settings.RestoreState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingStateTest {
  private class FakeStore : OnboardingStore {
    val writes = mutableListOf<String>()
    var imported = 0
    var rescanResult = ScanResult(0, 0, 0, 0)
    override val scan = MutableStateFlow(ScanProgress())
    override suspend fun setUseCalibre(v: Boolean) { writes += "calibre $v" }
    override suspend fun setWatchNewBooks(v: Boolean) { writes += "watch $v" }
    override suspend fun setOnboardingDone(v: Boolean) { writes += "done $v" }
    override suspend fun addFolder(path: String): Boolean { writes += "folder $path"; return true }
    override suspend fun rescan(): ScanResult { writes += "rescan"; return rescanResult }
    override suspend fun importFiles(uris: List<Uri>) = imported
  }

  private class FakeStorage(var access: Boolean) : StorageAccess {
    val found = listOf(FolderCandidate("Books", "/sdcard/Books", 12), FolderCandidate("Calibre", "/sdcard/Calibre", 40))
    override fun hasAllFilesAccess() = access
    override fun isUsableDirectory(path: String) = path.startsWith("/sdcard")
    override fun displayName(path: String) = path.substringAfterLast('/')
    override suspend fun discover(onFound: (DiscoveryProgress, FolderCandidate?) -> Unit): List<FolderCandidate> {
      found.forEachIndexed { i, c -> onFound(DiscoveryProgress(i + 1, found.size, c.name, c.epubCount), c) }
      return found.sortedByDescending { it.epubCount }
    }
    override suspend fun countEpubs(path: String) = 3
  }

  private val store = FakeStore()
  private val storage = FakeStorage(access = false)
  private val indexer = FakeIndexer()
  private val nav = RecordingNavigator()
  private val toasts = RecordingToasts()

  private fun TestScope.onboarding(initial: OnboardingUiState = OnboardingUiState()): OnboardingState {
    val noRestore = object : RestoreService {
      override suspend fun inspect(uri: Uri) = error("unused")
      override suspend fun stage(uri: Uri, onProgress: (Float?) -> Unit) = Unit
      override suspend fun merge(uri: Uri) = error("unused")
      override suspend fun restart() = Unit
    }
    return OnboardingState(
      initial, store, storage, indexer, RestoreState(noRestore, toasts, backgroundScope, backgroundScope), nav, toasts,
      backgroundScope.childScope(), backgroundScope,
    )
  }

  @Test fun `choosing folders without access asks for it first`() = runTest {
    val onboarding = onboarding()
    onboarding.chooseFolders()
    assertEquals(OnboardStep.Access, onboarding.state.value.onboardStep)
  }

  @Test fun `granting access moves on to the folders, found and picked as they come`() = runTest {
    val onboarding = onboarding(OnboardingUiState(onboardStep = OnboardStep.Access))
    storage.access = true
    onboarding.refreshAccess(); runCurrent()
    val s = onboarding.state.value
    assertEquals(OnboardStep.Folders, s.onboardStep)
    assertNull(s.discovery)
    assertEquals(listOf("/sdcard/Calibre", "/sdcard/Books"), s.candidates.map { it.path })
    assertEquals(setOf("/sdcard/Books", "/sdcard/Calibre"), s.pickedFolders)
    assertEquals(1, indexer.requests)
  }

  @Test fun `access back for an existing library returns to it and rescans`() = runTest {
    store.rescanResult = ScanResult(added = 2, updated = 0, removed = 0, unreadable = 0)
    val onboarding = onboarding(OnboardingUiState(onboardStep = OnboardStep.Access, accessForLibrary = true))
    storage.access = true
    onboarding.refreshAccess(); runCurrent()
    assertEquals(listOf(Visit.Library), nav.visits)
    assertEquals(listOf("2 new books"), toasts.shown)
  }

  @Test fun `the first scan saves the choices and the picked folders, and carries on after onboarding closes`() = runTest {
    val onboarding = onboarding(OnboardingUiState(pickedFolders = setOf("/sdcard/Books"), useCalibre = false))
    onboarding.startScan()
    onboarding.close()
    runCurrent()
    assertEquals(OnboardStep.Scan, onboarding.state.value.onboardStep)
    assertEquals(listOf("calibre false", "watch true", "done true", "folder /sdcard/Books", "rescan"), store.writes)
  }

  @Test fun `a picked folder is added to the list and selected`() = runTest {
    val onboarding = onboarding()
    onboarding.addPickedFolder("/sdcard/Mine"); runCurrent()
    assertEquals(FolderCandidate("Mine", "/sdcard/Mine", 3), onboarding.state.value.candidates.single())
    assertTrue("/sdcard/Mine" in onboarding.state.value.pickedFolders)
    onboarding.addPickedFolder("/data/secret")
    assertEquals("Quire can't read that folder", toasts.shown.last())
  }

  @Test fun `imported books finish onboarding and open the library`() = runTest {
    store.imported = 2
    val onboarding = onboarding()
    onboarding.importFiles(listOf(TestUri("content://a"), TestUri("content://b"))); runCurrent()
    assertEquals(listOf("done true"), store.writes)
    assertEquals(listOf(Visit.Library), nav.visits)
    assertEquals(listOf("Imported 2 books"), toasts.shown)
  }
}
