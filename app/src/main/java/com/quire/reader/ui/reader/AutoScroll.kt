package com.quire.reader.ui.reader

import com.quire.reader.data.AutoScrollPrefs

/** Why auto-scroll is not moving right now, if it is on. */
enum class AutoPause { Touch, Overlay, Background, Manual }

/**
 * Auto-scroll (scroll mode) and auto page turn (paged mode). [on] is the reader's choice; it moves only while nothing
 * pauses it: a finger on the page (until a second after it lifts), an overlay (the menu, a sheet, a selection, the
 * full-screen viewer), or the app in the background.
 */
data class AutoScrollState(val on: Boolean = false, val pauses: Set<AutoPause> = emptySet()) {
  val running: Boolean get() = on && pauses.isEmpty()

  fun paused(why: AutoPause, paused: Boolean): AutoScrollState = copy(pauses = if (paused) pauses + why else pauses - why)

  companion object {
    /** After a finger lifts, scrolling waits this long before it picks up again. */
    const val RESUME_AFTER_TOUCH_MS = 1_000L
  }
}

object AutoScrollSpeed {
  const val MIN_LINES = 2
  const val MAX_LINES = 60
  const val LINES_STEP = 2
  const val MIN_SECONDS = 5
  /** At most 170 s a page: a longer gap between turns would count as idle time on the reading clock (3 min cap). */
  const val MAX_SECONDS = 170
  const val SECONDS_STEP = 5

  /**
   * Lines a minute in CSS px a second, from the text size (CSS px; the reader's size is its CSS size) and line spacing, so
   * a bigger font keeps the same reading pace.
   */
  fun pxPerSecond(linesPerMinute: Int, fontSize: Int, lineHeight: Float): Double = linesPerMinute * fontSize * lineHeight / 60.0

  fun faster(prefs: AutoScrollPrefs, paged: Boolean): AutoScrollPrefs =
    if (paged) prefs.copy(secondsPerPage = (prefs.secondsPerPage - SECONDS_STEP).coerceIn(MIN_SECONDS, MAX_SECONDS))
    else prefs.copy(linesPerMinute = (prefs.linesPerMinute + LINES_STEP).coerceIn(MIN_LINES, MAX_LINES))

  fun slower(prefs: AutoScrollPrefs, paged: Boolean): AutoScrollPrefs =
    if (paged) prefs.copy(secondsPerPage = (prefs.secondsPerPage + SECONDS_STEP).coerceIn(MIN_SECONDS, MAX_SECONDS))
    else prefs.copy(linesPerMinute = (prefs.linesPerMinute - LINES_STEP).coerceIn(MIN_LINES, MAX_LINES))

  fun label(prefs: AutoScrollPrefs, paged: Boolean): String =
    if (paged) "${prefs.secondsPerPage} s/page" else "${prefs.linesPerMinute} lines/min"
}
