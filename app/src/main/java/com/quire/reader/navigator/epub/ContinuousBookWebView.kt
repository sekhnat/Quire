/*
 * Copyright 2024 Quire. Continuous-scroll book surface, written for Quire's vendored
 * Readium navigator fork (BSD-style license headers of the copied code preserved in
 * the vendored files this one replaces).
 */
//
// The scroll-mode surface: ONE native Android WebView loading the reserved shell
// document (see assets/quire/continuous-scroll.html), which stacks every reading-order
// resource as a same-origin, full-content-height iframe in publication order. The
// outer document owns the only scrollable range — native drag, fling and
// touch-to-stop come for free, and chapter seams are not scroll edges.
//
// The book is one column of slots, one per resource, with one complete geometry table.
// Only the slots near the viewport hold a live document (its Readium runtime, CSS, fonts
// and decoded images); the rest are empty boxes of their measured height, or of an
// estimate until a background pass has measured them, so memory is bounded by the window
// and not by the size of the book. The surface reports `Ready` once the documents around
// the starting position have settled. A required document failing before that is a
// terminal book-loading error; a failed optional image settles with the normal
// missing-image behavior.
//
// A document that leaves the window reports `frameEvicted` and its runner goes back to
// not-loaded; loading it again re-runs the per-resource initialization. Anything that has
// to address a resource that may not be live (a jump, a search underline, a selection)
// pins it first, see [withFrame].
//
// Each frame gets one ScriptRunner bound to its original href, and a frame-local
// adapter (`QuireBook`) through which Readium's injected scripts talk to the native
// side: taps, links/footnotes, viewport dimensions, selection, drag and decoration
// activation carry their originating resource, so interactions address the right
// document even when the top visible resource differs.

@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class, org.readium.r2.shared.ExperimentalReadiumApi::class, org.readium.r2.shared.DelicateReadiumApi::class)

package com.quire.reader.navigator.epub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.ActionMode
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import com.quire.reader.navigator.extensions.htmlId
import com.quire.reader.navigator.extensions.optRectF
import org.readium.r2.navigator.DecorationId
import org.readium.r2.shared.extensions.optNullableString
import timber.log.Timber
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.data.decodeString
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.use

/** Book-wide preparation state of the continuous surface. */
sealed class ContinuousBookState {
    data object Preparing : ContinuousBookState()
    data object Ready : ContinuousBookState()
    data class Failed(val error: String) : ContinuousBookState()

    /** The surface was closed or its book replaced; late callbacks are dropped. */
    data object Disposed : ContinuousBookState()
}

/**
 * A frame script's object answer, or null when the frame answered `null` or something else.
 *
 * Frame scripts are evaluated inside their own frame, and the bridge hands their answer back
 * JSON-encoded (a JSON *string*), so the object has to be unwrapped before it can be read.
 */
private fun String?.frameResultJson(): JSONObject? {
    var value: Any = runCatching { JSONTokener(this ?: "null").nextValue() }.getOrNull() ?: return null
    var guard = 0
    while (value is String && guard++ < 4) {
        value = runCatching { JSONTokener(value as String).nextValue() }.getOrNull() ?: return null
    }
    return value as? JSONObject
}

/** The `{ top: … }` offset of a frame script's answer, in CSS px, or null. */
private fun String?.frameResultTop(): Double? =
    frameResultJson()?.takeIf { it.has("top") }?.optDouble("top")?.takeIf { !it.isNaN() }
internal class ContinuousBookWebView @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface") constructor(
    context: Context,
    /** The forked navigator owning this surface. */
    private val navigator: EpubNavigatorFragment,
    ) : FrameLayout(context) {

    companion object {
        private const val TAG = "ContinuousBook"
        /** Minimum interval between locator publications while the reader scrolls. */
        private const val PROGRESSION_NOTIFY_INTERVAL_MS = 120L

        /** Restores a captured anchor: returns the element's offset from content top. */
        private const val ANCHOR_RESTORE_PREFIX = "(function(){ var parts='"
        private const val ANCHOR_RESTORE_SUFFIX = "'.split(':'); var blocks = document.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote'); var el = blocks[parts[1]]; if (!el) return -1; return el.getBoundingClientRect().top + window.pageYOffset; })()"

        private const val SETTLE_TIMEOUT_MS = 10_000L

        /** See [resourceIndexAt]. */
        private const val SEAM_TOLERANCE_CSS = 1.0

        /** How long a jump or script waits for a document outside the live window to load. */
        private const val FRAME_LOAD_TIMEOUT_MS = 20_000L

        /** How long a reflow waits for its remeasure to commit before restoring best-effort. */
        private const val LAYOUT_COMMIT_TIMEOUT_MS = 4_000L
    }

    /** What the surface needs from the navigator's view model. */
    interface Host {
        val publication: Publication

        /** Effective background color of the reading surface, as an Android color int. */
        val backgroundColor: Int

        /**
         * The app's custom text-selection menu. The paged path installs it through
         * `R2BasicWebView.startActionMode`; the shell document would otherwise show the
         * system menu, so the scroll surface asks for it too.
         */
        val selectionActionModeCallback: ActionMode.Callback?

        /** The reserved shell document URL on the effective publication origin. */
        fun shellUrl(): AbsoluteUrl
        fun urlTo(link: Link): AbsoluteUrl
        fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse?
        fun shouldOverrideUrlLoading(request: WebResourceRequest): Boolean

        /**
         * The per-resource Readium initialization for a frame whose document just loaded:
         * current CSS properties, decoration templates and any saved decorations. The paged
         * path runs this from the page fragment's `onPageFinished`; a continuous-scroll frame
         * must run it too, or every decoration style is unregistered inside the frame and its
         * decorations render as empty boxes.
         */
        fun onResourceLoaded(link: Link)

        /** The number of Readium positions in the resource at [index], to estimate its height. */
        fun positionCount(index: Int): Int
        fun onBookReady()
        fun onBookFailed(error: String)

        /** The WebView's renderer process was killed (memory) or crashed; the surface is unusable. */
        fun onRendererGone(didCrash: Boolean)
        fun onProgressionChanged()
        fun onTap(point: PointF): Boolean
        fun onDrag(type: DragType, start: PointF, offset: PointF): Boolean
        fun onDecorationActivated(id: DecorationId, group: String, rect: RectF, point: PointF, href: Url): Boolean
        fun onFootnoteLinkActivated(url: AbsoluteUrl, context: String)
        fun resourceAtUrl(url: AbsoluteUrl): Resource?
        fun javascriptInterfacesFor(link: Link): Map<String, Any?>
        fun clearSelectionRequested()
        fun runScript(command: EpubNavigatorViewModel.RunScriptCommand)
    }

    enum class DragType { Start, Move, End }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The one native WebView showing the shell document. */
    private val shell: WebView = ShellWebView(context)

    private val _state = MutableStateFlow<ContinuousBookState>(ContinuousBookState.Preparing)
    val state: StateFlow<ContinuousBookState> = _state.asStateFlow()

    /** Session generation: bumped on close/replacement; stale callbacks check it. */
    private var generation = 0

    /** The generation whose frames are currently loaded in the shell document. */
    private var generationAtPrepare = 0

    /** True once the shell document finished loading and can host frames. */
    private var shellLoaded = false

    /** True once the reading-order frames were created for this surface. */
    private var framesCreated = false

    /** Layout generation: bumped when every frame is remeasured after a reflow. */
    private var layoutGeneration = 0

    /** Serializes remeasures so two reflows cannot interleave their capture and commit. */
    private val remeasureMutex = Mutex()

    /** Reading-order resources this surface owns, by original href (no fragment). */
    private val resources: List<Link> = navigator.readingOrder

    /** Canonical hrefs (manifest spelling) of [resources]. */
    private val hrefs: List<Url> = resources.map { it.url().removeFragment() }

    /** ScriptRunner per original href, in reading order. Created eagerly. */
    private val runners: MutableList<FrameRunner> = resources.map { FrameRunner(it) }.toMutableList()

    /** Committed geometry: resource top offsets in CSS px, aligned with [resources]. */
    private var tops: List<Double> = List(resources.size) { 0.0 }

    /** Committed frame heights in CSS px, aligned with [resources]. */
    private var heights: List<Double> = List(resources.size) { 0.0 }

    /** Book height in CSS px from the committed table. */
    private val bookHeight: Double get() = tops.lastOrNull()?.plus(heights.lastOrNull() ?: 0.0) ?: 0.0

    /** Reader viewport width in CSS px, reported to frames for Readium's viewport math. */
    private var viewportWidthCss: Int = 0

    /** Reader viewport height in CSS px, for anchor capture and clamping. */
    private var viewportHeightCss: Int = 0

    /** The resource whose slot is scrolled to and loaded first. */
    private var initialIndex = 0

    /** Pending initial jump, applied once the book becomes ready. */
    private var initialJump: (() -> Unit)? = null

    /** Pending locator navigation: latest request wins. */
    private var pendingJump: PendingJump? = null

    private class PendingJump(
        val href: Url,
        val htmlId: String?,
        val progression: Double,
        /** Optional exact-text decoration to underline once landed. */
        val decoration: ((Url) -> Unit)?,
    )

    init {
        addView(shell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setupShell()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupShell() {
        val host = navigator.bookHost
        shell.settings.javaScriptEnabled = true
        shell.settings.domStorageEnabled = true
        // Frames must re-serve their documents per session: the publication is a live
        // file the user can replace, and the HTTP cache would bypass the serving
        // pipeline (and any interception) on later opens.
        shell.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        shell.isVerticalScrollBarEnabled = false
        shell.isHorizontalScrollBarEnabled = false
        shell.overScrollMode = OVER_SCROLL_NEVER
        shell.setBackgroundColor(host.backgroundColor)
        shell.isFocusable = false
        // The outer document owns the only scrollable range. Mirror its position in CSS px
        // and report movement to the navigator (throttled: a fling must not flood the
        // locator emitter, and a long fling must still publish progress while it runs).
        shell.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            scrollYSync = scrollY / context.resources.displayMetrics.density.toDouble()
            notifyProgressionThrottled()
        }
        shell.addJavascriptInterface(ShellBridge(), "QuireShell")
        shell.addJavascriptInterface(QuireBookBridge(), "QuireBookBridge")
        shell.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                host.shouldInterceptRequest(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                host.shouldOverrideUrlLoading(request)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Returning false (the default) would take the whole app down with the
                // renderer. The surface is rebuilt, or given up on, by the navigator.
                Log.w(TAG, "renderer process gone: didCrash=${detail.didCrash()}")
                val gen = generation
                post { if (gen == generation) navigator.bookHost.onRendererGone(detail.didCrash()) }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "shell onPageFinished: $url")
                // The shell document is live: expose the frame bridge to the frames and
                // create every reading-order frame eagerly.
                shell.evaluateJavascript(
                    "window.QuireBook = window.QuireBookBridge; typeof QuireShellHost",
                ) { result -> Log.d(TAG, "shell QuireShellHost: $result") }
                shellLoaded = true
                shell.evaluateJavascript("JSON.stringify({w:innerWidth,h:innerHeight,dpr:devicePixelRatio})") { r ->
                }
                createFramesIfMeasurable()
            }
        }
        shell.loadUrl(host.shellUrl().toString())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || (w == oldw && h == oldh)) return
        val density = context.resources.displayMetrics.density
        viewportWidthCss = (w / density).toInt()
        viewportHeightCss = (h / density).toInt()
        if (oldw <= 0 || oldh <= 0) {
            // The first real layout: frames created before it were measured at the wrong width.
            createFramesIfMeasurable()
        }
        if (framesCreated) {
            // Every later viewport change reflows the prepared documents. Before readiness the
            // frames are still loading, so a plain re-measure is enough; afterwards the reading
            // anchor is captured and restored around it.
            if (_state.value == ContinuousBookState.Ready) {
                navigator.reflowContinuousSurface()
            } else {
                scope.launch { remeasureAllFrames() }
            }
        }
    }

    /**
     * Publishes the current position to the navigator, at most once per
     * [PROGRESSION_NOTIFY_INTERVAL_MS] while the reader keeps scrolling. Without this the
     * navigator's locator stays at the readiness position: progress, saved positions,
     * bookmarks and the current-chapter highlight would all report stale content.
     */
    private fun notifyProgressionThrottled() {
        if (progressionNotifyPending) return
        progressionNotifyPending = true
        shell.postDelayed({
            progressionNotifyPending = false
            if (_state.value == ContinuousBookState.Ready) {
                navigator.bookHost.onProgressionChanged()
            }
        }, PROGRESSION_NOTIFY_INTERVAL_MS)
    }

    // ── preparation ─────────────────────────────────────────────────────────────

    /**
     * Creates every reading-order frame eagerly and waits for the whole book: each
     * frame's document, Readium runtime, CSS, decoration templates, fonts and static
     * images settle, and one complete geometry table is committed.
     */
    fun prepare(initialLocator: Locator?) {
        // Frames are created when the shell document finishes loading (onPageFinished).
        // The scroll frame adapter is injected next to Readium's scripts in every frame
        // document served from now on.
        generationAtPrepare = generation
        navigator.viewModel.server.injectsScrollFrameAdapter = true
        initialJump = initialLocator?.let { jumpFactory(it) }
        initialIndex = initialLocator?.let { indexOfHref(it.href.removeFragment()) } ?: 0
    }

    /**
     * Creates every reading-order frame once the shell document can host them.
     *
     * Preparation does not wait for a laid-out reader viewport: the reading screen only gives
     * the surface its height once the book is ready, so waiting on it would deadlock. The
     * frames are measured again — with the reader viewport then known — as soon as the surface
     * is laid out (see [onSizeChanged]).
     */
    private fun createFramesIfMeasurable() {
        if (!shellLoaded || framesCreated) return
        framesCreated = true
        loadShellFrames(generationAtPrepare)
    }

    private fun loadShellFrames(gen: Int) {
        if (gen != generation || _state.value != ContinuousBookState.Preparing) return
        val host = navigator.bookHost
        Log.d(TAG, "loadShellFrames for ${resources.size} resources")
        val js = StringBuilder("if (window.QuireShellHost) {")
        resources.forEachIndexed { index, link ->
            val servedUrl = host.urlTo(link)
            val href = hrefs[index].toString()
            js.append(
                "QuireShellHost.addFrame($index, ${JSONObject.quote(servedUrl.toString())}, " +
                    "${JSONObject.quote(href)}, ${host.positionCount(index)});",
            )
        }
        js.append("QuireShellHost.initialWindow($initialIndex);")
        js.append("}")
        shell.evaluateJavascript(js.toString(), null)
    }

    /** Called from the shell bridge when a frame loaded and measured its content. */
    private fun onFrameLoaded(href: String, height: Double, gen: Int) {
        if (gen != generation) return
        val index = indexOfHref(Url(href) ?: return) ?: return
        val runner = runners.getOrNull(index) ?: return
        runner.markLoaded(height)
        // The document is live: run its Readium initialization (CSS properties, decoration
        // templates, saved decorations) exactly as the paged path does, so decorations are
        // styles the frame knows about before any of them is applied.
        navigator.bookHost.onResourceLoaded(resources[index])
        // The shell settles fonts and images before it reports the frame.
        scope.launch { runner.settle() }
    }

    /** A document left the live window: its runner is no longer addressable until it loads again. */
    private fun onFrameEvicted(href: String, gen: Int) {
        if (gen != generation) return
        val index = indexOfHref(Url(href) ?: return) ?: return
        runners.getOrNull(index)?.markUnloaded()
    }

    /** The shell's initial window settled: the documents around the start position are live. */
    private fun markReady(gen: Int) {
        if (gen != generation) return
        if (_state.value != ContinuousBookState.Preparing) return
        commitGeometry()
        Log.d(TAG, "book ready: ${resources.size} resources, initial window at #$initialIndex")
        _state.value = ContinuousBookState.Ready
        navigator.bookHost.onBookReady()
        initialJump?.invoke()
        initialJump = null
    }

    /** Terminal failure of a required document or the reader runtime. */
    private fun failBook(message: String, gen: Int) {
        if (gen != generation) return
        if (_state.value is ContinuousBookState.Failed) return
        _state.value = ContinuousBookState.Failed(message)
        navigator.bookHost.onBookFailed(message)
    }

    /** Commits the geometry table from the frames' current heights (CSS px). */
    private fun commitGeometry() {
        val newHeights = runners.map { it.contentHeight }
        val newTops = ArrayList<Double>(newHeights.size)
        var running = 0.0
        for (h in newHeights) {
            newTops.add(running)
            running += h
        }
        heights = newHeights
        tops = newTops
    }

    // ── ScriptRunner surface (per original resource) ────────────────────────────

    /** One original frame href -> its runner. Null when the href is not part of the book. */
    fun runnerFor(href: Url): FrameRunner? {
        val index = indexOfHref(href) ?: return null
        return runners[index]
    }

    /**
     * Runs [block] with the runner of [href] loaded, loading its document first if it is outside
     * the live window and keeping it loaded until [block] returns. Null when the resource is not in
     * the book, the surface is gone, or the document did not load in time.
     */
    suspend fun <T> withFrame(href: Url, block: suspend (FrameRunner) -> T): T? {
        val index = indexOfHref(href) ?: return null
        val runner = runners[index]
        pinFrame(hrefs[index], true)
        try {
            if (withTimeoutOrNull(FRAME_LOAD_TIMEOUT_MS) { runner.awaitLoaded() } == null) {
                Log.w(TAG, "withFrame: ${hrefs[index]} did not load in ${FRAME_LOAD_TIMEOUT_MS}ms")
                return null
            }
            return block(runner)
        } finally {
            pinFrame(hrefs[index], false)
        }
    }

    /** How many documents the shell currently holds (loading or live): the size of the live window. For tests. */
    internal suspend fun liveFrameCount(): Int = withContext(Dispatchers.Main.immediate) {
        suspendCoroutine { cont ->
            shell.evaluateJavascript("window.QuireShellHost ? QuireShellHost.liveCount() : -1") { result ->
                cont.resume(result?.trim()?.toIntOrNull() ?: -1)
            }
        }
    }

    /** How many resources the shell has measured so far; the background pass is done at the book's size. For tests. */
    internal suspend fun measuredFrameCount(): Int = withContext(Dispatchers.Main.immediate) {
        suspendCoroutine { cont ->
            shell.evaluateJavascript("window.QuireShellHost ? QuireShellHost.measuredCount() : -1") { result ->
                cont.resume(result?.trim()?.toIntOrNull() ?: -1)
            }
        }
    }

    /** Asks the shell to keep (or stop keeping) the document of [href] loaded. */
    private fun pinFrame(href: Url, pinned: Boolean) {
        val gen = generation
        shell.post {
            if (gen != generation) return@post
            val call = if (pinned) "pin" else "unpin"
            shell.evaluateJavascript("window.QuireShellHost && QuireShellHost.$call(${JSONObject.quote(href.toString())});", null)
        }
    }

    /** The runner the outer viewport's reading position is in, once ready. */
    fun activeRunner(): FrameRunner? {
        if (_state.value != ContinuousBookState.Ready) return null
        val y = outerScrollY()
        val index = resourceIndexAt(y) ?: return runners.firstOrNull()
        return runners.getOrNull(index)
    }

    /** Runs [action] for every prepared (loaded) frame runner. */
    fun forEachLoadedRunner(action: (FrameRunner) -> Unit) {
        runners.forEach { if (it.isLoaded.value) action(it) }
    }

    private fun indexOfHref(href: Url): Int? {
        val clean = href.removeFragment().toString()
        return hrefs.indexOfFirst { it.toString() == clean }.takeIf { it >= 0 }
            // Tolerate hrefs spelled with a leading slash.
            ?: hrefs.indexOfFirst { it.toString() == clean.removePrefix("/") }.takeIf { it >= 0 }
    }

    // ── geometry and coordinates ────────────────────────────────────────────────

    /** Outer scroll position in CSS px (the shell document's scrollY). */
    fun outerScrollY(): Double = scrollYSync

    /** One CSS-pixel geometry read from the shell, batched over the JS bridge. */
    private var scrollYSync = 0.0

    /** True while a throttled locator publication is already scheduled. */
    private var progressionNotifyPending = false

    /**
     * The index of the resource whose span contains book position [scrollY], or null before readiness.
     *
     * The position is read [SEAM_TOLERANCE_CSS] below the viewport top: a jump to a chapter start can
     * land a fraction of a pixel above the seam (scroll offsets are snapped to device pixels), and that
     * must still report the chapter the reader is looking at, not the last page of the one before it.
     */
    fun resourceIndexAt(scrollY: Double): Int? {
        if (_state.value != ContinuousBookState.Ready) return null
        val y = scrollY + SEAM_TOLERANCE_CSS
        // Half-open intervals [top, top+height); zero-height resources are skipped by
        // construction (their interval is empty); the book end clamps to the last
        // readable resource.
        for (i in runners.indices) {
            val top = tops[i]
            val h = heights[i]
            if (h <= 0) continue
            if (y >= top && y < top + h) return i
        }
        return when {
            y < 0 -> 0
            runners.isNotEmpty() -> runners.indices.last { heights[it] > 0 }
            else -> null
        }
    }

    /** Progression within the resource covering [y], per Readium's scroll-mode convention. */
    fun progressionAt(y: Double): Double {
        val index = resourceIndexAt(y) ?: return 0.0
        val h = heights[index]
        if (h <= 0) return 0.0
        return ((y - tops[index]) / h).coerceIn(0.0, 1.0)
    }

    /**
     * Converts a frame-local CSS-px rectangle of the resource at [href] into this view's
     * coordinates, exactly once: book position plus local offset minus outer scroll,
     * then a single CSS-px -> Android-px scale.
     */
    fun frameRectToView(href: Url, local: RectF): RectF? {
        val index = indexOfHref(href) ?: return null
        val top = tops.getOrNull(index) ?: return null
        val scale = context.resources.displayMetrics.density
        val rect = RectF(local)
        rect.offset(0f, (top - outerScrollY()).toFloat())
        rect.scale(scale)
        return rect
    }

    private fun RectF.scale(scale: Float) {
        left *= scale; top *= scale; right *= scale; bottom *= scale
    }

    // ── navigation ──────────────────────────────────────────────────────────────

    private fun jumpFactory(locator: Locator): () -> Unit = {
        val href = locator.href.removeFragment()
        val index = indexOfHref(href)
        if (index != null) {
            pendingJump = PendingJump(
                href = hrefs[index],
                htmlId = locator.locations.htmlId,
                progression = locator.locations.progression ?: 0.0,
                decoration = null,
            )
            landPendingJump()
        }
    }

    /**
     * Navigates to [locator] within the prepared surface: resolves the target inside
     * its original frame, then scrolls the outer surface. Keeps the latest request.
     */
    fun go(locator: Locator): Boolean {
        if (_state.value != ContinuousBookState.Ready) {
            initialJump = jumpFactory(locator)
            return true
        }
        val href = locator.href.removeFragment()
        val index = indexOfHref(href) ?: return false
        pendingJump = PendingJump(
            href = hrefs[index],
            htmlId = locator.locations.htmlId,
            progression = locator.locations.progression ?: 0.0,
            decoration = null,
        )
        landPendingJump()
        return true
    }

    /** Scrolls the first decoration of [group] in the resource at [href] into view. */
    suspend fun scrollToDecoration(group: String, href: Url): Boolean {
        if (_state.value != ContinuousBookState.Ready) return false
        val index = indexOfHref(href) ?: return false
        return withFrame(href) { runner ->
            val within = runner.offsetTopForDecoration(group) ?: return@withFrame false
            jumpToResourceOffset(hrefs[index], within - viewportHeightCss / 4.0)
            true
        } ?: false
    }

    /**
     * Scrolls to [withinCss] inside the resource at [href]. The offset is resolved by the shell from
     * its own slot heights, which are always current; the native table trails them by a geometry
     * batch and is wrong right after the target's measurement rescaled the estimates above it.
     */
    private fun jumpToResourceOffset(href: Url, withinCss: Double) {
        val gen = generation
        shell.post {
            if (gen != generation) return@post
            shell.evaluateJavascript(
                "window.QuireShellHost && QuireShellHost.scrollToOffset(${JSONObject.quote(href.toString())}, $withinCss);",
                null,
            )
        }
    }

    /**
     * Moves the outer surface by one reader viewport, clamped to the prepared book.
     * False when there is nothing to scroll (before readiness or at the book edge).
     */
    private fun pageBy(deltaViewports: Int): Boolean {
        if (_state.value != ContinuousBookState.Ready) return false
        val scale = context.resources.displayMetrics.density
        val current = shell.scrollY
        val target = (current + deltaViewports * viewportHeightCss * scale).toInt()
        val max = (bookHeight * scale).toInt() - shell.height
        val clamped = target.coerceIn(0, max.coerceAtLeast(0))
        if (clamped == current) return false
        shell.post { shell.scrollTo(0, clamped) }
        navigator.bookHost.onProgressionChanged()
        return true
    }

    /** One reader-viewport step forwards within the prepared book. */
    fun pageForward(): Boolean = pageBy(1)

    /** One reader-viewport step backwards within the prepared book. */
    fun pageBackward(): Boolean = pageBy(-1)

    /** Lands the current [PendingJump] inside its frame, then scrolls the book. */
    private fun landPendingJump() {
        val jump = pendingJump ?: return
        pendingJump = null
        val index = indexOfHref(jump.href) ?: return
        scope.launch {
            withFrame(jump.href) { runner ->
                if (_state.value != ContinuousBookState.Ready) return@withFrame
                val resolved = jump.htmlId?.let { runner.offsetTopForId(it) }
                val within = resolved
                    ?: (jump.progression.coerceIn(0.0, 1.0) * runner.contentHeight)
                jump.decoration?.invoke(jump.href)
                jumpToResourceOffset(jump.href, within)
            }
        }
    }

    // ── reflow ──────────────────────────────────────────────────────────────────

    /**
     * Captures the visible resource's text anchor and viewport-relative offset before a
     * typography/theme/viewport change, to restore once after the reflow commits.
     */
    data class ReflowAnchor(val href: Url, val textAnchorScript: String?, val localOffset: Double?, val progression: Double?)

    /** Captures the anchor of what the reader currently sees. */
    suspend fun captureAnchor(): ReflowAnchor? {
        if (_state.value != ContinuousBookState.Ready) return null
        // One shell evaluation answers where the reader is and which block is at the viewport top, from
        // the shell's own heights (the native table trails them by a batch). It must be a single
        // round trip: a reflow's style change is queued right behind it.
        val position = shellCapture() ?: return null
        val index = indexOfHref(Url(position.optString("href")) ?: return null) ?: return null
        val local = position.optDouble("within")
        val height = position.optDouble("height").takeIf { it > 0 } ?: 1.0
        val selector = position.optJSONObject("anchor")?.optString("selector")?.takeIf { it.isNotEmpty() }
        return if (selector != null) {
            ReflowAnchor(hrefs[index], selector, local, null)
        } else {
            ReflowAnchor(hrefs[index], null, local, local / height)
        }
    }

    /** The shell's `captureAnchor()` answer: `{href, within, height, anchor: {selector, top} | null}`. */
    private suspend fun shellCapture(): JSONObject? = withContext(Dispatchers.Main.immediate) {
        suspendCoroutine { cont ->
            shell.evaluateJavascript("JSON.stringify(window.QuireShellHost ? QuireShellHost.captureAnchor() : null)") { result ->
                cont.resume(result.frameResultJson())
            }
        }
    }

    /**
     * Restores [anchor] exactly once after the layout generation [targetGeneration]
     * committed: every frame is remeasured, geometry recommitted, then the anchor (or its
     * progression fallback) is brought back under the viewport top. Suspending keeps the
     * caller's serialized pass in charge; the wait is for *this* reflow's generation —
     * computed before the remeasure was posted — so a fast commit is never mistaken for
     * the next reflow's.
     */
    suspend fun restoreAnchor(anchor: ReflowAnchor?, targetGeneration: Int) {
        if (_state.value != ContinuousBookState.Ready) return
        awaitLayoutGeneration(targetGeneration)
        if (_state.value != ContinuousBookState.Ready) return
        commitGeometry()
        val index = anchor?.let { indexOfHref(it.href) } ?: resourceIndexAt(outerScrollY()) ?: return
        val runner = runners[index]
        val anchorOffset = anchor?.textAnchorScript?.let { script ->
            runCatching { runner.runJavaScriptSuspend(ANCHOR_RESTORE_PREFIX + script + ANCHOR_RESTORE_SUFFIX) }
                .getOrNull()
                ?.trim('"')
                ?.toDoubleOrNull()
        }
        val within = when {
            anchorOffset != null && anchorOffset >= 0 -> anchorOffset
            anchor?.localOffset != null && anchor.localOffset!! <= heights[index] -> anchor.localOffset!!
            anchor?.progression != null -> anchor.progression!! * heights[index]
            else -> 0.0
        }
        jumpToResourceOffset(hrefs[index], within)
    }

    /**
     * Waits until the committed layout generation is at or past [target]. The wait is
     * bounded: a surface that died before committing releases the caller instead of
     * hanging the serialized apply pass.
     */
    private suspend fun awaitLayoutGeneration(target: Int) {
        withTimeoutOrNull(LAYOUT_COMMIT_TIMEOUT_MS) {
            layoutGenerationFlow.first { it >= target }
        } ?: Log.w(TAG, "layout generation $target did not commit within ${LAYOUT_COMMIT_TIMEOUT_MS}ms")
    }

    private val layoutGenerationFlow = MutableStateFlow(layoutGeneration)

    /**
     * Remeasures every loaded frame (fonts, images, viewport change) in one batch. Returns
     * the layout generation this remeasure will commit, computed *before* the request is
     * posted, so the anchor restore can wait for exactly this commit — a fast remeasure
     * (nothing changed) must never be mistaken for the next reflow's. Concurrent
     * remeasures are serialized: one cannot start until the previous one has committed.
     */
    suspend fun remeasureAllFrames(): Int = remeasureMutex.withLock {
        val target = layoutGeneration + 1
        val gen = generation
        if (gen == generation && _state.value == ContinuousBookState.Ready) {
            withContext(Dispatchers.Main.immediate) {
                shell.evaluateJavascript("window.QuireShellHost && QuireShellHost.remeasureAll();", null)
            }
            withTimeoutOrNull(LAYOUT_COMMIT_TIMEOUT_MS) {
                layoutGenerationFlow.first { it >= target }
            } ?: Log.w(TAG, "remeasure to generation $target did not commit in time")
        }
        target
    }

    // ── shell events ────────────────────────────────────────────────────────────

    /** Bridge bound as `QuireShell` in the shell document. */
    inner class ShellBridge {

        @JavascriptInterface
        fun event(json: String) {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
            val gen = generation
            if (!json.startsWith("{\"kind\":\"geometry\"")) Log.d(TAG, "shell event: ${json.take(200)}")
            when (obj.optString("kind")) {
                "frameEvicted" -> post { onFrameEvicted(obj.optString("href"), gen) }
                "frameLoaded" -> post {
                    val href = obj.optString("href")
                    val height = obj.optDouble("height", 0.0)
                    // The document is loaded and laid out; mark it so frame-local scripts
                    // can run, then settle images/fonts before book readiness.
                    onFrameLoaded(href, height, gen)
                }
                "ready" -> post {
                    applyGeometry(obj.optJSONObject("heights") ?: JSONObject(), gen)
                    markReady(gen)
                }
                "error" -> post {
                    failBook(obj.optString("message", "a chapter failed to load"), gen)
                }
                "geometry" -> post {
                    applyGeometry(obj.optJSONObject("heights") ?: JSONObject(), gen)
                }
                "remeasured" -> post {
                    // Every frame answered the re-measure: this is the layout generation the
                    // reflow's anchor restore waits for.
                    if (gen == generation) {
                        layoutGeneration++
                        layoutGenerationFlow.value = layoutGeneration
                    }
                }
                "scroll" -> post { navigator.bookHost.onProgressionChanged() }
            }
        }

        /** Reader viewport dimensions, asked once per frame for Readium's viewport math. */
        @JavascriptInterface
        fun getViewportWidth(): Int = viewportWidthCss

        @JavascriptInterface
        fun getViewportHeight(): Int = viewportHeightCss
    }

    /**
     * Bridge bound as `QuireBook` on the shell document; the frame adapters reach it
     * through `window.parent`. Every call carries the originating resource's href so
     * events address the right document, and stale/unregistered targets are rejected.
     */
    inner class QuireBookBridge {

        /** Canonicalizes and validates a frame href; null for stale/unregistered targets. */
        private fun canonical(href: String): Url? {
            if (generation != generationAtPrepare) return null
            val url = Url(href) ?: return null
            return when (val index = indexOfHref(url)) {
                null -> null
                else -> hrefs[index]
            }
        }

        @JavascriptInterface
        fun log(href: String, message: String) {
            Timber.d("JavaScript [$href]: $message")
        }

        @JavascriptInterface
        fun logError(href: String, message: String, filename: String, line: Int) {
            Timber.e("JavaScript error [$href]: $filename:$line $message")
        }

        @JavascriptInterface
        fun onTap(href: String, eventJson: String): Boolean {
            canonical(href) ?: return false
            val host = navigator.bookHost
            val event = runCatching { com.quire.reader.navigator.R2BasicWebView.TapEvent.fromJSON(eventJson) }.getOrNull() ?: return false
            if (event.defaultPrevented) return false
            // Tap points are frame-local; convert once into shell coordinates.
            val point = framePointToShell(href, event.point)
            return host.onTap(point)
        }

        @JavascriptInterface
        fun onDrag(href: String, phase: String, eventJson: String): Boolean {
            canonical(href) ?: return false
            val host = navigator.bookHost
            val event = runCatching { com.quire.reader.navigator.R2BasicWebView.DragEvent.fromJSON(eventJson) }.getOrNull() ?: return false
            if (!event.isValid) return false
            val type = when (phase) {
                "start" -> ContinuousBookWebView.DragType.Start
                "move" -> ContinuousBookWebView.DragType.Move
                "end" -> ContinuousBookWebView.DragType.End
                else -> return false
            }
            val start = framePointToShell(href, event.startPoint)
            return host.onDrag(type, start, event.offset)
        }

        @JavascriptInterface
        fun onKey(href: String, eventJson: String): Boolean {
            canonical(href) ?: return false
            // Key events carry no coordinates; forward as-is to the input pipeline.
            return navigator.forwardKeyEventFromFrame(eventJson)
        }

        @JavascriptInterface
        fun onSelectionStart(href: String) {
            val origin = canonical(href) ?: return
            // The selection's document must stay loaded for as long as the selection lives: the
            // copy/highlight/note actions address it even if the user scrolls far away.
            if (selectionHref != origin) {
                selectionHref?.let { pinFrame(it, false) }
                pinFrame(origin, true)
            }
            selectionHref = origin
        }

        @JavascriptInterface
        fun onSelectionEnd(href: String) {
            // Keep the originating href: the selection's document is the one the
            // copy/highlight/note actions must address, even if the user then scrolls.
            onSelectionStart(href)
        }

        @JavascriptInterface
        fun onDecorationActivated(href: String, eventJson: String): Boolean {
            val origin = canonical(href) ?: return false
            val obj = runCatching { JSONObject(eventJson) }.getOrNull() ?: return false
            val id = obj.optNullableString("id") ?: return false
            val group = obj.optNullableString("group") ?: return false
            val rect = obj.optRectF("rect") ?: return false
            val click = com.quire.reader.navigator.R2BasicWebView.TapEvent.fromJSONObject(obj.optJSONObject("click")) ?: return false
            val point = framePointToShell(href, click.point)
            val shellRect = frameRectToView(origin, rect) ?: return false
            return navigator.bookHost.onDecorationActivated(id, group, shellRect, point, origin)
        }

        /** The href of the resource holding the active text selection, if any. */
        val activeSelectionHref: Url? get() = selectionHref
    }

    /** The href of the resource holding the active text selection, if any. */
    fun activeSelectionHref(): Url? = selectionHref

    /** Forgets the recorded selection origin (after the selection was cleared). */
    fun clearSelectionHref() {
        selectionHref?.let { pinFrame(it, false) }
        selectionHref = null
    }

    /** The runner owning the active selection's resource, or null when none is recorded. */
    fun selectionRunner(): FrameRunner? = selectionHref?.let { runnerFor(it) }

    private var selectionHref: Url? = null

    /** Converts a frame-local point into shell-document coordinates, exactly once. */
    private fun framePointToShell(href: String, point: PointF): PointF {
        val url = Url(href) ?: return point
        val index = indexOfHref(url) ?: return point
        val top = tops.getOrNull(index) ?: return point
        return PointF(point.x, point.y + top.toFloat())
    }

    /**
     * The shell WebView, with the same text-selection menu handling as the paged
     * `R2BasicWebView`: without this the frame selection shows Android's system menu
     * instead of the app's Highlight/Note/Copy actions.
     */
    private inner class ShellWebView(context: Context) : WebView(context) {

        override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
            val custom = navigator.bookHost.selectionActionModeCallback
                ?: return super.startActionMode(callback)
            val parent = parent ?: return null
            return parent.startActionModeForChild(this, custom)
        }

        override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
            val custom = navigator.bookHost.selectionActionModeCallback
                ?: return super.startActionMode(callback, type)
            val parent = parent ?: return null
            return parent.startActionModeForChild(this, Callback2Wrapper(custom, callback as? ActionMode.Callback2), type)
        }
    }

    /** Keeps `ActionMode.Callback2`'s content rectangle (selection menu placement) intact. */
    private class Callback2Wrapper(
        private val callback: ActionMode.Callback,
        private val callback2: ActionMode.Callback2?,
    ) : ActionMode.Callback by callback, ActionMode.Callback2() {
        override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect?) =
            callback2?.onGetContentRect(mode, view, outRect)
                ?: super.onGetContentRect(mode, view, outRect)
    }

    private fun post(block: () -> Unit) { shell.post(block) }

    private fun applyGeometry(heights: JSONObject, gen: Int) {
        if (gen != generation) return
        for (i in runners.indices) {
            val href = hrefs[i].toString()
            if (heights.has(href)) {
                runners[i].contentHeight = heights.getDouble(href)
            }
        }
        commitGeometry()
        if (_state.value == ContinuousBookState.Ready) notifyProgressionThrottled()
    }

    // ── disposal ────────────────────────────────────────────────────────────────

    /** Closes the surface: generation bump, callback rejection, WebView teardown. */
    fun dispose() {
        Log.d(TAG, "dispose surface #${System.identityHashCode(this)}")
        generation++
        layoutGeneration++
        layoutGenerationFlow.value = layoutGeneration
        _state.value = ContinuousBookState.Disposed
        runners.forEach { it.dispose() }
        scope.cancel()
        shell.post {
            shell.stopLoading()
            shell.removeJavascriptInterface("QuireShell")
        }
        removeAllViews()
        shell.destroy()
    }

    // Note: disposal happens explicitly from resetContainer/onDestroyView. Detaching from the
    // window does NOT dispose the surface: a transient detach (fragment re-layout, container
    // reparenting) must not destroy the prepared book.

    // ── per-frame scripting ─────────────────────────────────────────────────────

    /**
     * One reading-order resource: its document inside the shell, its loaded/settled
     * state, and frame-local script execution. The runner's `loaded` state represents
     * the resource's initialization; book readiness is the barrier above.
     */
    inner class FrameRunner(private val link: Link) : com.quire.reader.navigator.ScriptRunner {

        val href: Url = link.url().removeFragment()

        private val _loaded = MutableStateFlow(false)
        override val isLoaded: StateFlow<Boolean> = _loaded.asStateFlow()

        /** Loaded AND settled: the shell has laid out fonts and images; false again once the document is unloaded. */
        val settled = MutableStateFlow(false)

        /** Content height of this resource, in CSS px. */
        var contentHeight: Double = 0.0

        override suspend fun awaitLoaded() {
            isLoaded.first { it }
        }

        /** Marks the document loaded; static images and fonts are awaited by [settle]. */
        fun markLoaded(height: Double) {
            contentHeight = height
            _loaded.value = true
        }

        /** Marks the document unloaded: it left the live window and must be loaded again to be addressed. */
        fun markUnloaded() {
            _loaded.value = false
            settled.value = false
        }

        /**
         * Marks the frame settled. The shell document settles fonts/images itself
         * before reporting `frameLoaded`, so by the time the native side sees it the
         * layout inputs are final; the Kotlin side only tracks the flag.
         */
        suspend fun settle() {
            if (settled.value) return
            settled.value = true
        }

        override fun runJavaScript(script: String, callback: ((String) -> Unit)?) {
            if (!_loaded.value) {
                // Queue until loaded, mirroring the chapter view's contract.
                scope.launch {
                    awaitLoaded()
                    runJavaScript(script, callback)
                }
                return
            }
            shell.post {
                runInFrame(script, callback)
            }
        }

        override suspend fun runJavaScriptSuspend(javascript: String): String = suspendCoroutine { cont ->
            runJavaScript(javascript) { result -> cont.resume(result) }
        }

        /** Evaluates [script] inside this frame's contentWindow, synchronously by href. */
        private fun runInFrame(script: String, callback: ((String) -> Unit)?) {
            // The shell document (same-origin with the frames) performs the eval inside
            // the target frame's contentWindow and returns the result JSON-encoded exactly
            // once (`JSON.stringify(r)`), which is what a WebView's own evaluateJavascript
            // does for direct evaluations: objects arrive as raw JSON text (`{...}`) and
            // primitives as their JSON literal. Double-encoding would hand consumers a
            // *quoted* JSON string, and `JSONObject(result)` — as the paged path calls it
            // for `readium.getCurrentSelection()` — would silently fail.
            val js = buildString {
                append("(function(){var f=window.QuireShellHost?QuireShellHost.frameByHref(")
                append(JSONObject.quote(href.toString()))
                append("):null;if(!f)return '\"error: no frame\"';var r;try{r=f.contentWindow.eval(")
                append(JSONObject.quote(script))
                append(")}catch(e){r='error: '+e}")
                append("if(r===undefined)return JSON.stringify(null);")
                append("return JSON.stringify(r)})()")
            }
            shell.evaluateJavascript(js) { result ->
                // `result` is the shell WebView's JSON serialization of the wrapper's string
                // answer; unwrapping one JSON layer yields that answer's own JSON text.
                val decoded = runCatching {
                    JSONTokener(result ?: "null").nextValue() as? String
                }.getOrNull()
                callback?.invoke(decoded ?: "null")
            }
        }

        /** Vertical offset of the first decoration of [group] in this frame, CSS px. */
        suspend fun offsetTopForDecoration(group: String): Double? {
            val script =
                "(function () {" +
                    " var items = window.readium ? window.readium.getDecorations(${JSONObject.quote(group)}).items : [];" +
                    " if (!items.length) return null;" +
                    " return { top: items[0].range.getBoundingClientRect().top + window.pageYOffset }; })()"
            return runJavaScriptSuspend(script).frameResultTop()
        }

        /** Vertical offset of the element with [htmlId] in this frame, CSS px. */
        suspend fun offsetTopForId(htmlId: String): Double? {
            val escaped = JSONObject.quote(htmlId)
            val script =
                "(function () {" +
                    " var el = document.getElementById($escaped) || document.getElementsByName($escaped)[0];" +
                    " if (!el) return null;" +
                    " return { top: el.getBoundingClientRect().top + window.pageYOffset }; })()"
            return runJavaScriptSuspend(script).frameResultTop()
        }

        fun dispose() {
            // Nothing per-frame: the shell document owns the iframes and dies with the WebView.
        }
    }
}
