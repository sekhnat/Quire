package com.quire.reader.ui.library

import com.quire.reader.data.Book
import com.quire.reader.data.FileOpResult
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextStatusFilter
import com.quire.reader.reader.STALE_TARGET_MESSAGE
import com.quire.reader.ui.BulkSheet
import com.quire.reader.ui.FakeIndexer
import com.quire.reader.ui.FakeLibraryPrefs
import com.quire.reader.ui.FakeLibraryStore
import com.quire.reader.ui.LibFilter
import com.quire.reader.ui.LibLayout
import com.quire.reader.ui.LibView
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStateTest {
  private val store = FakeLibraryStore(listOf(testBook(1, "Emma"), testBook(2, "Persuasion")))
  private val indexer = FakeIndexer()
  private val nav = RecordingNavigator()
  private val toasts = RecordingToasts()

  /** The books handed to the share sheet; [shareable] is whether an app takes them. */
  private val shared = mutableListOf<List<Long>>()
  private var shareable = true

  private fun TestScope.library(prefs: FakeLibraryPrefs = FakeLibraryPrefs(), access: Boolean = true, scope: CoroutineScope = backgroundScope) =
    LibraryState(store, prefs, indexer, nav, toasts, { access }, scope, compute = EmptyCoroutineContext, shareFiles = { shared += it.map(Book::id); shareable })

  /** A library with [ids] selected, the first by a long press. */
  private fun TestScope.selecting(vararg ids: Long, access: Boolean = true): LibraryState {
    val library = library(access = access)
    runCurrent()
    library.startSelection(ids.first())
    ids.drop(1).forEach(library::toggleSelected)
    return library
  }

  @Test fun `the saved sort and layout are applied at start`() = runTest {
    val library = library(FakeLibraryPrefs(layout = "List", sort = "Added", ascending = true))
    runCurrent()
    val s = library.state.value
    assertEquals(SortKey.Added to true, s.sort to s.sortAscending)
    assertEquals(LibLayout.List, s.layout)
    assertEquals(2, library.data.value.books.size)
  }

  @Test fun `the book list follows the query and the books, not the layout or view`() = runTest {
    val library = library()
    backgroundScope.launch { library.visible.collect {} }
    runCurrent()
    assertEquals(listOf(2L, 1L), library.visible.value.books.map { it.id })
    library.setQuery("emma"); runCurrent()
    assertEquals(listOf(1L), library.visible.value.books.map { it.id })
    val before = library.visible.value
    library.cycleLayout(); library.setView(LibView.Authors); library.openSort(true); runCurrent()
    assertSame(before, library.visible.value)
    store.books.value = store.books.value + testBook(3, "Emma Again"); runCurrent()
    assertEquals(listOf(3L, 1L), library.visible.value.books.map { it.id })
  }

  @Test fun `searching on the Tags view stays there, and on any other view shows the books`() = runTest {
    val library = library()
    library.setView(LibView.Tags)
    library.setQuery("fant")
    assertEquals(LibView.Tags to "fant", library.state.value.let { it.view to it.query })
    library.setView(LibView.Authors)
    library.setQuery("emma")
    assertEquals(LibView.Books to "emma", library.state.value.let { it.view to it.query })
  }

  @Test fun `a view's groups are only worked out while it is observed`() = runTest {
    val library = library()
    runCurrent()
    assertNull(library.authors.value)
    backgroundScope.launch { library.authors.collect {} }
    runCurrent()
    assertEquals(listOf('A'), library.authors.value?.value?.map { it.first })
    assertNull(library.shelves.value)
    assertNull(library.series.value)
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
    library.openBook(2); library.read(1); library.openSettings(); library.openStats()
    assertEquals(listOf(Visit.Detail(2), ReaderRequest(1), Visit.Settings, Visit.Stats), nav.visits)
  }

  @Test fun `the inside-books search runs with the library filter`() = runTest {
    val library = library()
    backgroundScope.launch { library.textSearch.collect {} }
    library.setFilter(LibFilter.Unread)
    library.toggleSearch(); library.setSearchScope(SearchScope.Text); library.setTextLibraryQuery("pemberley")
    runCurrent(); advanceTimeBy(300); runCurrent()
    assertEquals(listOf(TextSearchFilters(status = TextStatusFilter.Unread)), store.searches)
  }

  @Test fun `hiding a tag takes its books out of the library and the inside-books search at once`() = runTest {
    store.books.value = listOf(testBook(1, "Emma", tags = listOf("Private")), testBook(2, "Persuasion"))
    val prefs = FakeLibraryPrefs()
    val library = library(prefs)
    backgroundScope.launch { library.textSearch.collect {} }
    runCurrent()
    assertEquals(listOf(1L, 2L), library.data.value.books.map { it.id })
    prefs.hiddenTags.value = setOf("Private"); runCurrent()
    assertEquals(listOf(2L), library.data.value.books.map { it.id })
    assertEquals("Emma", library.data.value.byId[1L]?.title)
    library.toggleSearch(); library.setSearchScope(SearchScope.Text); library.setTextLibraryQuery("pemberley")
    runCurrent(); advanceTimeBy(300); runCurrent()
    assertEquals(listOf(TextSearchFilters(hiddenTags = setOf("Private"))), store.searches)
  }

  @Test fun `the word count setting reaches every screen through the library data`() = runTest {
    val prefs = FakeLibraryPrefs()
    val library = library(prefs)
    runCurrent()
    assertEquals(false, library.data.value.showWords)
    prefs.showWordCount.value = true; runCurrent()
    assertEquals(true, library.data.value.showWords)
  }

  @Test fun `a saved sort by words falls back to the default while word counts are off`() = runTest {
    val library = library(FakeLibraryPrefs(sort = "Words"))
    runCurrent()
    assertEquals(SortKey.Opened, library.state.value.sort)
  }

  @Test fun `a saved sort by words is kept while word counts are on, and dropped when they are turned off`() = runTest {
    val prefs = FakeLibraryPrefs(sort = "Words", showWordCount = MutableStateFlow(true))
    val library = library(prefs)
    runCurrent()
    assertEquals(SortKey.Words, library.state.value.sort)
    prefs.showWordCount.value = false; runCurrent()
    assertEquals(SortKey.Opened, library.state.value.sort)
    assertEquals(emptyList<Pair<String, Boolean>>(), prefs.savedSorts)
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

  // ── selecting books ────────────────────────────────────────────────────────

  @Test fun `a long press starts selecting, taps pick and unpick, and unpicking the last ends it`() = runTest {
    val library = selecting(1)
    assertEquals(setOf(1L), library.state.value.selected)
    library.toggleSelected(2)
    assertEquals(setOf(1L, 2L), library.state.value.selected)
    library.toggleSelected(1); library.toggleSelected(2)
    assertEquals(false, library.state.value.selecting)
    assertTrue(nav.visits.isEmpty())
  }

  @Test fun `select all picks the books shown, and then none`() = runTest {
    val library = selecting(1)
    backgroundScope.launch { library.visible.collect {} }
    library.setQuery("persuasion"); runCurrent()
    library.toggleSelectAll()
    assertEquals(setOf(1L, 2L), library.state.value.selected)
    library.setQuery(""); runCurrent()
    library.toggleSelectAll()
    assertEquals(false, library.state.value.selecting)
  }

  @Test fun `another view ends selecting, and a sheet needs a selection`() = runTest {
    val library = selecting(1, 2)
    library.openBulk(BulkSheet.Info)
    library.setView(LibView.Authors)
    assertEquals(false, library.state.value.selecting)
    assertNull(library.state.value.bulkSheet)
    library.openBulk(BulkSheet.Tag)
    assertNull(library.state.value.bulkSheet)
  }

  @Test fun `a selected book that leaves the library leaves the selection`() = runTest {
    val library = selecting(1, 2)
    store.books.value = listOf(testBook(2, "Persuasion")); runCurrent()
    assertEquals(setOf(2L), library.state.value.selected)
    store.books.value = emptyList(); runCurrent()
    assertEquals(false, library.state.value.selecting)
  }

  @Test fun `tagging adds the tag to every selected book and ends selecting`() = runTest {
    val library = selecting(1, 2)
    library.openBulk(BulkSheet.Tag)
    library.tagSelected("  "); runCurrent()
    assertTrue(store.bulk.isEmpty())
    library.tagSelected(" Austen "); runCurrent()
    assertEquals(listOf("tag [1, 2] Austen"), store.bulk)
    assertEquals(listOf("Tagged 2 books “Austen”"), toasts.shown)
    assertEquals(false, library.state.value.selecting)
    assertNull(library.state.value.bulkSheet)
  }

  @Test fun `sending hands the selected books to the share sheet`() = runTest {
    val library = selecting(2, 1)
    library.sendSelected()
    assertEquals(listOf(listOf(1L, 2L)), shared)
    assertEquals(false, library.state.value.selecting)
  }

  @Test fun `sending with no app to take the files says so and keeps the selection`() = runTest {
    shareable = false
    val library = selecting(1)
    library.sendSelected()
    assertEquals(listOf("No app can receive these files"), toasts.shown)
    assertEquals(setOf(1L), library.state.value.selected)
  }

  @Test fun `copying reports how many books went to the folder`() = runTest {
    store.fileResult = FileOpResult(done = 1, failed = 1)
    val library = selecting(1, 2)
    library.openBulk(BulkSheet.Copy)
    library.copySelected("/storage/emulated/0/Download/"); runCurrent()
    assertEquals(listOf("copy [1, 2] /storage/emulated/0/Download/"), store.bulk)
    assertEquals(listOf("Copying 2 books…", "Copied 1 book to Download · 1 couldn’t be copied"), toasts.shown)
    assertEquals(false, library.state.value.selecting)
  }

  @Test fun `closing the copy folder picker keeps the selection`() = runTest {
    val library = selecting(1)
    library.openBulk(BulkSheet.Copy)
    library.copySelected(null); runCurrent()
    assertTrue(store.bulk.isEmpty())
    assertEquals(setOf(1L), library.state.value.selected)
    assertNull(library.state.value.bulkSheet)
  }

  @Test fun `removing takes the selected books out of the library`() = runTest {
    val library = selecting(1)
    library.openBulk(BulkSheet.ConfirmRemove)
    library.removeSelected(); runCurrent()
    assertEquals(listOf("remove [1]"), store.bulk)
    assertEquals(listOf("Removed 1 book from the library · bring it back in Settings"), toasts.shown)
    assertEquals(false, library.state.value.selecting)
  }

  @Test fun `deleting reports the files deleted`() = runTest {
    store.fileResult = FileOpResult(done = 2, failed = 0)
    val library = selecting(1, 2)
    library.deleteSelected(); runCurrent()
    assertEquals(listOf("delete [1, 2]"), store.bulk)
    assertEquals(listOf("Deleted 2 books"), toasts.shown)
  }

  @Test fun `files that could not be deleted without file access ask for it`() = runTest {
    store.fileResult = FileOpResult(done = 0, failed = 1)
    val library = selecting(1, access = false)
    library.deleteSelected(); runCurrent()
    assertEquals(listOf("Allow access to your files first"), toasts.shown)
  }
}
