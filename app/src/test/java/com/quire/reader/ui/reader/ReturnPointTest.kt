package com.quire.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** When the "Back to page N" chip goes away: more than three pages from where the link led, never before landing. */
class ReturnPointTest {
  private val origin = "chapter1.xhtml#p12"

  @Test
  fun `a report from the origin before the jump lands keeps waiting`() {
    val point = ReturnPoint(origin, page = 34)
    assertSame(point, point.movedTo(34))
  }

  @Test
  fun `the first report away from the origin is the landing`() {
    assertEquals(120, ReturnPoint(origin, page = 34).movedTo(120)?.landedAt)
  }

  @Test
  fun `staying within three pages of the landing keeps the chip`() {
    val landed = ReturnPoint(origin, page = 34, landedAt = 120)
    listOf(117, 119, 120, 121, 123).forEach { assertEquals(landed, landed.movedTo(it)) }
  }

  @Test
  fun `moving more than three pages from the landing in either direction clears it`() {
    val landed = ReturnPoint(origin, page = 34, landedAt = 120)
    assertNull(landed.movedTo(124))
    assertNull(landed.movedTo(116))
  }

  @Test
  fun `the label names the origin page`() {
    assertEquals("Back to page 34", ReturnPoint(origin, page = 34).label)
  }
}
