package com.quire.reader.ui.reader

import kotlin.math.abs

/**
 * Where the reader was before following a link inside the book, offered back as a "Back to page N" chip. One entry
 * deep: a newer jump replaces it.
 *
 * [locator] is where to go back to (a Readium locator in the app). [page] is the origin's position number, the one the
 * footer shows. [landedAt] is the position the jump arrived at, learned from the first position report that differs
 * from the origin (a report from before the jump finished still names the origin, so it is not taken as the landing).
 */
data class ReturnPoint<L>(val locator: L, val page: Int, val landedAt: Int? = null) {

  /** The return point after the reader moved to [position]; null once it is more than [MAX_PAGES_AWAY] from the landing. */
  fun movedTo(position: Int): ReturnPoint<L>? = when {
    landedAt == null -> if (position == page) this else copy(landedAt = position)
    abs(position - landedAt) > MAX_PAGES_AWAY -> null
    else -> this
  }

  val label: String get() = "Back to page $page"

  companion object {
    /** Turning more pages than this away from the link's target dismisses the chip. */
    const val MAX_PAGES_AWAY = 3
  }
}
