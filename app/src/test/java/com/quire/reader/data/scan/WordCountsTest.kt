package com.quire.reader.data.scan

import com.quire.reader.data.db.UncountedBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class WordCountsTest {
  private val books = (1L..3L).map { UncountedBook(it, "/books/$it.epub", mtime = it * 10, sizeBytes = it * 100) }
  /** What the database holds: books without a count are the uncounted ones. */
  private val stored = LinkedHashMap<Long, Int>()
  private val writes = mutableListOf<Map<Long, Int>>()
  private var enabled = true
  private var unreadable = emptySet<Long>()
  private var counts: (File) -> Int? = { f -> f.nameWithoutExtension.toInt() * 1000 }

  private fun TestScope.counter(time: () -> Long = { 0L }) = WordCounts(
    uncounted = { books.filter { it.id !in stored } },
    store = { batch -> writes += batch.mapKeys { it.key.id }; batch.forEach { (b, w) -> stored[b.id] = w } },
    enabled = { enabled },
    scope = backgroundScope,
    dispatcher = StandardTestDispatcher(testScheduler),
    count = { counts(it) },
    readable = { _, b -> b.id !in unreadable },
    clock = time,
  )

  @Test fun `a pass counts every uncounted book and stores the counts together`() = runTest {
    assertEquals(3, counter().pass())
    assertEquals(mapOf(1L to 1000, 2L to 2000, 3L to 3000), stored)
    assertEquals(1, writes.size)
  }

  @Test fun `a book that cannot be read stays uncounted, one whose text cannot be read is stored as unknown`() = runTest {
    unreadable = setOf(2L)
    counts = { f -> if (f.nameWithoutExtension == "3") null else 7 }
    counter().pass()
    assertEquals(mapOf(1L to 7, 3L to 0), stored)
    // Once it can be read (access granted, a rescan), the next pass counts it.
    unreadable = emptySet()
    counter().pass()
    assertEquals(7, stored[2L])
  }

  @Test fun `a count that throws is stored as unknown and the pass goes on`() = runTest {
    counts = { f -> if (f.nameWithoutExtension == "1") throw OutOfMemoryError() else 5 }
    counter().pass()
    assertEquals(mapOf(1L to 0, 2L to 5, 3L to 5), stored)
  }

  @Test fun `turning word counts off stops a pass`() = runTest {
    var seen = 0
    counts = { seen++; if (seen == 2) enabled = false; 1 }
    counter().pass()
    assertEquals(setOf(1L, 2L), stored.keys)
  }

  @Test fun `long passes store their counts as they go`() = runTest {
    var now = 0L
    counts = { now += WordCounts.BATCH_MILLIS; 1 }
    counter { now }.pass()
    assertEquals(listOf(mapOf(1L to 1, 2L to 1), mapOf(3L to 1)), writes)
  }

  @Test fun `requests run passes in the background, one after another`() = runTest {
    val counter = counter()
    counter.request(); counter.request()
    runCurrent()
    assertEquals(3, stored.size)
    assertEquals(1, writes.size)
  }
}
