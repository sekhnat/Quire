package com.quire.reader.ui.reader

import com.quire.reader.data.StatusItem
import com.quire.reader.reader.ReaderSession
import kotlin.math.roundToInt

/** The phone's battery, when it has one. */
data class BatteryState(val percent: Int, val charging: Boolean)

/**
 * Everything the status line can show, at one moment. Page numbers are Readium positions (about 1,000 characters each),
 * the same "pages" as the footer's "Page X of Y".
 */
data class StatusFacts(
  val bookTitle: String,
  val chapterTitle: String,
  /** The time, already formatted the way the phone shows it (12 or 24 hours). */
  val clock: String,
  val battery: BatteryState?,
  val position: Int,
  val total: Int,
  val pagesLeftInChapter: Int,
  val minutesLeftInBook: Int,
  /** 0–1 through the whole book. */
  val progress: Float,
)

object StatusLineText {

  /** The text [item] shows, or null when it shows nothing (None, or a battery the phone does not have). */
  fun of(item: StatusItem, f: StatusFacts): String? = when (item) {
    StatusItem.None -> null
    StatusItem.BookTitle -> f.bookTitle
    StatusItem.ChapterTitle -> f.chapterTitle.ifBlank { null }
    StatusItem.Clock -> f.clock
    StatusItem.Battery -> f.battery?.let { "${it.percent}%" }
    StatusItem.PageOfTotal -> "Page ${f.position} of ${f.total}"
    StatusItem.PagesLeftInChapter -> when (val n = f.pagesLeftInChapter) {
      0 -> "Last page in chapter"
      1 -> "1 page left in chapter"
      else -> "$n pages left in chapter"
    }
    StatusItem.TimeLeftInChapter -> {
      val minutes = (f.pagesLeftInChapter * ReaderSession.MINUTES_PER_POSITION).roundToInt()
      if (minutes < 1) "Chapter almost done" else "${minutesLabel(minutes)} left in chapter"
    }
    StatusItem.TimeLeftInBook -> timeLabel(f.minutesLeftInBook)
    StatusItem.Percent -> "${(f.progress.coerceIn(0f, 1f) * 100).toInt()}%"
  }

  /** The time left in the book, as the footer has always shown it. */
  fun timeLabel(minutes: Int): String = when {
    minutes < 1 -> "Almost done"
    minutes < 60 -> "$minutes min left"
    else -> "${minutes / 60}h ${minutes % 60}m left"
  }

  private fun minutesLabel(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"

  /**
   * Pages after [here] before the next chapter starts, from the chapters' first pages [chapterStarts] (sorted, from the
   * table of contents; nested entries count as chapters). The last chapter runs to [total]. A book with no table of
   * contents is one chapter.
   */
  fun pagesLeftInChapter(chapterStarts: List<Int>, here: Int, total: Int): Int {
    val next = chapterStarts.firstOrNull { it > here }
    val last = if (next != null) next - 1 else total
    return (last - here).coerceAtLeast(0)
  }
}
