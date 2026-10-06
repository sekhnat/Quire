package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexPolicyTest {
  private val running = ActivityInputs(
    enabled = true, permissionMissing = false, readerBusy = false, running = true, chargingOnly = false,
    workQueued = false, eligible = 10, pending = 4,
  )

  @Test fun `a book is published only when the file and the index are still the ones it was read from`() {
    assertEquals(Settlement.Publish, settle(Extracted.Text(chunks = 12), fileUnchanged = true, epochCurrent = true))
    assertEquals(Settlement.Discard, settle(Extracted.Text(chunks = 12), fileUnchanged = false, epochCurrent = true))
    assertEquals(Settlement.Discard, settle(Extracted.Text(chunks = 12), fileUnchanged = true, epochCurrent = false))
  }

  @Test fun `an unreadable book fails and a book without text is skipped, each for the current file only`() {
    assertEquals(Settlement.MarkFailed, settle(Extracted.Unreadable, fileUnchanged = true, epochCurrent = true))
    assertEquals(Settlement.MarkSkipped, settle(Extracted.Text(chunks = 0), fileUnchanged = true, epochCurrent = true))
    assertEquals(Settlement.Discard, settle(Extracted.Unreadable, fileUnchanged = false, epochCurrent = true))
    assertEquals(Settlement.Discard, settle(Extracted.Text(chunks = 0), fileUnchanged = true, epochCurrent = false))
  }

  @Test fun `an interrupted extraction never writes anything`() {
    assertEquals(Settlement.Discard, settle(Extracted.Interrupted, fileUnchanged = true, epochCurrent = true))
  }

  @Test fun `only a spent time budget asks for a continuation`() {
    assertTrue(BatchResult(processed = 40, stop = BatchStop.Deadline).needsContinuation)
    assertFalse(BatchResult(processed = 40, stop = BatchStop.Drained).needsContinuation)
    assertFalse(BatchResult(processed = 3, stop = BatchStop.ReaderBusy).needsContinuation)
    assertFalse(BatchResult(processed = 0, stop = BatchStop.Superseded).needsContinuation)
  }

  @Test fun `a request with no worker running starts the chain afresh, even over queued work`() {
    assertEquals(EnqueueChoice.Replace, enqueueChoice(workerActive = false, successorQueued = false))
    assertEquals(EnqueueChoice.Replace, enqueueChoice(workerActive = false, successorQueued = true))
  }

  @Test fun `a request while a worker runs queues one successor and no more`() {
    assertEquals(EnqueueChoice.Append, enqueueChoice(workerActive = true, successorQueued = false))
    assertEquals(EnqueueChoice.Skip, enqueueChoice(workerActive = true, successorQueued = true))
  }

  @Test fun `a disabled setting outranks every other reason`() {
    val inputs = running.copy(enabled = false, permissionMissing = true, readerBusy = true)
    assertEquals(IndexActivity.Disabled, deriveActivity(inputs))
  }

  @Test fun `a missing permission outranks a reader pause and running`() {
    assertEquals(IndexActivity.PermissionMissing, deriveActivity(running.copy(permissionMissing = true, readerBusy = true)))
  }

  @Test fun `an open reader pauses indexing while books remain or a batch is still winding down`() {
    assertEquals(IndexActivity.PausedForReader, deriveActivity(running.copy(running = false, readerBusy = true)))
    assertEquals(IndexActivity.PausedForReader, deriveActivity(running.copy(running = true, readerBusy = true, pending = 0)))
    assertEquals(IndexActivity.Idle, deriveActivity(running.copy(running = false, readerBusy = true, pending = 0)))
  }

  @Test fun `a running batch reports books settled out of books eligible`() {
    assertEquals(IndexActivity.Running(done = 6, total = 10), deriveActivity(running))
  }

  @Test fun `queued work waits for charging only when charging is required and books remain`() {
    val queued = running.copy(running = false, workQueued = true)
    assertEquals(IndexActivity.WaitingForCharging, deriveActivity(queued.copy(chargingOnly = true)))
    assertEquals(IndexActivity.Idle, deriveActivity(queued))
    assertEquals(IndexActivity.Idle, deriveActivity(queued.copy(chargingOnly = true, pending = 0)))
  }

  @Test fun `a book none of whose resources can be read is unreadable and fails`() {
    val outcome = extracted(chunks = 0, htmlResources = 3, unreadable = 3)
    assertEquals(Extracted.Unreadable, outcome)
    assertEquals(Settlement.MarkFailed, settle(outcome, fileUnchanged = true, epochCurrent = true))
  }

  @Test fun `a book with some unreadable resources is published and says how many`() {
    val outcome = extracted(chunks = 40, htmlResources = 34, unreadable = 17)
    assertEquals(Extracted.Text(chunks = 40, unreadableResources = 17), outcome)
    assertEquals(Settlement.Publish, settle(outcome, fileUnchanged = true, epochCurrent = true))
  }

  @Test fun `a readable book without text is still skipped and a book without HTML is not failed`() {
    assertEquals(Settlement.MarkSkipped, settle(extracted(chunks = 0, htmlResources = 2, unreadable = 0), fileUnchanged = true, epochCurrent = true))
    assertEquals(Extracted.Text(chunks = 0), extracted(chunks = 0, htmlResources = 0, unreadable = 0))
  }

  @Test fun `only a sizeable resource that yields almost nothing is sparse`() {
    assertTrue(isSparse(bytes = 450_000, yieldedChars = 184))
    assertTrue(isSparse(bytes = 450_000, yieldedChars = 8_999))
    assertFalse("exactly 2% is enough text", isSparse(bytes = 450_000, yieldedChars = 9_000))
    assertTrue(isSparse(bytes = 2_048, yieldedChars = 0))
    assertFalse("a short page is never sparse", isSparse(bytes = 2_047, yieldedChars = 0))
    assertFalse("an unread resource is never sparse", isSparse(bytes = 0, yieldedChars = 0))
  }
}
