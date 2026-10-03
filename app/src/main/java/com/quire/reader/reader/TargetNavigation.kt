package com.quire.reader.reader

/**
 * How a jump to a text-search target ended, judged by what the page actually shows rather than by the navigator's
 * return value (which says "true" even for text that is not in the chapter).
 */
sealed interface TargetOutcome {
  /** The exact passage was found and is underlined. */
  data object Arrived : TargetOutcome
  /** The passage could not be found; the reader moved to the nearest place in the book instead, with no underline. */
  data object Unresolved : TargetOutcome
  /** There was no navigator to jump in (none showed up, or its page never loaded), so nothing moved. */
  data object NoNavigator : TargetOutcome
}

/** What to tell the user after a jump, or null when it needs no explanation. */
fun TargetOutcome.message(): String? = when (this) {
  TargetOutcome.Arrived -> null
  TargetOutcome.Unresolved -> "Exact passage unavailable. Showing the nearest place in the book."
  TargetOutcome.NoNavigator -> "Couldn't jump to that passage."
}

/** Where a newly opened book starts. */
enum class OpeningPosition { Target, Saved, Start }

/** An explicit search target beats the saved position for this opening only; "Read again" starts at the beginning. */
fun openingPosition(restart: Boolean, hasTarget: Boolean): OpeningPosition = when {
  hasTarget -> OpeningPosition.Target
  restart -> OpeningPosition.Start
  else -> OpeningPosition.Saved
}

/** Shown when a search result belongs to a file that has changed since it was indexed. */
const val STALE_TARGET_MESSAGE = "This book has changed since it was searched. Refreshing its search index."
