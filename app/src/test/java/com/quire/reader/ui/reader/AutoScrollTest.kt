package com.quire.reader.ui.reader

import com.quire.reader.data.AutoScrollPrefs
import com.quire.reader.data.ReaderControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto-scroll and auto page turn (spec feature 9): what pauses it, and how speeds convert and step. */
class AutoScrollTest {

  @Test fun `it runs only while on and nothing pauses it`() {
    assertFalse(AutoScrollState().running)
    val on = AutoScrollState(on = true)
    assertTrue(on.running)
    val touched = on.paused(AutoPause.Touch, true)
    assertFalse(touched.running)
    val alsoMenu = touched.paused(AutoPause.Overlay, true)
    // The finger lifts, but the menu is still open: still paused.
    assertFalse(alsoMenu.paused(AutoPause.Touch, false).running)
    assertTrue(alsoMenu.paused(AutoPause.Touch, false).paused(AutoPause.Overlay, false).running)
    assertFalse(on.paused(AutoPause.Background, true).running)
    assertFalse(on.paused(AutoPause.Manual, true).running)
  }

  @Test fun `lines a minute become pixels a second from the text size, so a bigger font keeps the pace`() {
    assertEquals(12 * 19 * 1.6 / 60, AutoScrollSpeed.pxPerSecond(12, 19, 1.6f), 1e-6)
    val small = AutoScrollSpeed.pxPerSecond(20, 14, 1.6f)
    val large = AutoScrollSpeed.pxPerSecond(20, 28, 1.6f)
    assertEquals(2.0, large / small, 1e-6)
    assertEquals(2.0, AutoScrollSpeed.pxPerSecond(20, 20, 1.8f) / AutoScrollSpeed.pxPerSecond(10, 20, 1.8f), 1e-6)
  }

  @Test fun `faster and slower step within the limits, each mode on its own`() {
    val prefs = AutoScrollPrefs(linesPerMinute = 12, secondsPerPage = 30)
    assertEquals(14, AutoScrollSpeed.faster(prefs, paged = false).linesPerMinute)
    assertEquals(30, AutoScrollSpeed.faster(prefs, paged = false).secondsPerPage)
    assertEquals(25, AutoScrollSpeed.faster(prefs, paged = true).secondsPerPage)
    assertEquals(35, AutoScrollSpeed.slower(prefs, paged = true).secondsPerPage)
    assertEquals(AutoScrollSpeed.MAX_LINES, AutoScrollSpeed.faster(prefs.copy(linesPerMinute = 60), false).linesPerMinute)
    assertEquals(AutoScrollSpeed.MIN_LINES, AutoScrollSpeed.slower(prefs.copy(linesPerMinute = 2), false).linesPerMinute)
    assertEquals(AutoScrollSpeed.MIN_SECONDS, AutoScrollSpeed.faster(prefs.copy(secondsPerPage = 5), true).secondsPerPage)
    assertEquals(AutoScrollSpeed.MAX_SECONDS, AutoScrollSpeed.slower(prefs.copy(secondsPerPage = 170), true).secondsPerPage)
  }

  @Test fun `the slowest page turn stays inside the reading clock's idle cap`() {
    assertTrue(AutoScrollSpeed.MAX_SECONDS * 1000L < ReadingClock.IDLE_CAP_MS)
  }

  @Test fun `labels and stored speeds`() {
    assertEquals("12 lines/min", AutoScrollSpeed.label(AutoScrollPrefs(), paged = false))
    assertEquals("30 s/page", AutoScrollSpeed.label(AutoScrollPrefs(), paged = true))
    val controls = ReaderControls(autoScroll = AutoScrollPrefs(40, 90))
    assertEquals(controls, ReaderControls.fromJson(controls.toJson()))
    assertEquals(AutoScrollPrefs(), ReaderControls.fromJson("""{"volumeKeys":true}""")!!.autoScroll)
  }
}
