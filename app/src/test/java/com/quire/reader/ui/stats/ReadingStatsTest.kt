package com.quire.reader.ui.stats

import com.quire.reader.data.db.ReadingSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReadingStatsTest {
  private val utc: ZoneId = ZoneOffset.UTC
  private val minute = 60_000L

  private fun at(date: String, time: String = "12:00", zone: ZoneId = utc) =
    LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

  private fun session(date: String, minutes: Long, book: Long = 1, time: String = "12:00", read: Float = 0f, zone: ZoneId = utc) =
    ReadingSessionEntity(bookId = book, startedAt = at(date, time, zone), endedAt = at(date, time, zone) + minutes * minute, activeMs = minutes * minute, startProgress = 0f, endProgress = 0f, readProgress = read)

  /** Saturday 31 October 2026. */
  private val now = at("2026-10-31")

  private fun summarize(vararg sessions: ReadingSessionEntity, finished: List<Long> = emptyList(), pages: Int = 0, zone: ZoneId = utc, at: Long = now) =
    ReadingStats.summarize(sessions.toList(), finished, { pages }, at, zone, DayOfWeek.MONDAY)

  @Test fun `no sessions is an empty summary with a full row of empty days`() {
    val s = summarize()
    assertTrue(s.isEmpty)
    assertEquals(0, s.currentStreak)
    assertEquals(30, s.lastDays.size)
    assertTrue(s.lastDays.all { it.ms == 0L })
  }

  @Test fun `today, this week, month and year each take only their own days`() {
    val s = summarize(
      session("2026-10-31", 30), session("2026-10-27", 20), session("2026-10-20", 10), session("2026-01-05", 5), session("2025-12-31", 3),
    )
    assertEquals(30 * minute, s.todayMs)
    assertEquals(50 * minute, s.weekMs)    // Monday 26 October onwards
    assertEquals(60 * minute, s.monthMs)
    assertEquals(65 * minute, s.yearMs)
    assertEquals(68 * minute, s.totalMs)
  }

  @Test fun `the week starts on the day it is told to`() {
    val sunday = ReadingStats.summarize(listOf(session("2026-10-25", 10), session("2026-10-31", 5)), emptyList(), { 0 }, now, utc, DayOfWeek.SUNDAY)
    assertEquals(15 * minute, sunday.weekMs)
    val monday = ReadingStats.summarize(listOf(session("2026-10-25", 10), session("2026-10-31", 5)), emptyList(), { 0 }, now, utc, DayOfWeek.MONDAY)
    assertEquals(5 * minute, monday.weekMs)
  }

  @Test fun `the chart runs oldest first and ends on today`() {
    val s = summarize(session("2026-10-31", 12), session("2026-10-02", 7), session("2026-10-01", 9))
    assertEquals(LocalDate.of(2026, 10, 2), s.lastDays.first().date)
    assertEquals(7 * minute, s.lastDays.first().ms)
    assertEquals(LocalDate.of(2026, 10, 31), s.lastDays.last().date)
    assertEquals(12 * minute, s.lastDays.last().ms)
  }

  @Test fun `a streak counts consecutive days with at least a minute`() {
    val s = summarize(session("2026-10-31", 5), session("2026-10-30", 5), session("2026-10-29", 1), session("2026-10-27", 40))
    assertEquals(3, s.currentStreak)
    assertEquals(3, s.longestStreak)
  }

  @Test fun `a day under a minute does not count`() {
    val s = summarize(session("2026-10-31", 5), ReadingSessionEntity(bookId = 1, startedAt = at("2026-10-30"), endedAt = at("2026-10-30"), activeMs = 59_000, startProgress = 0f, endProgress = 0f, readProgress = 0f))
    assertEquals(1, s.currentStreak)
  }

  @Test fun `a streak is still alive when today has no reading yet, and dead after two empty days`() {
    assertEquals(2, summarize(session("2026-10-30", 5), session("2026-10-29", 5)).currentStreak)
    assertEquals(0, summarize(session("2026-10-29", 5), session("2026-10-28", 5)).currentStreak)
  }

  @Test fun `the longest streak can be an old one`() {
    val s = summarize(session("2026-10-31", 5), session("2026-10-10", 5), session("2026-10-11", 5), session("2026-10-12", 5), session("2026-10-13", 5))
    assertEquals(1, s.currentStreak)
    assertEquals(4, s.longestStreak)
  }

  @Test fun `several sessions in a day add up to that day`() {
    val s = summarize(session("2026-10-31", 20, time = "08:00"), session("2026-10-31", 25, time = "21:00"))
    assertEquals(45 * minute, s.todayMs)
    assertEquals(45 * minute, s.lastDays.last().ms)
  }

  @Test fun `a session belongs to the day it started in the reader's own time zone`() {
    val east = ZoneOffset.ofHours(2)
    // 23:30 UTC on the 30th is 01:30 on the 31st at UTC+2.
    val late = session("2026-10-30", 10, time = "23:30")
    val s = summarize(late, zone = east, at = at("2026-10-31", "12:00", east))
    assertEquals(10 * minute, s.todayMs)
    assertEquals(0L, summarize(late, zone = utc).todayMs)
  }

  @Test fun `only books finished this year count as finished this year`() {
    val s = summarize(finished = listOf(at("2026-02-01"), at("2026-10-30"), at("2025-12-31")))
    assertEquals(2, s.finishedThisYear)
  }

  @Test fun `pace is pages an hour over the recent reading, and needs enough of it`() {
    // One hour with a tenth of a 300-page book read page by page.
    assertEquals(30, summarize(session("2026-10-30", 60, read = 0.1f), pages = 300).pagesPerHour)
    assertNull(summarize(session("2026-10-30", 5, read = 0.1f), pages = 300).pagesPerHour)
    assertNull(summarize(session("2026-10-30", 60, read = 0.1f), pages = 0).pagesPerHour)
    // Reading from before the three-month window does not count.
    assertNull(summarize(session("2026-06-01", 60, read = 0.1f), pages = 300).pagesPerHour)
  }

  @Test fun `the most read books come first, at most five, with their time`() {
    val s = summarize(
      session("2026-10-31", 10, book = 1), session("2026-10-30", 30, book = 2), session("2026-10-29", 20, book = 2), session("2026-10-28", 5, book = 3),
      session("2026-10-27", 4, book = 4), session("2026-10-26", 3, book = 5), session("2026-10-25", 2, book = 6),
    )
    assertEquals(listOf(2L, 1L, 3L, 4L, 5L), s.topBooks.map { it.bookId })
    assertEquals(50 * minute, s.topBooks.first().ms)
  }
}
