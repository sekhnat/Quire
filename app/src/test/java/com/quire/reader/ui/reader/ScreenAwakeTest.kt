package com.quire.reader.ui.reader

import com.quire.reader.data.ReaderControls
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenAwakeTest {
  @Test fun `with keep screen on off the system timeout decides`() {
    assertNull(screenOnHold(ReaderControls(keepScreenOn = false, keepScreenOnMinutes = 10), keepAwake = false))
  }

  @Test fun `always holds the screen while the book is open`() {
    assertEquals(Duration.INFINITE, screenOnHold(ReaderControls(keepScreenOn = true, keepScreenOnMinutes = 0), keepAwake = false))
  }

  @Test fun `a timeout holds the screen for that long after the last touch`() {
    assertEquals(10.minutes, screenOnHold(ReaderControls(keepScreenOn = true, keepScreenOnMinutes = 10), keepAwake = false))
  }

  @Test fun `auto-scroll holds the screen whatever the setting`() {
    assertEquals(Duration.INFINITE, screenOnHold(ReaderControls(keepScreenOn = false), keepAwake = true))
    assertEquals(Duration.INFINITE, screenOnHold(ReaderControls(keepScreenOn = true, keepScreenOnMinutes = 5), keepAwake = true))
  }

  @Test fun `settings saved before the timeout existed keep the screen on always`() {
    val old = ReaderControls.fromJson("""{"volumeKeys":false,"keepScreenOn":true,"orientation":"Auto"}""")!!
    assertEquals(0, old.keepScreenOnMinutes)
    assertEquals(Duration.INFINITE, screenOnHold(old, keepAwake = false))
  }

  @Test fun `the timeout survives a save and load`() {
    val c = ReaderControls(keepScreenOn = true, keepScreenOnMinutes = 30)
    assertEquals(c, ReaderControls.fromJson(c.toJson()))
  }
}
