package com.quire.reader.data

import kotlin.math.max
import kotlin.math.roundToInt

/** A rectangle of an image's own pixels. */
data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * The cover crop screen: an image under a fixed 2:3 frame, which the user pans and zooms. At [scale] 1 the image just
 * covers the frame; [offsetX] and [offsetY] move the image's centre away from the frame's, in screen pixels. Pure, so
 * what the screen shows and what is saved are worked out the same way.
 */
object CoverCrop {
  const val MAX_SCALE = 6f

  /** Screen pixels per image pixel at [scale]. */
  fun pixelsPerImagePixel(imageW: Int, imageH: Int, frameW: Float, frameH: Float, scale: Float): Float =
    max(frameW / imageW, frameH / imageH) * scale

  /** The offset kept so the image always covers the frame: no empty edge can be panned into view. */
  fun clampOffset(offsetX: Float, offsetY: Float, imageW: Int, imageH: Int, frameW: Float, frameH: Float, scale: Float): Pair<Float, Float> {
    val k = pixelsPerImagePixel(imageW, imageH, frameW, frameH, scale)
    val maxX = ((imageW * k - frameW) / 2f).coerceAtLeast(0f)
    val maxY = ((imageH * k - frameH) / 2f).coerceAtLeast(0f)
    return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
  }

  /** The part of the image inside the frame, in the image's own pixels. */
  fun sourceRect(imageW: Int, imageH: Int, frameW: Float, frameH: Float, scale: Float, offsetX: Float, offsetY: Float): PixelRect {
    val (ox, oy) = clampOffset(offsetX, offsetY, imageW, imageH, frameW, frameH, scale)
    val k = pixelsPerImagePixel(imageW, imageH, frameW, frameH, scale)
    val w = (frameW / k).coerceAtMost(imageW.toFloat())
    val h = (frameH / k).coerceAtMost(imageH.toFloat())
    // Moving the image right shows what lies to the left of its centre.
    val cx = imageW / 2f - ox / k
    val cy = imageH / 2f - oy / k
    val left = (cx - w / 2f).roundToInt().coerceIn(0, imageW - 1)
    val top = (cy - h / 2f).roundToInt().coerceIn(0, imageH - 1)
    return PixelRect(left, top, w.roundToInt().coerceIn(1, imageW - left), h.roundToInt().coerceIn(1, imageH - top))
  }
}
