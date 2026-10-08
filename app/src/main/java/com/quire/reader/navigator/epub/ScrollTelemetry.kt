/*
 * Copyright 2024 Quire. Continuous-scroll telemetry, benchmark builds only.
 */

package com.quire.reader.navigator.epub

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the scroll surface reports about its live window in benchmark builds: periodic samples
 * of the documents it holds, every episode of the viewport showing a slot without a live
 * document, and how long documents take to load. The shell only reports once the surface
 * enables it, which only benchmark builds do (`BuildConfig.SCROLL_TELEMETRY`); in every other
 * build no collector exists and the surface drops the events.
 *
 * Owned by the navigator, so a surface rebuilt after a renderer loss keeps adding to the same
 * record. Renderer memory is not here: the renderer is an isolated process the app cannot
 * inspect, so the benchmark harness samples it from the shell.
 *
 * Bridge events arrive on the WebView's binder thread and tests read from theirs; every access
 * holds the collector's lock.
 */
internal class ScrollTelemetry {

    companion object {
        private const val TAG = "ScrollTelemetry"

        /** About 40 minutes of samples at the shell's 500 ms interval. */
        private const val MAX_SAMPLES = 5_000
        private const val MAX_EPISODES = 20_000
        private const val MAX_LOADS = 20_000
    }

    /** One periodic report of the shell's window. Counters are cumulative per surface. */
    data class Sample(
        val t: Long,
        val y: Double,
        val live: Int,
        val loading: Int,
        /** Estimated bytes held by the live documents. */
        val weight: Double,
        /** Of [weight], the documents in the viewport or pinned, which the budget never refuses. */
        val required: Double,
        /** The weight budget at the sample's tier. */
        val budget: Double,
        val jsHeap: Long,
        val mounts: Int,
        val evictions: Int,
        val wasted: Int,
        val cancelled: Int,
        val tier: Int,
        val policy: String,
    )

    /** The viewport showed a slot without a live document from [t] for [ms]; [worst] is the largest share covered. */
    data class BlankEpisode(val t: Long, val ms: Long, val worst: Double)

    /**
     * A fling's predicted stop against where the document was when its hint ended, in CSS px: [end] is
     * `rest`, `touch` (a finger stopped it), `overshoot` or `timeout`. [velocity] is the hinted velocity
     * and [observed] the shell's own reading shortly after the fling started, CSS px per second;
     * [corrected] when the shell rescaled the prediction from it.
     */
    data class FlingCheck(
        val t: Long,
        val start: Double,
        val predicted: Double,
        val actual: Double,
        val velocity: Double,
        val observed: Double,
        val corrected: Boolean,
        val ms: Long,
        val end: String,
    )

    data class Snapshot(
        val samples: List<Sample>,
        val episodes: List<BlankEpisode>,
        val loadMs: List<Long>,
        val flings: List<FlingCheck>,
    )

    private val samples = ArrayDeque<Sample>()
    private val episodes = ArrayDeque<BlankEpisode>()
    private val loads = ArrayDeque<Long>()
    private val flings = ArrayDeque<FlingCheck>()

    /** Folds one `{kind: "telemetry"}` shell event in. Malformed events are dropped. */
    fun onEvent(event: JSONObject) = synchronized(this) {
        when (event.optString("type")) {
            "sample" -> {
                samples.addCapped(
                    Sample(
                        t = event.optLong("t"),
                        y = event.optDouble("y", 0.0),
                        live = event.optInt("live"),
                        loading = event.optInt("loading"),
                        weight = event.optDouble("weight", 0.0),
                        required = event.optDouble("required", 0.0),
                        budget = event.optDouble("budget", 0.0),
                        jsHeap = event.optLong("jsHeap"),
                        mounts = event.optInt("mounts"),
                        evictions = event.optInt("evictions"),
                        wasted = event.optInt("wasted"),
                        cancelled = event.optInt("cancelled"),
                        tier = event.optInt("tier"),
                        policy = event.optString("policy", "static"),
                    ),
                    MAX_SAMPLES,
                )
                val times = event.optJSONArray("loads") ?: JSONArray()
                for (i in 0 until times.length()) loads.addCapped(times.optLong(i), MAX_LOADS)
            }
            "blank" -> episodes.addCapped(
                BlankEpisode(event.optLong("t"), event.optLong("ms"), event.optDouble("worst", 0.0)),
                MAX_EPISODES,
            )
            "fling" -> flings.addCapped(
                FlingCheck(
                    t = event.optLong("t"),
                    start = event.optDouble("start", 0.0),
                    predicted = event.optDouble("predicted", 0.0),
                    actual = event.optDouble("actual", 0.0),
                    velocity = event.optDouble("velocity", 0.0),
                    observed = event.optDouble("observed", 0.0),
                    corrected = event.optBoolean("corrected"),
                    ms = event.optLong("ms"),
                    end = event.optString("end"),
                ),
                MAX_EPISODES,
            )
        }
        Unit
    }

    fun snapshot(): Snapshot = synchronized(this) {
        Snapshot(samples.toList(), episodes.toList(), loads.toList(), flings.toList())
    }

    /** Forgets everything recorded so far, so a benchmark arm starts from zero. */
    fun reset() = synchronized(this) {
        samples.clear()
        episodes.clear()
        loads.clear()
        flings.clear()
    }

    /** Logs a one-line summary of what was recorded. */
    fun logSummary(reason: String) {
        val s = snapshot()
        val blankMs = s.episodes.sumOf { it.ms }
        val peakLive = s.samples.maxOfOrNull { it.live + it.loading } ?: 0
        val last = s.samples.lastOrNull()
        Log.i(
            TAG,
            "$reason: ${s.samples.size} samples, ${s.episodes.size} blank episodes ($blankMs ms), " +
                "peak $peakLive documents, ${last?.mounts ?: 0} loads, ${last?.wasted ?: 0} wasted, " +
                "median load ${s.loadMs.sorted().getOrNull(s.loadMs.size / 2) ?: 0} ms",
        )
    }

    private fun <T> ArrayDeque<T>.addCapped(value: T, cap: Int) {
        if (size >= cap) removeFirst()
        addLast(value)
    }
}
