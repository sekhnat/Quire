package com.quire.reader.ui.reader

import android.view.KeyEvent

/** What the reader does with a hardware key event. */
enum class VolumeKey { Ignore, Consume, Forward, Backward }

/**
 * Volume down turns forward and volume up turns back, once per press. Both keys are consumed while pressed, so the
 * system volume does not move under the page turns, and the release of a key whose press was ours is consumed too.
 * With a sheet, search or the controls open the keys are left to the system, so volume still works there.
 */
fun volumeKeyFor(keyCode: Int, action: Int, repeatCount: Int, overlaysOpen: Boolean): VolumeKey {
  if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) return VolumeKey.Ignore
  if (overlaysOpen) return VolumeKey.Ignore
  if (action != KeyEvent.ACTION_DOWN || repeatCount > 0) return VolumeKey.Consume
  return if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) VolumeKey.Forward else VolumeKey.Backward
}
