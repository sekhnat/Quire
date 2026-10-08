package com.quire.reader.navigator.epub

import android.content.ComponentCallbacks2
import com.quire.reader.navigator.epub.PressureTiers.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll window's memory-pressure tiers: which system signals shrink the window, that it shrinks at
 * once and grows back one step at a time, and the conditions that hold it small regardless.
 */
class PressureTiersTest {

  private var now = 0L
  private val tiers = PressureTiers { now }

  private val mb = 1L shl 20
  private val threshold = 200 * mb

  private fun healthy() = tiers.onMemorySample(availMem = 2_000 * mb, threshold = threshold, lowMemory = false)

  @Test
  fun `starts at normal`() {
    assertEquals(Tier.Normal, tiers.tier)
  }

  @Test
  fun `running trim levels map to tiers`() {
    assertEquals(null, PressureTiers.tierForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    assertEquals(Tier.Reduced, PressureTiers.tierForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
    assertEquals(Tier.Minimal, PressureTiers.tierForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
    assertEquals(Tier.Minimal, PressureTiers.tierForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    // Hidden is its own input, not a memory signal.
    assertEquals(null, PressureTiers.tierForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
  }

  @Test
  fun `memory samples map to tiers against the system threshold`() {
    assertEquals(Tier.Normal, PressureTiers.tierForSample(threshold * 3, threshold, lowMemory = false))
    assertEquals(Tier.Reduced, PressureTiers.tierForSample(threshold * 3 / 2, threshold, lowMemory = false))
    assertEquals(Tier.Minimal, PressureTiers.tierForSample(threshold, threshold, lowMemory = false))
    assertEquals(Tier.Minimal, PressureTiers.tierForSample(threshold * 3, threshold, lowMemory = true))
  }

  @Test
  fun `a signal raises the tier at once`() {
    assertTrue(tiers.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
    assertEquals(Tier.Reduced, tiers.tier)
    assertTrue(tiers.onMemorySample(availMem = threshold, threshold = threshold, lowMemory = false))
    assertEquals(Tier.Minimal, tiers.tier)
  }

  @Test
  fun `the tier comes down one step per recovery period of healthy samples`() {
    tiers.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    now += PressureTiers.RECOVERY_MS - 1
    assertFalse(healthy())
    assertEquals(Tier.Minimal, tiers.tier)

    now += 1
    assertTrue(healthy())
    assertEquals(Tier.Reduced, tiers.tier)

    now += PressureTiers.RECOVERY_MS / 2
    assertFalse(healthy())
    assertEquals(Tier.Reduced, tiers.tier)
    now += PressureTiers.RECOVERY_MS / 2
    assertTrue(healthy())
    assertEquals(Tier.Normal, tiers.tier)
  }

  @Test
  fun `a sample that still shows pressure restarts the recovery period`() {
    tiers.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    now += PressureTiers.RECOVERY_MS - 1_000
    tiers.onMemorySample(availMem = threshold * 3 / 2, threshold = threshold, lowMemory = false)
    now += 2_000
    assertFalse(healthy())
    assertEquals(Tier.Reduced, tiers.tier)
  }

  @Test
  fun `a hidden reader is minimal until it is visible again`() {
    assertTrue(tiers.onHidden())
    assertEquals(Tier.Minimal, tiers.tier)
    assertTrue(tiers.onVisible())
    assertEquals(Tier.Normal, tiers.tier)
  }

  @Test
  fun `coming back from hidden returns to the pressure there is`() {
    tiers.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    tiers.onHidden()
    tiers.onVisible()
    assertEquals(Tier.Reduced, tiers.tier)
  }

  @Test
  fun `a lost renderer keeps the window reduced for good`() {
    assertTrue(tiers.onRendererLost())
    assertEquals(Tier.Reduced, tiers.tier)
    now += PressureTiers.RECOVERY_MS * 10
    assertFalse(healthy())
    assertEquals(Tier.Reduced, tiers.tier)
    // Stronger pressure still applies above the floor.
    tiers.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    assertEquals(Tier.Minimal, tiers.tier)
  }

  @Test
  fun `a forced tier holds until released`() {
    assertTrue(tiers.force(Tier.Minimal))
    now += PressureTiers.RECOVERY_MS * 10
    healthy()
    assertEquals(Tier.Minimal, tiers.tier)
    assertTrue(tiers.force(null))
    assertEquals(Tier.Normal, tiers.tier)
  }
}
