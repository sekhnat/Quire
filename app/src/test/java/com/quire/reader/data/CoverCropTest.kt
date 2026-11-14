package com.quire.reader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Framing a cover by hand (spec feature 11): what the 2:3 frame shows is what is saved, in the image's own pixels. */
class CoverCropTest {
  // A 600 x 900 frame (2:3) on screen.
  private val fw = 600f
  private val fh = 900f

  @Test fun `at rest a tall image fills the frame's width and the middle of its height is kept`() {
    // 1000 x 2000: the width fits (0.6 screen px per image px), 1500 image px of height show.
    assertEquals(PixelRect(0, 250, 1000, 1500), CoverCrop.sourceRect(1000, 2000, fw, fh, scale = 1f, offsetX = 0f, offsetY = 0f))
  }

  @Test fun `at rest a wide image fills the frame's height and the middle of its width is kept`() {
    assertEquals(PixelRect(700, 0, 600, 900), CoverCrop.sourceRect(2000, 900, fw, fh, 1f, 0f, 0f))
  }

  @Test fun `moving the image right shows its left side, and the frame never leaves the image`() {
    // Wide image at rest: 700 image px of slack each side (at 1 screen px per image px).
    assertEquals(PixelRect(400, 0, 600, 900), CoverCrop.sourceRect(2000, 900, fw, fh, 1f, offsetX = 300f, offsetY = 0f))
    assertEquals(PixelRect(0, 0, 600, 900), CoverCrop.sourceRect(2000, 900, fw, fh, 1f, offsetX = 5000f, offsetY = 0f))
    assertEquals(PixelRect(1400, 0, 600, 900), CoverCrop.sourceRect(2000, 900, fw, fh, 1f, offsetX = -5000f, offsetY = 0f))
    // No vertical slack at rest: a vertical move changes nothing.
    assertEquals(PixelRect(700, 0, 600, 900), CoverCrop.sourceRect(2000, 900, fw, fh, 1f, 0f, offsetY = 400f))
  }

  @Test fun `zooming in keeps a smaller part of the image, around the same centre`() {
    assertEquals(PixelRect(850, 225, 300, 450), CoverCrop.sourceRect(2000, 900, fw, fh, scale = 2f, offsetX = 0f, offsetY = 0f))
  }

  @Test fun `the offset is clamped to what the zoom allows`() {
    val (x, y) = CoverCrop.clampOffset(10_000f, -10_000f, 2000, 900, fw, fh, scale = 2f)
    // At 2x the image is 4000 x 1800 on screen: 1700 px of slack across, 450 down.
    assertEquals(1700f, x, 0.01f)
    assertEquals(-450f, y, 0.01f)
  }
}
