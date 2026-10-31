package com.quire.reader.ui.reader

/** One stretch of reading the clock measured: what is stored as a `ReadingSessionEntity` once it has a book. */
data class ReadingSpan(
  val startedAt: Long,
  val endedAt: Long,
  val activeMs: Long,
  val startProgress: Float,
  val endProgress: Float,
  /** Forward page-by-page progress only, as a fraction of the book; jumps are left out. */
  val readProgress: Float,
)

/**
 * Measures how long a book is actually read. A session begins when the reader comes to the front ([resume]) and ends
 * when it leaves it ([pause]). Time only counts while it runs, and the gap since the last sign of life (a page turn,
 * or the start) counts for at most [IDLE_CAP_MS], so a book left open on a table adds minutes, not hours.
 *
 * It holds no clock of its own, only [now], so tests drive it with a fake one; and it never touches the database.
 */
class ReadingClock(private val now: () -> Long, positions: Int) {
  /** The largest forward step, as a fraction of the book, that still counts as reading rather than jumping. */
  private val stepLimit = STEP_POSITIONS / positions.coerceAtLeast(1).toFloat()

  private var running = false
  private var startedAt = 0L
  private var lastAt = 0L
  private var activeMs = 0L
  private var startProgress = 0f
  private var lastProgress = 0f
  private var readProgress = 0f

  fun resume(progress: Float) {
    if (running) return
    val t = now()
    startedAt = t; lastAt = t
    activeMs = 0; readProgress = 0f
    startProgress = progress; lastProgress = progress
    running = true
  }

  /** The reader is now at [progress]. Moves made while not running (the place being restored) are not reading. */
  fun moved(progress: Float) {
    if (!running) { lastProgress = progress; return }
    accrue()
    val step = progress - lastProgress
    if (step > 0f && step <= stepLimit) readProgress += step
    lastProgress = progress
  }

  /** Ends the session; null when it was too short to be one, or none was running. */
  fun pause(): ReadingSpan? {
    if (!running) return null
    accrue()
    running = false
    if (activeMs < MIN_ACTIVE_MS) return null
    return ReadingSpan(startedAt, lastAt, activeMs, startProgress, lastProgress, readProgress.coerceIn(0f, 1f))
  }

  private fun accrue() {
    val t = now()
    activeMs += (t - lastAt).coerceIn(0L, IDLE_CAP_MS)
    lastAt = t
  }

  companion object {
    const val IDLE_CAP_MS = 3 * 60_000L
    const val MIN_ACTIVE_MS = 30_000L
    /** A move of up to this many pages is reading; a longer one is a jump (contents, the slider, a search result). */
    const val STEP_POSITIONS = 5
  }
}
