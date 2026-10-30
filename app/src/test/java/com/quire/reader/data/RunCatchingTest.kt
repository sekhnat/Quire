package com.quire.reader.data

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunCatchingTest {
  @Test fun `an ordinary failure is caught`() {
    val result = runCatchingCancellable { error("broken file") }
    assertTrue(result.isFailure)
  }

  @Test(expected = CancellationException::class)
  fun `a cancellation is rethrown`() {
    runCatchingCancellable { throw CancellationException("stopped") }
  }

  @Test fun `a cancelled read stops its loop instead of moving on to the next item`() = runTest {
    val reached = mutableListOf<Int>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      for (i in 1..3) {
        reached += i
        runCatchingCancellable { awaitCancellation() }
      }
    }
    job.cancel()
    job.join()
    assertEquals(listOf(1), reached)
  }
}
