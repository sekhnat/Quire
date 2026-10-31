package com.quire.reader.ui.stats

import com.quire.reader.data.db.ReadingSessionEntity
import com.quire.reader.ui.LibraryData
import com.quire.reader.ui.RecordingNavigator
import com.quire.reader.ui.Visit
import com.quire.reader.ui.childScope
import com.quire.reader.ui.testBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class StatsStateTest {
  private class FakeStats : StatsStore {
    val all = MutableStateFlow(emptyList<ReadingSessionEntity>())
    override val sessions = all
    override val finishedAt = flowOf(emptyList<Long>())
  }

  private val store = FakeStats()
  private val nav = RecordingNavigator()
  private val library = flowOf(LibraryData(listOf(testBook(1, "Emma"), testBook(2, "Persuasion")), emptyList()))
  private val now = 1_790_000_000_000L

  private fun TestScope.stats() = StatsState(store, library, nav, backgroundScope.childScope(), { now }, { ZoneOffset.UTC }, { DayOfWeek.MONDAY })

  private fun session(book: Long, minutes: Long) =
    ReadingSessionEntity(bookId = book, startedAt = now - 1_000, endedAt = now, activeMs = minutes * 60_000, startProgress = 0f, endProgress = 0f, readProgress = 0f)

  @Test fun `with no sessions the summary is empty`() = runTest {
    val stats = stats()
    runCurrent()
    assertEquals(true, stats.ui.value.summary!!.isEmpty)
  }

  @Test fun `a recorded session shows up, and the most read book is named`() = runTest {
    val stats = stats()
    runCurrent()
    store.all.value = listOf(session(1, 10), session(2, 25))
    runCurrent()
    val ui = stats.ui.value
    assertEquals(35 * 60_000L, ui.summary!!.todayMs)
    assertEquals(listOf(2L, 1L), ui.summary!!.topBooks.map { it.bookId })
    assertEquals(mapOf(2L to "Persuasion", 1L to "Emma"), ui.titles)
  }

  @Test fun `a book that left the library is still counted but has no title`() = runTest {
    val stats = stats()
    store.all.value = listOf(session(99, 10))
    runCurrent()
    assertEquals(10 * 60_000L, stats.ui.value.summary!!.totalMs)
    assertNull(stats.ui.value.titles[99L])
  }

  @Test fun `leaving goes back to the library`() = runTest {
    stats().leave()
    assertEquals(listOf<Any>(Visit.Library), nav.visits)
  }
}
