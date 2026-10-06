package com.quire.reader.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.QuireApplication
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The library sort survives the app closing. A fresh view model is what a relaunched app builds, so it must come back with
 * the sort and direction the previous one chose. Runs in the app under test, which must be a throwaway application id.
 */
class LibrarySortPersistenceTest {
  private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as QuireApplication

  private fun newViewModel(): QuireViewModel {
    lateinit var vm: QuireViewModel
    InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = QuireViewModel(app) }
    return vm
  }

  private fun chooseOnMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

  private suspend fun reopenedSort(): Pair<SortKey, Boolean> {
    val vm = newViewModel()
    return try {
      withTimeout(5_000) { vm.state.first { it.sort != SortKey.Opened || it.sortAscending }.let { it.sort to it.sortAscending } }
    } catch (e: TimeoutCancellationException) {
      vm.state.value.let { it.sort to it.sortAscending }
    }
  }

  @Test fun `the chosen sort and direction come back after the app is reopened`() = runBlocking {
    val first = newViewModel()
    chooseOnMain { first.pickSort(SortKey.Added); first.setSortAscending(true) }
    delay(500)
    assertEquals(SortKey.Added to true, reopenedSort())

    chooseOnMain { first.pickSort(SortKey.Size); first.flipSort() }
    delay(500)
    assertEquals(SortKey.Size to false, reopenedSort())
  }

  @Test fun `going back to the default sort is remembered too`() = runBlocking {
    val first = newViewModel()
    chooseOnMain { first.pickSort(SortKey.Pages); first.setSortAscending(true) }
    delay(500)
    chooseOnMain { first.pickSort(SortKey.Opened); first.setSortAscending(false) }
    delay(500)
    val vm = newViewModel()
    delay(1_000)
    assertEquals(SortKey.Opened to false, vm.state.value.let { it.sort to it.sortAscending })
  }
}
