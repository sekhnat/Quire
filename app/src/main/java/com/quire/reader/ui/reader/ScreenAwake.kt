package com.quire.reader.ui.reader

import com.quire.reader.data.ReaderControls
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How long the reader holds the screen on after the last touch or key press: null lets the system's timeout decide,
 * [Duration.INFINITE] holds it while the book is open. Auto-scroll ([keepAwake]) holds it while it runs, whatever
 * *Keep screen on* is set to.
 */
fun screenOnHold(controls: ReaderControls, keepAwake: Boolean): Duration? = when {
  keepAwake -> Duration.INFINITE
  !controls.keepScreenOn -> null
  controls.keepScreenOnMinutes <= 0 -> Duration.INFINITE
  else -> controls.keepScreenOnMinutes.minutes
}
