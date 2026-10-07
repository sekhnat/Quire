package com.quire.reader.ui.library

import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextStatusFilter
import com.quire.reader.reader.STALE_TARGET_MESSAGE
import com.quire.reader.ui.FakeIndexer
import com.quire.reader.ui.FakeLibraryPrefs
import com.quire.reader.ui.FakeLibraryStore
import com.quire.reader.ui.LibFilter
import com.quire.reader.ui.LibLayout
import com.quire.reader.ui.ReaderRequest
import com.quire.reader.ui.RecordingNavigator
import com.quire.reader.ui.RecordingToasts
import com.quire.reader.ui.SearchScope
import com.quire.reader.ui.SortKey
import com.quire.reader.ui.testBook
import com.quire.reader.ui.testTarget
import com.quire.reader.ui.Visit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStateTest {
  private val store = FakeLibraryStore(listOf(testBook(1, "Emma"), testBook(2, "Persuasion")))
  private val indexer = FakeIndexer()
  private val nav = RecordingNavigator()
  private val toasts = RecordingToasts()

  private fun TestScope.library(prefs: FakeLibraryPrefs = FakeLibraryPrefs(), access: Boolean = true, scope: CoroutineScope = backgroundScope) =
    LibraryState(store, prefs, indexer, nav, toasts, { access }, scope)

  @Test fun `the saved sort and layout are applied at start`() = runTest {
    val library = library(FakeLibraryPrefs(layout = "List", sort = "Added", ascending = true))
    runCurrent()
    val s = library.state.value
    assertEquals(SortKey.Added to true, s.sort to s.sortAscending)
    assertEquals(LibLayout.List, s.layout)
    assertEquals(2, library.data.value.books.size)
  }

  @Test fun `a sort picked while the saved one is loading wins and is saved`() = runTest {
    val release = CompletableDeferred<Unit>()
    val prefs = FakeLibraryPrefs(sort = "Pages", ascending = true, librarySort = flow { release.await(); emit("Pages") })
    val library = library(prefs)
    runCurrent()
    library.pickSort(SortKey.Size)
    release.complete(Unit); runCurrent()
    assertEquals(SortKey.Size to false, library.state.value.let { it.sort to it.sortAscending })
    assertEquals(listOf("Size" to false), prefs.savedSorts)
  }

  @Test fun `cycling the layout saves it and says which one it is`() = runTest {
    val prefs = FakeLibraryPrefs()
    val library = library(prefs)
    runCurrent()
    library.cycleLayout(); runCurrent()
    assertEquals(LibLayout.List, library.state.value.layout)
    assertEquals("List", prefs.libraryLayout.value)
    assertEquals(listOf("Dense list layout"), toasts.shown)
  }

  @Test fun `a stale text hit is not opened and asks for the book to be indexed again`() = runTest {
    val library = library()
    store.current = false
    library.openTextHit(testTarget(1)); runCurrent()
    assertEquals(listOf(STALE_TARGET_MESSAGE), toasts.shown)
    assertEquals(1, indexer.requests)
    assertTrue(nav.visits.isEmpty())
  }

  @Test fun `a current text hit opens the reader at the passage`() = runTest {
    val library = library()
    library.openTextHit(testTarget(2)); runCurrent()
    assertEquals(listOf(ReaderRequest(2, target = testTarget(2))), nav.visits)
  }

  @Test fun `show all in this book opens the reader in library-search mode`() = runTest {
    library().openBookSearch(1, "pemberley")
    assertEquals(listOf(ReaderRequest(1, libraryQuery = "pemberley")), nav.visits)
  }

  @Test fun `browsing actions go through the navigator`() = runTest {
    val library = library()
    library.openBook(2); library.read(1); library.openSettings()
    assertEquals(listOf(Visit.Detail(2), ReaderRequest(1), Visit.Settings), nav.visits)
  }

  @Test fun `the inside-books search runs with the library filter`() = runTest {
    val library = library()
    backgroundScope.launch { library.textSearch.collect {} }
    library.setFilter(LibFilter.Unread)
    library.toggleSearch(); library.setSearchScope(SearchScope.Text); library.setTextLibraryQuery("pemberley")
    runCurrent(); advanceTimeBy(300); runCurrent()
    assertEquals(listOf(TextSearchFilters(status = TextStatusFilter.Unread)), store.searches)
  }

  @Test fun `a rescan without file access only asks for it`() = runTest {
    val library = library(access = false)
    library.rescan(); runCurrent()
    assertEquals(listOf("Allow access to your files first"), toasts.shown)
    assertEquals(0, store.rescans)
  }

  @Test fun `a rescan reports what changed`() = runTest {
    val library = library()
    library.openImport(true)
    library.rescan(); runCurrent()
    assertEquals(listOf("Scanning…", "Library is up to date"), toasts.shown)
    assertEquals(false, library.state.value.importOpen)
  }
}
