package com.quire.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Panning a zoomed image never shows empty space past its edges (spec feature 8). */
class MediaZoomTest {
  private val view = IntSize(1000, 2000)
  private val image = IntSize(1000, 600)

  @Test
  fun `at 1x nothing can be panned`() {
    assertOffset(0f, 0f, MediaZoom.clamp(Offset(300f, -200f), 1f, image, view))
  }

  @Test
  fun `zoomed in, panning stops at the image's edges`() {
    // At 4x the image is 4000 x 2400 in a 1000 x 2000 view: 1500 px of slack each side across, 200 down.
    assertOffset(1500f, 200f, MediaZoom.clamp(Offset(5000f, 900f), 4f, image, view))
    assertOffset(-1500f, -200f, MediaZoom.clamp(Offset(-5000f, -900f), 4f, image, view))
    assertOffset(120f, -50f, MediaZoom.clamp(Offset(120f, -50f), 4f, image, view))
  }

  @Test
  fun `an axis still smaller than the view stays centred`() {
    // At 2x the image is 2000 x 1200: wider than the view, not taller.
    assertOffset(400f, 0f, MediaZoom.clamp(Offset(400f, 300f), 2f, image, view))
  }

  /** Offsets compare by value here: a clamped 0 can come back as -0, which Offset's equality tells apart. */
  private fun assertOffset(x: Float, y: Float, actual: Offset) {
    assertEquals(x, actual.x, 0.001f)
    assertEquals(y, actual.y, 0.001f)
  }
}
