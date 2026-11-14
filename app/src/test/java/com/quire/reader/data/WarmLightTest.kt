package com.quire.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The warm light and its schedule (spec feature 5). */
class WarmLightTest {
  private fun at(h: Int, m: Int = 0) = h * 60 + m
  private val night = WarmSchedule(startMinute = at(21), endMinute = at(7))

  @Test
  fun `a window crossing midnight`() {
    assertTrue(night.contains(at(21)))
    assertTrue(night.contains(at(23, 59)))
    assertTrue(night.contains(at(0)))
    assertTrue(night.contains(at(6, 59)))
    assertFalse(night.contains(at(7)))
    assertFalse(night.contains(at(12)))
    assertFalse(night.contains(at(20, 59)))
  }

  @Test
  fun `a window within the day, and an empty one`() {
    val day = WarmSchedule(at(9), at(17))
    assertTrue(day.contains(at(9)))
    assertFalse(day.contains(at(17)))
    assertFalse(WarmSchedule(at(21), at(21)).contains(at(21)))
    assertFalse(WarmSchedule(at(21), at(21)).contains(at(3)))
  }

  @Test
  fun `minutes to the next change`() {
    assertEquals(60, night.minutesToNextChange(at(20)))
    assertEquals(600, night.minutesToNextChange(at(21)))
    assertEquals(1, night.minutesToNextChange(at(6, 59)))
    assertEquals(840, night.minutesToNextChange(at(7)))
  }

  @Test
  fun `without a schedule the switch decides`() {
    assertFalse(WarmLight().isActive(0, at(22)))
    assertTrue(WarmLight().toggled(0, at(12)).isActive(0, at(12)))
  }

  @Test
  fun `the switch overrides the schedule until its next change`() {
    val light = WarmLight(schedule = night)
    // 22:00 on day 0, in milliseconds since that midnight.
    val ten = at(22) * 60_000L
    assertTrue(light.isActive(ten, at(22)))
    val off = light.toggled(ten, at(22))
    assertFalse(off.isActive(ten, at(22)))
    assertFalse(off.isActive(ten + 8 * 3_600_000L, at(6)))
    // At 07:00 the schedule's next change, the override is spent: the schedule rules again.
    val seven = at(7) * 60_000L + 24 * 3_600_000L
    assertEquals(seven, off.overrideUntil)
    assertFalse(off.isActive(seven, at(7)))
    assertTrue(off.isActive(seven + 14 * 3_600_000L, at(21)))
  }

  @Test
  fun `strength maps to a tint from faint to strong`() {
    assertEquals(0.08f, WarmLight(strength = 10).alpha, 1e-6f)
    assertEquals(0.40f, WarmLight(strength = 100).alpha, 1e-6f)
    assertEquals(0.08f, WarmLight(strength = 0).alpha, 1e-6f)
  }

  @Test
  fun `the warm light survives JSON`() {
    val controls = ReaderControls(warmLight = WarmLight(enabled = true, strength = 70, schedule = night, overrideOn = false, overrideUntil = 123))
    assertEquals(controls, ReaderControls.fromJson(controls.toJson()))
  }
}
