package com.quire.reader.ui.stats

import com.quire.reader.data.db.ReadingSessionEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class DayTime(val date: LocalDate, val ms: Long)
data class BookTime(val bookId: Long, val ms: Long)

data class ReadingSummary(
  val todayMs: Long,
  val weekMs: Long,
  val monthMs: Long,
  val yearMs: Long,
  val totalMs: Long,
  /** The last [ReadingStats.CHART_DAYS] days, oldest first, today last; days without reading are 0. */
  val lastDays: List<DayTime>,
  val currentStreak: Int,
  val longestStreak: Int,
  val finishedThisYear: Int,
  /** How fast the reader gets through pages, over the last three months; null until there is enough to say. */
  val pagesPerHour: Int?,
  val topBooks: List<BookTime>,
) {
  val isEmpty: Boolean get() = totalMs == 0L
}

/** Everything the Stats screen shows, worked out from the recorded sessions with no database or clock of its own. */
object ReadingStats {
  const val CHART_DAYS = 30
  /** A day counts toward a streak with at least this much reading. */
  const val STREAK_DAY_MS = 60_000L
  private const val PACE_WINDOW_DAYS = 90L
  private const val PACE_MIN_MS = 10 * 60_000L
  private const val TOP_BOOKS = 5

  /**
   * @param finishedAt when each finished book was finished, epoch millis
   * @param pagesOf the page count of a book, 0 when unknown, for the pace
   * @param zone decides which day a session belongs to: the day it started, in this zone
   */
  fun summarize(
    sessions: List<ReadingSessionEntity>,
    finishedAt: List<Long>,
    pagesOf: (Long) -> Int,
    now: Long,
    zone: ZoneId,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
  ): ReadingSummary {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    val byDay = HashMap<LocalDate, Long>()
    sessions.forEach { byDay.merge(dayOf(it.startedAt), it.activeMs, Long::plus) }
    fun since(start: LocalDate) = byDay.entries.sumOf { (day, ms) -> if (day >= start) ms else 0L }

    val readingDays = byDay.filterValues { it >= STREAK_DAY_MS }.keys
    val (current, longest) = streaks(readingDays, today)

    val paceFrom = now - PACE_WINDOW_DAYS * DAY_MS
    val recent = sessions.filter { it.startedAt >= paceFrom && pagesOf(it.bookId) > 0 }
    val recentMs = recent.sumOf { it.activeMs }
    val pages = recent.sumOf { it.readProgress.toDouble() * pagesOf(it.bookId) }
    val pace = if (recentMs >= PACE_MIN_MS) (pages / (recentMs / HOUR_MS.toDouble())).toInt().takeIf { it > 0 } else null

    return ReadingSummary(
      todayMs = byDay[today] ?: 0L,
      weekMs = since(today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))),
      monthMs = since(today.withDayOfMonth(1)),
      yearMs = since(today.withDayOfYear(1)),
      totalMs = sessions.sumOf { it.activeMs },
      lastDays = (CHART_DAYS - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { DayTime(it, byDay[it] ?: 0L) } },
      currentStreak = current,
      longestStreak = longest,
      finishedThisYear = finishedAt.count { dayOf(it).year == today.year },
      pagesPerHour = pace,
      topBooks = sessions.groupBy { it.bookId }.map { (id, list) -> BookTime(id, list.sumOf { it.activeMs }) }
        .filter { it.ms > 0 }.sortedByDescending { it.ms }.take(TOP_BOOKS),
    )
  }

  /** The current streak (still alive if the last reading day was yesterday) and the longest one ever. */
  private fun streaks(days: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
    var current = 0
    var day = if (today in days) today else today.minusDays(1)
    while (day in days) { current++; day = day.minusDays(1) }
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (d in days.sorted()) {
      run = if (previous != null && d == previous.plusDays(1)) run + 1 else 1
      longest = maxOf(longest, run)
      previous = d
    }
    return current to longest
  }

  private const val DAY_MS = 24 * 3_600_000L
  private const val HOUR_MS = 3_600_000L
}
