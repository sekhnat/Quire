package com.quire.reader.reader

import com.quire.reader.navigator.epub.DragTracker
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class DragTrackerTest {
  /** Drags from [from] in [steps] equal moves of [step] px and returns the total scroll applied. */
  private fun drag(step: Float, steps: Int, from: Float = 1500f): Int {
    val tracker = DragTracker().apply { start(from) }
    var scrolled = 0
    for (i in 1..steps) scrolled += tracker.step(from - i * step)
    return scrolled
  }

  @Test fun `whole-pixel moves scroll exactly as far as the finger`() {
    assertEquals(40, drag(1f, 40))
  }

  @Test fun `slow sub-pixel moves still scroll with the finger`() {
    assertEquals(40, drag(0.4f, 100))
  }

  @Test fun `moves just over half a pixel do not run ahead of the finger`() {
    assertEquals(30, drag(0.6f, 50))
  }

  @Test fun `moves of one and a half pixels do not fall behind`() {
    assertEquals(60, drag(1.5f, 40))
  }

  @Test fun `dragging back down scrolls backwards by the same amount`() {
    assertEquals(-25, drag(-0.25f, 100))
  }

  @Test fun `the scroll never strays more than half a pixel from the finger`() {
    val tracker = DragTracker().apply { start(1000f) }
    var scrolled = 0
    var y = 1000f
    val moves = floatArrayOf(0.3f, 0.7f, 1.2f, -0.4f, 2.6f, 0.1f, 0.1f, 0.1f, -1.9f, 3.3f)
    repeat(30) { for (m in moves) { y -= m; scrolled += tracker.step(y); assertEquals(true, abs((1000f - y) - scrolled) <= 0.5f + 1e-3f) } }
  }

  @Test fun `starting again forgets the previous gesture's remainder`() {
    val tracker = DragTracker().apply { start(100f) }
    tracker.step(99.6f)
    tracker.start(500f)
    assertEquals(0, tracker.step(500f))
  }
}
