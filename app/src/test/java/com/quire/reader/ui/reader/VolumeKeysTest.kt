package com.quire.reader.ui.reader

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeKeysTest {
  @Test fun `volume down turns forward and volume up turns back, once per press`() {
    assertEquals(VolumeKey.Forward, volumeKeyFor(KEYCODE_VOLUME_DOWN, ACTION_DOWN, 0, overlaysOpen = false))
    assertEquals(VolumeKey.Backward, volumeKeyFor(KEYCODE_VOLUME_UP, ACTION_DOWN, 0, overlaysOpen = false))
  }

  @Test fun `a held key and the release are swallowed so the system volume never moves`() {
    assertEquals(VolumeKey.Consume, volumeKeyFor(KEYCODE_VOLUME_DOWN, ACTION_DOWN, 3, overlaysOpen = false))
    assertEquals(VolumeKey.Consume, volumeKeyFor(KEYCODE_VOLUME_DOWN, ACTION_UP, 0, overlaysOpen = false))
    assertEquals(VolumeKey.Consume, volumeKeyFor(KEYCODE_VOLUME_UP, ACTION_UP, 0, overlaysOpen = false))
  }

  @Test fun `with something open over the page the system keeps its volume keys`() {
    listOf(ACTION_DOWN, ACTION_UP).forEach { action ->
      assertEquals(VolumeKey.Ignore, volumeKeyFor(KEYCODE_VOLUME_DOWN, action, 0, overlaysOpen = true))
      assertEquals(VolumeKey.Ignore, volumeKeyFor(KEYCODE_VOLUME_UP, action, 0, overlaysOpen = true))
    }
  }

  @Test fun `other keys are never ours`() {
    assertEquals(VolumeKey.Ignore, volumeKeyFor(KEYCODE_BACK, ACTION_DOWN, 0, overlaysOpen = false))
  }
}
