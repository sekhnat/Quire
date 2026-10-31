package com.quire.reader.ui.stats

import com.quire.reader.data.db.ReadingSessionEntity
import com.quire.reader.ui.AppNavigator
import com.quire.reader.ui.LibraryData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** What the Stats screen needs from the data layer (`LibraryRepository`). */
interface StatsStore {
  val sessions: Flow<List<ReadingSessionEntity>>
  /** When each finished book was finished, epoch millis. */
  val finishedAt: Flow<List<Long>>
}

/** [summary] is null until the first sessions have been read; [titles] names the books in the summary's top list. */
data class StatsUi(val summary: ReadingSummary? = null, val titles: Map<Long, String> = emptyMap())

/**
 * The Stats screen: reading time, streaks and pace over the recorded sessions, recomputed whenever a session is added
 * or the library changes. Made when the screen is opened and [close]d when it is left.
 */
class StatsState(
  store: StatsStore,
  library: Flow<LibraryData>,
  private val nav: AppNavigator,
  private val scope: CoroutineScope,
  now: () -> Long = System::currentTimeMillis,
  zone: () -> ZoneId = ZoneId::systemDefault,
  firstDayOfWeek: () -> DayOfWeek = { WeekFields.of(Locale.getDefault()).firstDayOfWeek },
) {
  val ui: StateFlow<StatsUi> = combine(store.sessions, store.finishedAt, library) { sessions, finishedAt, lib ->
    val summary = ReadingStats.summarize(sessions, finishedAt, { lib.byId[it]?.pages ?: 0 }, now(), zone(), firstDayOfWeek())
    StatsUi(summary, summary.topBooks.mapNotNull { top -> lib.byId[top.bookId]?.let { top.bookId to it.title } }.toMap())
  }.stateIn(scope, SharingStarted.Eagerly, StatsUi())

  fun leave() = nav.openLibrary()

  fun close() = scope.cancel()
}
