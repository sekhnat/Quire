/*
 * Copyright 2024 Quire. Memory pressure for the continuous-scroll window.
 */

package com.quire.reader.navigator.epub

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log

/**
 * How hard the scroll surface's live window should shrink, from what the system says about memory.
 * Pure state, driven with a clock, so its rules are tested on the JVM; [ScrollMemoryPressure] feeds it.
 *
 * The tier is the strongest of:
 * - the memory signal: trim callbacks (`RUNNING_LOW` → Reduced, `RUNNING_CRITICAL` and above →
 *   Minimal; only delivered up to API 33, Android 14 stopped sending running levels to the
 *   foreground) and polled [ActivityManager.MemoryInfo] samples, the main signal from API 34 on.
 *   A signal raises the tier at once; it comes down one step per [RECOVERY_MS] of samples that no
 *   longer support it, so a reader hovering at the threshold does not reload the window back and forth.
 * - the reader being hidden (Minimal): a backgrounded app's renderer is among the first processes
 *   the low-memory killer takes, and the visible chapter stays loaded for the return.
 * - Reduced, for the rest of the navigator's life, once the renderer was killed: the first loss
 *   rebuilds the surface, and a second would fail the book.
 * - a forced tier, for tests.
 */
internal class PressureTiers(private val clock: () -> Long) {

    enum class Tier { Normal, Reduced, Minimal }

    companion object {
        const val RECOVERY_MS = 30_000L

        /** Available memory under this multiple of the system's low-memory threshold is pressure. */
        const val REDUCED_THRESHOLD_FACTOR = 2.0
        const val MINIMAL_THRESHOLD_FACTOR = 1.2

        fun tierForTrimLevel(level: Int): Tier? = when {
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> null // see onHidden
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> Tier.Minimal
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> Tier.Reduced
            else -> null
        }

        fun tierForSample(availMem: Long, threshold: Long, lowMemory: Boolean): Tier = when {
            lowMemory || availMem < threshold * MINIMAL_THRESHOLD_FACTOR -> Tier.Minimal
            availMem < threshold * REDUCED_THRESHOLD_FACTOR -> Tier.Reduced
            else -> Tier.Normal
        }
    }

    /** The memory signal, with its hysteresis. */
    private var pressure = Tier.Normal

    /** When [pressure] was last supported by a signal; the recovery clock runs from here. */
    private var pressureSince = 0L

    private var hidden = false
    private var floor = Tier.Normal
    private var forced: Tier? = null

    val tier: Tier
        get() = maxOf(pressure, floor, if (hidden) Tier.Minimal else Tier.Normal, forced ?: Tier.Normal)

    /** A trim callback; true when [tier] changed. */
    fun onTrimMemory(level: Int): Boolean = changes { tierForTrimLevel(level)?.let { observe(it) } }

    /** A memory sample; true when [tier] changed. */
    fun onMemorySample(availMem: Long, threshold: Long, lowMemory: Boolean): Boolean =
        changes { observe(tierForSample(availMem, threshold, lowMemory)) }

    fun onHidden(): Boolean = changes { hidden = true }

    fun onVisible(): Boolean = changes { hidden = false }

    fun onRendererLost(): Boolean = changes { floor = maxOf(floor, Tier.Reduced) }

    /** Holds the tier at least at [tier] until called with null. For tests. */
    fun force(tier: Tier?): Boolean = changes { forced = tier }

    private fun observe(signal: Tier) {
        val now = clock()
        when {
            signal >= pressure -> {
                pressure = signal
                pressureSince = now
            }
            now - pressureSince >= RECOVERY_MS -> {
                pressure = Tier.entries[pressure.ordinal - 1]
                pressureSince = now
            }
        }
    }

    private inline fun changes(block: () -> Unit): Boolean {
        val before = tier
        block()
        return tier != before
    }
}

/**
 * Feeds [PressureTiers] from the system: trim callbacks while [start]ed, and [poll]ed memory samples.
 * Owned by the navigator, so it outlives the surfaces it rebuilds; [onTier] hears every change.
 */
internal class ScrollMemoryPressure(
    context: Context,
    private val onTier: (PressureTiers.Tier) -> Unit,
) {
    companion object {
        private const val TAG = "ScrollMemoryPressure"

        /** How often the navigator samples system memory while the reader is in scroll mode. */
        const val POLL_INTERVAL_MS = 2_000L
    }

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val memoryInfo = ActivityManager.MemoryInfo()
    val tiers = PressureTiers { android.os.SystemClock.elapsedRealtime() }
    val tier: PressureTiers.Tier get() = tiers.tier

    private val callbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = update("trim $level") { tiers.onTrimMemory(level) }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = update("low memory") { tiers.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        appContext.registerComponentCallbacks(callbacks)
    }

    fun stop() {
        if (!started) return
        started = false
        appContext.unregisterComponentCallbacks(callbacks)
    }

    /** Samples system memory once. */
    fun poll() {
        activityManager?.getMemoryInfo(memoryInfo) ?: return
        update("sample avail=${memoryInfo.availMem shr 20} MB threshold=${memoryInfo.threshold shr 20} MB low=${memoryInfo.lowMemory}") {
            tiers.onMemorySample(memoryInfo.availMem, memoryInfo.threshold, memoryInfo.lowMemory)
        }
    }

    fun onHidden() = update("hidden") { tiers.onHidden() }
    fun onVisible() = update("visible") { tiers.onVisible() }
    fun onRendererLost() = update("renderer lost") { tiers.onRendererLost() }
    fun force(tier: PressureTiers.Tier?) = update("forced $tier") { tiers.force(tier) }

    private inline fun update(reason: String, block: () -> Boolean) {
        if (block()) {
            Log.i(TAG, "tier ${tiers.tier} ($reason)")
            onTier(tiers.tier)
        }
    }
}
