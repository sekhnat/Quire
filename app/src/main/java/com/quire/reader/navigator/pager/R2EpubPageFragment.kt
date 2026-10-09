/*
 * Module: r2-navigator-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann, Mostapha Idoubihi, Paul Stoica
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.


@file:OptIn(InternalReadiumApi::class)

package com.quire.reader.navigator.pager

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.*
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.postDelayed
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import com.quire.reader.R
import com.quire.reader.navigator.R2BasicWebView
import com.quire.reader.navigator.R2WebView
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import com.quire.reader.navigator.hardenForReaderContent
import com.quire.reader.navigator.epub.EpubNavigatorViewModel
import com.quire.reader.navigator.extensions.htmlId
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl

@OptIn(ExperimentalReadiumApi::class)
internal class R2EpubPageFragment : Fragment(), com.quire.reader.navigator.ScriptRunner {

    private val resourceUrl: AbsoluteUrl?
        get() = BundleCompat.getParcelable(requireArguments(), "url", AbsoluteUrl::class.java)

    internal val link: Link?
        get() = BundleCompat.getParcelable(requireArguments(), "link", Link::class.java)

    private var pendingLocator: Locator? = null

    private val positionCount: Long
        get() = requireArguments().getLong("positionCount")

    var webView: R2WebView? = null
        private set

    private lateinit var containerView: View
    private val viewModel: EpubNavigatorViewModel by viewModels(
        ownerProducer = { requireParentFragment() }
    )


    private var isLoading: Boolean = false
    private val _isLoaded = MutableStateFlow(false)

    internal fun setFontSize(fontSize: Double) {
        textZoom = (fontSize * 100).roundToInt()
    }

    private var textZoom: Int = 100
        set(value) {
            field = value
            webView?.settings?.textZoom = value
        }

    /**
     * Indicates whether the resource is fully loaded in the web view.
     */
    @InternalReadiumApi
    override val isLoaded: StateFlow<Boolean>
        get() = _isLoaded.asStateFlow()

    /**
     * Waits for the page to be loaded.
     */
    @InternalReadiumApi
    override suspend fun awaitLoaded() {
        isLoaded.first { it }
    }

    /**
     * Suspends until the WebView paints a frame after the current style changes, so a
     * position-preserving re-scroll sees the re-laid-out content and not the previous
     * layout. Bounded: a view that never paints again releases the caller.
     */
    internal suspend fun awaitVisualStateUpdate() {
        val view = webView ?: return
        withTimeoutOrNull(VISUAL_STATE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                WebViewCompat.postVisualStateCallback(view, 0) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private val navigator: EpubNavigatorFragment?
        get() = parentFragment as? EpubNavigatorFragment

    private val shouldApplyInsetsPadding: Boolean
        get() = navigator?.config?.shouldApplyInsetsPadding ?: true

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(textZoomBundleKey, textZoom)

        super.onSaveInstanceState(outState)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        savedInstanceState
            ?.getInt(textZoomBundleKey)
            ?.takeIf { it > 0 }
            ?.let { textZoom = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingLocator = BundleCompat.getParcelable(
            requireArguments(),
            "initialLocator",
            Locator::class.java
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        containerView = inflater.inflate(R.layout.quire_navigator_viewpager_fragment_epub, container, false)

        val webView = containerView.findViewById<R2WebView>(R.id.webView)
        this.webView = webView

        webView.visibility = View.INVISIBLE
        navigator?.webViewListener?.let { listener ->
            webView.listener = listener

            link?.let { link ->
                // Setup custom Javascript interfaces. (Quire registers none; anything
                // registered here is exposed to untrusted EPUB content and must validate
                // its inputs like the "Android" bridge does.)
                for ((name, obj) in listener.javascriptInterfacesForResource(link)) {
                    if (obj != null) {
                        webView.addJavascriptInterface(obj, name)
                    }
                }
            }
        }

        webView.disablePageTurnsWhileScrolling =
            navigator?.config?.disablePageTurnsWhileScrolling ?: false
        webView.settings.javaScriptEnabled = true
        webView.settings.hardenForReaderContent()
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.textZoom = textZoom
        webView.resourceUrl = resourceUrl
        webView.setPadding(0, 0, 0, 0)
        // Upstream Readium contract: the WebView itself is the "Android" bridge. Its
        // @JavascriptInterface methods are the whole exposed surface — untrusted EPUB
        // content can call them, so each must validate its inputs (JSON payloads parsed
        // defensively, hrefs resolved against the publication only). Any future
        // @JavascriptInterface method added to R2BasicWebView must do the same.
        webView.addJavascriptInterface(webView, "Android")

        var endReached = false
        webView.setOnOverScrolledCallback(object : R2BasicWebView.OnOverScrolledCallback {
            override fun onOverScrolled(
                scrollX: Int,
                scrollY: Int,
                clampedX: Boolean,
                clampedY: Boolean,
            ) {
                activity ?: return
                val metrics = DisplayMetrics()

                val topDecile = webView.contentHeight - 1.15 * metrics.heightPixels
                val bottomDecile = (webView.contentHeight - metrics.heightPixels).toDouble()

                when (scrollY.toDouble()) {
                    in topDecile..bottomDecile -> {
                        if (!endReached) {
                            endReached = true
                            webView.listener?.onPageEnded(endReached)
                        }
                    }
                    else -> {
                        if (endReached) {
                            endReached = false
                            webView.listener?.onPageEnded(endReached)
                        }
                    }
                }
            }
        })

        webView.webViewClient = object : WebViewClientCompat() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                (webView as? R2BasicWebView)?.shouldOverrideUrlLoading(request) ?: false

            override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean {
                // Do something with the event here
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                onPageFinished()

                link?.let {
                    webView.listener?.onResourceLoaded(webView, it)
                }

                webView.onContentReady {
                    onLoadPage()
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                (webView as? R2BasicWebView)?.shouldInterceptRequest(view, request)
        }

        webView.isHapticFeedbackEnabled = false
        webView.isLongClickable = false
        webView.setOnLongClickListener {
            false
        }

        resourceUrl?.let { url ->
            isLoading = true
            _isLoaded.value = false
            val navigator = navigator
            val link = link
            if (navigator != null && link != null && navigator.holdsBack(link)) {
                // A neighbour of the page the book opens on: it loads once that page has shown.
                viewLifecycleOwner.lifecycleScope.launch {
                    navigator.awaitNeighbourTurn()
                    webView.loadUrl(url.toString())
                }
            } else {
                webView.loadUrl(url.toString())
            }
        }

        setupPadding()

        // Forward a tap event when the web view is not ready to propagate the taps. This allows
        // to toggle a navigation UI while a page is loading, for example.
        containerView.setOnClickListenerWithPoint { _, point ->
            webView.listener?.onTap(point)
        }

        return containerView
    }

    private var isPageFinished = false
    private val pendingPageFinished = mutableListOf<() -> Unit>()

    /**
     * Will run the given [action] when the content of the [WebView] is loaded.
     */
    fun whenPageFinished(action: () -> Unit) {
        if (isPageFinished) {
            action()
        } else {
            pendingPageFinished.add(action)
        }
    }

    private fun onPageFinished() {
        isPageFinished = true
        pendingPageFinished.forEach { it() }
        pendingPageFinished.clear()
    }

    /**
     * Will run the given [action] when the content of the [WebView] is fully laid out.
     */
    private fun WebView.onContentReady(action: () -> Unit) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) {
            WebViewCompat.postVisualStateCallback(this, 0) {
                action()
            }
        } else {
            // On older devices, there's no reliable way to guarantee the page is fully laid out.
            // As a workaround, we run a dummy JavaScript, then wait for a short delay before
            // assuming it's ready.
            evaluateJavascript("true") {
                postDelayed(500, action)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val lifecycleOwner = viewLifecycleOwner
        lifecycleOwner.lifecycleScope.launch {
            viewModel.isScrollEnabled
                .flowWithLifecycle(lifecycleOwner.lifecycle)
                .collectLatest { webView?.scrollModeFlow?.value = it }
        }
    }

    override fun onDestroyView() {
        webView?.listener = null
        super.onDestroyView()
    }

    override fun onDetach() {
        super.onDetach()

        // Prevent the web view from leaking when its parent is detached.
        // See https://stackoverflow.com/a/19391512/1474476
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }
    }

    private fun setupPadding() {
        updatePadding()

        // Update padding when the scroll mode changes
        viewLifecycleOwner.lifecycleScope.launch {
            webView?.scrollModeFlow?.collectLatest {
                updatePadding()
            }
        }

        if (shouldApplyInsetsPadding) {
            // Update padding when the window insets change, for example when the navigation and status
            // bars are toggled.
            ViewCompat.setOnApplyWindowInsetsListener(containerView) { _, insets ->
                updatePadding()
                insets
            }
        }
    }

    private fun updatePadding() {
        if (view == null) return

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val window = activity?.window ?: return@repeatOnLifecycle
                var top = 0
                var bottom = 0

                // Add additional padding to take into account the display cutout, if needed.
                if (
                    shouldApplyInsetsPadding &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
                    window.attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                ) {
                    // Request the display cutout insets from the decor view because the ones given by
                    // setOnApplyWindowInsetsListener are not always correct for preloaded views.
                    window.decorView.rootWindowInsets?.displayCutout?.let { displayCutoutInsets ->
                        top += displayCutoutInsets.safeInsetTop
                        bottom += displayCutoutInsets.safeInsetBottom
                    }
                }

                if (!viewModel.isScrollEnabled.value) {
                    val margin =
                        resources.getDimension(R.dimen.quire_navigator_epub_vertical_padding)
                            .toInt()
                    top += margin
                    bottom += margin
                }

                containerView.setPadding(0, top, 0, bottom)
            }
        }
    }

    internal val paddingTop: Int get() = containerView.paddingTop
    internal val paddingBottom: Int get() = containerView.paddingBottom

    private val isCurrentResource: Boolean get() {
        val epubNavigator = navigator ?: return false
        val currentFragment = (epubNavigator.resourcePager.adapter as? R2PagerAdapter)?.getCurrentFragment() as? R2EpubPageFragment ?: return false
        return tag == currentFragment.tag
    }

    private fun onLoadPage() {
        if (!isLoading) return
        isLoading = false
        _isLoaded.value = true

        if (view == null) return

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                val webView = requireNotNull(webView)
                webView.visibility = View.VISIBLE

                pendingLocator
                    ?.let { locator ->
                        loadLocator(
                            webView,
                            requireNotNull(navigator).overflow.value.readingProgression,
                            locator
                        )
                    }
                    .also { pendingLocator = null }

                link?.let {
                    webView.listener?.onPageLoaded(webView, it)
                }
            }
        }
    }

    internal fun loadLocator(locator: Locator) {
        if (!isLoaded.value) {
            pendingLocator = locator
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                val webView = requireNotNull(webView)
                val epubNavigator = requireNotNull(navigator)
                loadLocator(webView, epubNavigator.overflow.value.readingProgression, locator)
                webView.listener?.onProgressionChanged()
            }
        }
    }

    private suspend fun loadLocator(
        webView: R2WebView,
        readingProgression: ReadingProgression,
        locator: Locator,
    ) {
        if (locator.text.highlight != null) {
            if (webView.scrollToLocator(locator)) {
                return
            }
        }

        val htmlId = locator.locations.htmlId
        if (htmlId != null && webView.scrollToId(htmlId)) {
            return
        }

        var progression = locator.locations.progression ?: 0.0

        // We need to reverse the progression with RTL because the Web View
        // always scrolls from left to right, no matter the reading direction.
        progression =
            if (webView.scrollMode || readingProgression == ReadingProgression.LTR) {
                progression
            } else {
                1 - progression
            }

        if (webView.scrollMode) {
            webView.scrollToPosition(progression)
        } else {
            // Figure out the target web view "page" from the requested
            // progression.
            var item = (progression * webView.numPages).roundToInt()
            if (readingProgression == ReadingProgression.RTL && item > 0) {
                item -= 1
            }
            webView.setCurrentItem(item, false)
        }
    }

    override fun runJavaScript(script: String, callback: ((String) -> Unit)?) {
        whenPageFinished {
            requireNotNull(webView).runJavaScript(script, callback)
        }
    }

    override suspend fun runJavaScriptSuspend(javascript: String): String = suspendCoroutine { cont ->
        runJavaScript(javascript) { result ->
            cont.resume(result)
        }
    }

    companion object {
        private const val textZoomBundleKey = "org.readium.textZoom"

        /** How long a reflow waits for the WebView to paint the re-laid-out content. */
        private const val VISUAL_STATE_TIMEOUT_MS = 2_000L

        fun newInstance(
            url: AbsoluteUrl,
            link: Link? = null,
            initialLocator: Locator? = null,
            positionCount: Int = 0,
        ): R2EpubPageFragment =
            R2EpubPageFragment().apply {
                arguments = Bundle().apply {
                    putParcelable("url", url)
                    putParcelable("link", link)
                    putParcelable("initialLocator", initialLocator)
                    putLong("positionCount", positionCount.toLong())
                }
            }
    }
}

/**
 * Same as setOnClickListener, but will also report the tap point in the view.
 */
private fun View.setOnClickListenerWithPoint(action: (View, PointF) -> Unit) {
    var point = PointF()

    setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_DOWN) {
            point = PointF(event.x, event.y)
        }
        false
    }

    setOnClickListener {
        action(it, point)
    }
}
