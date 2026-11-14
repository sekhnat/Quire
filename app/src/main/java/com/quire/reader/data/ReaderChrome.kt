package com.quire.reader.data

import kotlinx.serialization.Serializable

// The reader's surroundings that are app-wide rather than per book: the status line around the page, and the warm
// light over it. Both ride in ReaderControls, so they are part of snapshots like the other controls.

/** What one slot of the status line shows. */
@Serializable
enum class StatusItem(val label: String) {
  None("Nothing"),
  BookTitle("Book title"),
  ChapterTitle("Chapter title"),
  Clock("Clock"),
  Battery("Battery"),
  PageOfTotal("Page of total"),
  PagesLeftInChapter("Pages left in chapter"),
  TimeLeftInChapter("Time left in chapter"),
  TimeLeftInBook("Time left in book"),
  Percent("Percent"),
}

/** The three slots of one line. */
@Serializable
data class StatusSlots(val left: StatusItem = StatusItem.None, val centre: StatusItem = StatusItem.None, val right: StatusItem = StatusItem.None) {
  fun with(slot: StatusSlot, item: StatusItem): StatusSlots = when (slot) {
    StatusSlot.Left -> copy(left = item)
    StatusSlot.Centre -> copy(centre = item)
    StatusSlot.Right -> copy(right = item)
  }

  operator fun get(slot: StatusSlot): StatusItem = when (slot) {
    StatusSlot.Left -> left
    StatusSlot.Centre -> centre
    StatusSlot.Right -> right
  }
}

enum class StatusSlot { Left, Centre, Right }

/**
 * The lines above and below the page. The defaults are the reader as it always was: the book's title centred above,
 * the page on the left below and the time left in the book on the right.
 */
@Serializable
data class StatusLine(
  val header: StatusSlots = StatusSlots(centre = StatusItem.BookTitle),
  val footer: StatusSlots = StatusSlots(left = StatusItem.PageOfTotal, right = StatusItem.TimeLeftInBook),
  val showHeader: Boolean = true,
  val showFooter: Boolean = true,
)

/** Auto-scroll speeds, remembered per mode: lines a minute when scrolling, seconds a page when turning pages. */
@Serializable
data class AutoScrollPrefs(val linesPerMinute: Int = 12, val secondsPerPage: Int = 30)

/** A daily window, in minutes after local midnight; it may cross midnight (21:00–07:00). Equal ends make it empty. */
@Serializable
data class WarmSchedule(val startMinute: Int = 21 * 60, val endMinute: Int = 7 * 60) {
  fun contains(minuteOfDay: Int): Boolean = when {
    startMinute == endMinute -> false
    startMinute < endMinute -> minuteOfDay in startMinute until endMinute
    else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
  }

  /** Minutes from [minuteOfDay] to the next start or end, at least 1. */
  fun minutesToNextChange(minuteOfDay: Int): Int {
    fun until(target: Int) = ((target - minuteOfDay) % DAY + DAY) % DAY
    return listOf(until(startMinute), until(endMinute)).map { if (it == 0) DAY else it }.min()
  }

  companion object {
    const val DAY = 24 * 60
  }
}

/**
 * The warm tint over the reader. Without a [schedule], [enabled] is the switch. With one, the schedule decides, and the
 * switch sets [overrideOn] until [overrideUntil] (the schedule's next change), the way the system's Night Light behaves.
 */
@Serializable
data class WarmLight(
  val enabled: Boolean = false,
  /** 10–100 (percent). */
  val strength: Int = 50,
  val schedule: WarmSchedule? = null,
  val overrideOn: Boolean? = null,
  /** Epoch milliseconds; the override has no effect from then on. */
  val overrideUntil: Long = 0,
) {
  /** Whether the tint shows at [nowMs], which is [minuteOfDay] minutes after local midnight. */
  fun isActive(nowMs: Long, minuteOfDay: Int): Boolean {
    val schedule = schedule ?: return enabled
    if (overrideOn != null && nowMs < overrideUntil) return overrideOn
    return schedule.contains(minuteOfDay)
  }

  /** This light with its switch flipped at [nowMs] ([minuteOfDay] local): the switch itself, or an override until the next change. */
  fun toggled(nowMs: Long, minuteOfDay: Int): WarmLight {
    val schedule = schedule ?: return copy(enabled = !enabled)
    val next = nowMs - nowMs % 60_000 + schedule.minutesToNextChange(minuteOfDay) * 60_000L
    return copy(overrideOn = !isActive(nowMs, minuteOfDay), overrideUntil = next)
  }

  /** The tint's opacity for [strength]: 0.08 at 10% to 0.40 at 100%. */
  val alpha: Float get() = 0.08f + (strength.coerceIn(MIN_STRENGTH, 100) - MIN_STRENGTH) / (100f - MIN_STRENGTH) * 0.32f

  companion object {
    const val MIN_STRENGTH = 10
  }
}
