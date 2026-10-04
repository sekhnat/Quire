package com.quire.reader.data.scan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ScanPipelineTest {
  @Test fun `a found file is read while the walk is still going`() = runTest {
    val firstRead = CompletableDeferred<Unit>()
    withTimeout(5_000) {
      readWhileFinding<String>(
        parallelism = 2,
        // The walk cannot finish until the first file it found has been read.
        walk = { emit -> emit("a.epub"); firstRead.await(); emit("b.epub") },
        read = { if (it == "a.epub") firstRead.complete(Unit) },
      )
    }
  }

  @Test fun `every emitted file is read exactly once`() = runTest {
    val read = mutableListOf<String>()
    readWhileFinding<String>(parallelism = 3, walk = { emit -> (1..50).forEach { emit("$it.epub") } }, read = { synchronized(read) { read += it } })
    assertEquals((1..50).map { "$it.epub" }.toSet(), read.toSet())
    assertEquals(50, read.size)
  }

  @Test fun `no more than the given number of files are read at once`() = runTest {
    val running = AtomicInteger(0)
    val peak = AtomicInteger(0)
    readWhileFinding<Int>(
      parallelism = 4,
      walk = { emit -> repeat(40) { emit(it) } },
      read = { running.incrementAndGet().also { n -> peak.updateAndGet { maxOf(it, n) } }; delay(10); running.decrementAndGet() },
    )
    assertTrue("peak ${peak.get()}", peak.get() in 1..4)
  }

  @Test fun `the call returns only after the last read has finished`() = runTest {
    var finished = false
    readWhileFinding<Int>(parallelism = 2, walk = { emit -> emit(1) }, read = { delay(1_000); finished = true })
    assertTrue(finished)
  }
}
