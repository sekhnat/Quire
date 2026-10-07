package com.quire.reader.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.QuireApplication
import com.quire.reader.ui.library.LibraryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The library sort survives the app closing. A fresh library state holder is what a relaunched app builds, so it must
 * come back with the sort and direction the previous one chose. Runs in the app under test, which must be a throwaway
 * application id.
 */
class LibrarySortPersistenceTest {
  private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as QuireApplication
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private object Nowhere : AppNavigator {
    override fun openLibrary() = Unit
    override fun openLibraryScope(scope: Scope) = Unit
    override fun openDetail(bookId: Long) = Unit
    override fun openReader(request: ReaderRequest) = Unit
    override fun openSettings() = Unit
  }

  @After fun tearDown() = scope.cancel()

  private fun newLibrary(): LibraryState {
    lateinit var library: LibraryState
    InstrumentationRegistry.getInstrumentation().runOnMainSync { library = Features(app).library(Nowhere, {}, scope.childScope()) }
    return library
  }

  private fun chooseOnMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

  private suspend fun reopenedSort(): Pair<SortKey, Boolean> {
    val vm = newLibrary()
    return try {
      withTimeout(5_000) { vm.state.first { it.sort != SortKey.Opened || it.sortAscending }.let { it.sort to it.sortAscending } }
    } catch (e: TimeoutCancellationException) {
      vm.state.value.let { it.sort to it.sortAscending }
    }
  }

  @Test fun `the chosen sort and direction come back after the app is reopened`() = runBlocking {
    val first = newLibrary()
    chooseOnMain { first.pickSort(SortKey.Added); first.setSortAscending(true) }
    delay(500)
    assertEquals(SortKey.Added to true, reopenedSort())

    chooseOnMain { first.pickSort(SortKey.Size); first.flipSort() }
    delay(500)
    assertEquals(SortKey.Size to false, reopenedSort())
  }

  @Test fun `going back to the default sort is remembered too`() = runBlocking {
    val first = newLibrary()
    chooseOnMain { first.pickSort(SortKey.Pages); first.setSortAscending(true) }
    delay(500)
    chooseOnMain { first.pickSort(SortKey.Opened); first.setSortAscending(false) }
    delay(500)
    val vm = newLibrary()
    delay(1_000)
    assertEquals(SortKey.Opened to false, vm.state.value.let { it.sort to it.sortAscending })
  }
}
