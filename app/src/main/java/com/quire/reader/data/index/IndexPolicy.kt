package com.quire.reader.data.index

/** What the indexer is doing, or why it is not, as the library search surfaces it. */
sealed interface IndexActivity {
  data object Idle : IndexActivity
  /** [done] of [total] readable books have an outcome for their current file. */
  data class Running(val done: Int, val total: Int) : IndexActivity
  data object PausedForReader : IndexActivity
  data object WaitingForCharging : IndexActivity
  data object Disabled : IndexActivity
  data object PermissionMissing : IndexActivity
}

/** Everything [deriveActivity] looks at. [pending] counts readable books that still need indexing out of [eligible]. */
data class ActivityInputs(
  val enabled: Boolean,
  val permissionMissing: Boolean,
  val readerBusy: Boolean,
  val running: Boolean,
  val chargingOnly: Boolean,
  /** A request is queued with WorkManager (and so is waiting for its constraints). */
  val workQueued: Boolean,
  val eligible: Int,
  val pending: Int,
)

/** The one thing to tell the user, in order of what blocks indexing most firmly. */
fun deriveActivity(i: ActivityInputs): IndexActivity = when {
  !i.enabled -> IndexActivity.Disabled
  i.permissionMissing -> IndexActivity.PermissionMissing
  i.readerBusy && (i.running || i.pending > 0) -> IndexActivity.PausedForReader
  i.running -> IndexActivity.Running(i.eligible - i.pending, i.eligible)
  i.chargingOnly && i.workQueued && i.pending > 0 -> IndexActivity.WaitingForCharging
  else -> IndexActivity.Idle
}

/** What extracting one book came to, before anything is written. */
sealed interface Extracted {
  /** The publication could not be opened or read (corrupt, DRM, not an EPUB). */
  data object Unreadable : Extracted
  /** The book was read to the end, or to the size cap; [chunks] is how many searchable chunks it made (possibly none). */
  data class Text(val chunks: Int) : Extracted
  /** Reading stopped early (reader opened, indexing cleared or disabled). Nothing was extracted completely. */
  data object Interrupted : Extracted
}

/** What to write for a book once it has been extracted. */
enum class Settlement {
  /** Replace the book's chunks and record `done`. */
  Publish,
  /** Record `failed` and drop obsolete chunks. */
  MarkFailed,
  /** Record `skipped` and drop obsolete chunks. */
  MarkSkipped,
  /** Write nothing: the book stays eligible, or is reconsidered from its current file. */
  Discard,
}

/**
 * The only way a book reaches a terminal state. Nothing is written when extraction was interrupted, when indexing was
 * cleared or disabled meanwhile ([epochCurrent] false), or when the file is no longer the one that was read ([fileUnchanged] false).
 */
fun settle(extracted: Extracted, fileUnchanged: Boolean, epochCurrent: Boolean): Settlement = when {
  !epochCurrent || !fileUnchanged || extracted == Extracted.Interrupted -> Settlement.Discard
  extracted == Extracted.Unreadable -> Settlement.MarkFailed
  extracted is Extracted.Text && extracted.chunks == 0 -> Settlement.MarkSkipped
  else -> Settlement.Publish
}

/** Why a batch of indexing ended. */
enum class BatchStop {
  /** No eligible book is left that this batch has not already set aside. */
  Drained,
  /** The time budget ran out with books still waiting. */
  Deadline,
  /** A reader is opening or open. */
  ReaderBusy,
  /** The index was cleared or indexing disabled while the batch ran. */
  Superseded,
}

/** [processed] books reached a terminal state during the batch. */
data class BatchResult(val processed: Int, val stop: BatchStop) {
  /** Only a spent time budget leaves work behind that nothing else will pick up; reader close and scans request their own runs. */
  val needsContinuation: Boolean get() = stop == BatchStop.Deadline
}
