package com.quire.reader.navigator.epub

import kotlin.math.roundToInt

/**
 * Turns a dragging pointer's positions into whole-pixel scroll steps that add up to the
 * finger's travel. Touch coordinates are fractional, so each step's sub-pixel remainder is
 * carried into the next instead of dropped; otherwise slow drags would not move at all and
 * moves just over half a pixel would run ahead of the finger.
 */
internal class DragTracker {
  /** Pointer position that the scroll applied so far corresponds to. */
  private var anchorY = 0f

  fun start(y: Float) {
    anchorY = y
  }

  /** Whole pixels to scroll for the pointer now at [y]; positive when the finger moved up. */
  fun step(y: Float): Int {
    val delta = (anchorY - y).roundToInt()
    anchorY -= delta
    return delta
  }
}
