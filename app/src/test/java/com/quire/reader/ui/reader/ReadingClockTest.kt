package com.quire.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingClockTest {
  private var time = 1_000_000L
  private val clock = ReadingClock({ time }, positions = 100)

  private fun advance(ms: Long) { time += ms }

  @Test fun `time between coming to the front and leaving is the session`() {
    clock.resume(0.10f)
    advance(90_000); clock.moved(0.11f)
    advance(60_000)
    val span = clock.pause()!!
    assertEquals(1_000_000L, span.startedAt)
    assertEquals(1_150_000L, span.endedAt)
    assertEquals(150_000L, span.activeMs)
    assertEquals(0.10f, span.startProgress, 0f)
    assertEquals(0.11f, span.endProgress, 0f)
  }

  @Test fun `a gap longer than the idle cap counts only up to the cap`() {
    clock.resume(0f)
    advance(10 * 60_000); clock.moved(0.01f)
    advance(20_000)
    assertEquals(ReadingClock.IDLE_CAP_MS + 20_000, clock.pause()!!.activeMs)
  }

  @Test fun `a book left open and then closed adds minutes, not hours`() {
    clock.resume(0f)
    advance(5 * 3_600_000L)
    assertEquals(ReadingClock.IDLE_CAP_MS, clock.pause()!!.activeMs)
  }

  @Test fun `a visit under thirty seconds is not a session, and the next one starts fresh`() {
    clock.resume(0f)
    advance(29_000)
    assertNull(clock.pause())
    clock.resume(0f)
    advance(40_000)
    assertEquals(40_000L, clock.pause()!!.activeMs)
  }

  @Test fun `only forward page by page steps count as reading, not jumps or going back`() {
    clock.resume(0.00f)
    advance(1_000); clock.moved(0.01f)   // one page on
    advance(1_000); clock.moved(0.50f)   // a jump through the contents
    advance(1_000); clock.moved(0.51f)   // one page on
    advance(1_000); clock.moved(0.50f)   // back a page
    advance(40_000)
    assertEquals(0.02f, clock.pause()!!.readProgress, 1e-5f)
  }

  @Test fun `a move made while away, such as restoring the place, is not reading`() {
    clock.moved(0.40f)
    clock.resume(0.40f)
    advance(40_000); clock.moved(0.41f)
    val span = clock.pause()!!
    assertEquals(0.40f, span.startProgress, 0f)
    assertEquals(0.01f, span.readProgress, 1e-5f)
  }

  @Test fun `resuming twice or pausing twice changes nothing`() {
    clock.resume(0f)
    advance(20_000)
    clock.resume(0.5f)
    advance(20_000)
    assertEquals(40_000L, clock.pause()!!.activeMs)
    assertNull(clock.pause())
  }

  @Test fun `a book with few pages treats a bigger share of the book as one step`() {
    val short = ReadingClock({ time }, positions = 10)
    short.resume(0f)
    advance(40_000); short.moved(0.4f)  // four pages of ten
    assertNotNull(short.pause())
    short.resume(0f)
    advance(40_000); short.moved(0.4f)
    assertEquals(0.4f, short.pause()!!.readProgress, 1e-5f)
  }
}
