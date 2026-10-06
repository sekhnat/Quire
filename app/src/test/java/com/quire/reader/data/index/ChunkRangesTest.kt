package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChunkRangesTest {
  private val ranges = ChunkRanges(listOf(BookChunks(7, 101, 150), BookChunks(3, 1, 100), BookChunks(9, 200, 200), BookChunks(4, 5, 4)))

  @Test fun `a chunk id belongs to the book whose range holds it`() {
    assertEquals(3L, ranges.bookOf(1))
    assertEquals(3L, ranges.bookOf(100))
    assertEquals(7L, ranges.bookOf(101))
    assertEquals(7L, ranges.bookOf(150))
    assertEquals(9L, ranges.bookOf(200))
  }

  @Test fun `ids outside every range, and empty ranges, belong to no book`() {
    for (id in listOf(0L, 151L, 199L, 201L, -5L)) assertNull("$id", ranges.bookOf(id))
    assertEquals(3, ranges.size)
    assertEquals(1L..200L, ranges.span)
    assertNull(ChunkRanges(emptyList()).span)
  }

  @Test fun `every id of many books is assigned to its own book`() {
    val books = (1..500L).map { BookChunks(it, it * 1000, it * 1000 + it % 37) }
    val r = ChunkRanges(books.shuffled(java.util.Random(4)))
    for (b in books) for (id in b.first..b.last) assertEquals(b.bookId, r.bookOf(id))
    assertNull(r.bookOf(1037))
  }
}
