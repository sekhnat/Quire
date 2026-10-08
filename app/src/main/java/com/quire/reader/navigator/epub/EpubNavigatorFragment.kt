/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.


@file:OptIn(InternalReadiumApi::class)

package com.quire.reader.navigator.epub

import android.content.pm.ApplicationInfo
import android.graphics.PointF
import android.util.Log
import android.graphics.RectF
import android.os.Bundle
import android.util.LayoutDirection
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.collection.forEach
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.viewpager.widget.ViewPager
import kotlin.math.ceil
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorationId
import org.readium.r2.navigator.HyperlinkNavigator
import com.quire.reader.navigator.NavigatorFragment
import com.quire.reader.navigator.epub.EpubDefaults
import com.quire.reader.navigator.epub.EpubPreferences
import com.quire.reader.navigator.epub.EpubSettings
import org.readium.r2.navigator.OverflowableNavigator
import com.quire.reader.R
import com.quire.reader.navigator.R2BasicWebView
import com.quire.reader.navigator.R2WebView
import com.quire.reader.navigator.RestorationNotSupportedException
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.Selection
import com.quire.reader.navigator.dummyPublication
import com.quire.reader.navigator.extensions.htmlId
import com.quire.reader.navigator.epub.EpubNavigatorViewModel.RunScriptCommand
import com.quire.reader.navigator.epub.css.FontFamilyDeclaration
import com.quire.reader.navigator.epub.css.MutableFontFamilyDeclaration
import com.quire.reader.navigator.epub.css.RsProperties
import com.quire.reader.navigator.epub.css.buildFontFamilyDeclaration
import org.readium.r2.navigator.extensions.normalizeLocator
import com.quire.reader.navigator.extensions.optRectF
import com.quire.reader.navigator.extensions.positionsByResource
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import com.quire.reader.navigator.input.CompositeInputListener
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.InputModifier
import org.readium.r2.navigator.input.Key
import org.readium.r2.navigator.input.KeyEvent
import com.quire.reader.navigator.input.KeyInterceptorView
import org.readium.r2.navigator.input.TapEvent
import com.quire.reader.navigator.pager.R2EpubPageFragment
import com.quire.reader.navigator.pager.R2PagerAdapter
import com.quire.reader.navigator.pager.R2PagerAdapter.PageResource
import com.quire.reader.navigator.pager.R2ViewPager
import org.readium.r2.navigator.preferences.Configurable
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.util.createFragmentFactory
import org.readium.r2.shared.extensions.optNullableString
import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.extensions.tryOrLog
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource

/**
 * Factory for a [JavascriptInterface] which will be injected in the web views.
 *
 * Return `null` if you don't want to inject the interface for the given resource.
 */
public typealias JavascriptInterfaceFactory = (resource: Link) -> Any?

/**
 * Navigator for EPUB publications.
 *
 * To use this [Fragment], create a factory with `EpubNavigatorFragment.createFactory()`.
 */
@OptIn(ExperimentalReadiumApi::class, DelicateReadiumApi::class)
public class EpubNavigatorFragment internal constructor(
    publication: Publication,
    private val initialLocator: Locator?,
    readingOrder: List<Link>?,
    private val initialPreferences: EpubPreferences,
    internal val listener: Listener?,
    internal val paginationListener: PaginationListener?,
    layout: Layout,
    private val defaults: EpubDefaults,
    configuration: Configuration,
) : NavigatorFragment(publication),
    OverflowableNavigator,
    SelectableNavigator,
    DecorableNavigator,
    HyperlinkNavigator,
    Configurable<EpubSettings, EpubPreferences> {

    // Make a copy to prevent the user from modifying the configuration after initialization.
    internal val config: Configuration = configuration.copy().apply {
        servedAssets += "readium/.*"

        addFontFamilyDeclaration(FontFamily.OPEN_DYSLEXIC) {
            addFontFace {
                addSource("readium/fonts/OpenDyslexic-Regular.otf")
            }
        }
    }

    public data class Configuration internal constructor(

        /**
         * Patterns for asset paths which will be available to EPUB resources under
         * https://readium/assets/.
         *
         * The patterns can use simple glob wildcards, see:
         * https://developer.android.com/reference/android/os/PatternMatcher#PATTERN_SIMPLE_GLOB
         *
         * Use .* to serve all app assets.
         */
        @ExperimentalReadiumApi
        var servedAssets: List<String>,

        /**
         * Readium CSS reading system settings.
         *
         * See https://readium.org/readium-css/docs/CSS19-api.html#reading-system-styles
         */
        @ExperimentalReadiumApi
        var readiumCssRsProperties: RsProperties,

        /**
         * When disabled, the Android web view's `WebSettings.textZoom` will be used to adjust the
         * font size, instead of using the Readium CSS's `--USER__fontSize` variable.
         *
         * `WebSettings.textZoom` will work with more publications than `--USER__fontSize`, even the
         * ones poorly authored. However, the page width is not adjusted when changing the font
         * size to keep the optimal line length.
         *
         * See:
         *   - https://github.com/readium/mobile/issues/5
         *   - https://github.com/readium/mobile/issues/1#issuecomment-652431984
         */
        @ExperimentalReadiumApi
        @DelicateReadiumApi
        var useReadiumCssFontSize: Boolean = true,

        /**
         * Supported HTML decoration templates.
         */
        var decorationTemplates: HtmlDecorationTemplates,

        /**
         * Indicates if a user can swipe to change resources when scroll is enabled.
         */
        var disablePageTurnsWhileScrolling: Boolean,

        /**
         * Custom [ActionMode.Callback] to be used when the user selects content.
         *
         * Provide one if you want to customize the selection context menu items.
         */
        var selectionActionModeCallback: ActionMode.Callback?,

        /**
         * Whether padding accounting for display cutouts should be applied.
         */
        var shouldApplyInsetsPadding: Boolean?,

        /**
         * Disable user selection if the publication is protected by a DRM (e.g. with LCP).
         *
         * WARNING: If you choose to disable this, you MUST remove the Copy and Share selection
         * menu items in your app. Otherwise, you will void the EDRLab certification for your
         * application. If you need help, follow up on:
         * https://github.com/readium/kotlin-toolkit/issues/299#issuecomment-1315643577
         */
        @DelicateReadiumApi
        var disableSelectionWhenProtected: Boolean,

        internal var fontFamilyDeclarations: List<FontFamilyDeclaration>,
        internal var javascriptInterfaces: Map<String, JavascriptInterfaceFactory>,
    ) {
        public constructor(
            servedAssets: List<String> = emptyList(),
            readiumCssRsProperties: RsProperties = RsProperties(),
            decorationTemplates: HtmlDecorationTemplates = HtmlDecorationTemplates.defaultTemplates(),
            disablePageTurnsWhileScrolling: Boolean = false,
            selectionActionModeCallback: ActionMode.Callback? = null,
            shouldApplyInsetsPadding: Boolean? = true,
        ) : this(
            servedAssets = servedAssets,
            readiumCssRsProperties = readiumCssRsProperties,
            decorationTemplates = decorationTemplates,
            disablePageTurnsWhileScrolling = disablePageTurnsWhileScrolling,
            selectionActionModeCallback = selectionActionModeCallback,
            shouldApplyInsetsPadding = shouldApplyInsetsPadding,
            disableSelectionWhenProtected = true,
            fontFamilyDeclarations = emptyList(),
            javascriptInterfaces = emptyMap()
        )

        /**
         * Registers a new factory for the [JavascriptInterface] named [name].
         *
         * Return `null` in [factory] to prevent adding the Javascript interface for a given
         * resource.
         */
        public fun registerJavascriptInterface(name: String, factory: JavascriptInterfaceFactory) {
            javascriptInterfaces += name to factory
        }

        /**
         * Adds a declaration for [fontFamily] using [builderAction].
         *
         * @param alternates Specifies a list of alternative font families used as fallbacks when
         * symbols are missing from [fontFamily].
         */
        @ExperimentalReadiumApi
        public fun addFontFamilyDeclaration(
            fontFamily: FontFamily,
            alternates: List<FontFamily> = emptyList(),
            builderAction: (MutableFontFamilyDeclaration).() -> Unit,
        ) {
            fontFamilyDeclarations += buildFontFamilyDeclaration(
                fontFamily = fontFamily.name,
                alternates = alternates.map { it.name },
                builderAction = builderAction
            )
        }

        public companion object {
            public operator fun invoke(builder: Configuration.() -> Unit): Configuration =
                Configuration().apply(builder)
        }
    }

    public interface PaginationListener {
        public fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {}
        public fun onPageLoaded() {}
    }

    public interface Listener : OverflowableNavigator.Listener, HyperlinkNavigator.Listener

    private sealed class State {
        /** The navigator just started and didn't load any resource yet. */
        data object Initializing : State()

        /** The navigator is loading the first resource at `initialResourceHref`. */
        data class Loading(val initialResourceHref: Url) : State()

        /** The navigator is fully initialized and ready for action. */
        data object Ready : State()
    }

    /** Whole-book preparation state of the navigator. */
    public sealed interface Readiness {
        public data object Preparing : Readiness
        public data object Ready : Readiness
        public data class Failed(val error: String) : Readiness
    }

    private val _readiness = MutableStateFlow<Readiness>(Readiness.Preparing)

    /** Current whole-book readiness: ready only once the entire book is prepared. */
    public val readiness: StateFlow<Readiness> get() = _readiness

    /**
     * Waits until the whole book is prepared for reading. In scroll mode this is the
     * eager continuous surface's readiness barrier (no deadline: whole-book preparation
     * legitimately exceeds the old five-second page wait); in paged and fixed modes
     * the first loaded page readies the navigator. False on failure or disposal.
     */
    public suspend fun awaitWholeBookReadiness(): Boolean =
        readiness.first { it !is Readiness.Preparing } is Readiness.Ready

    /** Publishes [new] as the whole-book readiness unless the navigator is gone. */
    private fun publishReadiness(new: Readiness) {
        _readiness.value = new
    }

    private var state: State = State.Initializing

    // Configurable

    override val settings: StateFlow<EpubSettings> get() = viewModel.settings

    override fun submitPreferences(preferences: EpubPreferences) {
        viewModel.submitPreferences(preferences)
    }

    /**
     * Evaluates the given JavaScript on the currently visible HTML resource.
     *
     * Note that this only work with reflowable resources.
     */
    public suspend fun evaluateJavascript(script: String): String? {
        val page = activeScriptRunner() ?: return null
        page.awaitLoaded()
        return page.runJavaScriptSuspend(script)
    }

    /**
     * Evaluates the given JavaScript on the resource at [href] rather than the one being read, once it has loaded.
     * Returns null while that resource is not (yet) one of the loaded ones.
     */
    public suspend fun evaluateJavascript(script: String, href: Url): String? {
        continuousBook?.let { book ->
            // Accept either the original href or a served URL, and load the document first if it
            // is outside the surface's live window.
            val original = AbsoluteUrl(href.toString())
                ?.let { viewModel.server.servedUrlToLink(it)?.url()?.removeFragment() }
                ?: href
            return book.withFrame(original) { it.runJavaScriptSuspend(script) }
        }
        val page = loadedFragmentForHref(href) ?: return null
        page.awaitLoaded()
        return page.runJavaScriptSuspend(script)
    }

    /**
     * The URL the given original publication href is served at, for tests and tools that must
     * address a resource the way the WebView does. The href may be the manifest spelling
     * (`OEBPS/c1.xhtml`) or the file name (`c1.xhtml`); null when it is not in the manifest.
     */
    public fun servedUrlFor(href: Url): Url? {
        val clean = href.removeFragment().toString()
        val link = readingOrder.firstOrNull {
            val candidate = it.url().removeFragment().toString()
            candidate == clean || candidate.endsWith("/$clean")
        } ?: return null
        val served = viewModel.urlTo(link).toString()
        return Url(served.removeSuffix("/"))?.removeFragment()
    }

    /**
     * In continuous scroll, scrolls the first decoration of [group] in the chapter at [href] into view. A locator
     * jump there lands by progression (or element id), which can miss the quoted text by a page or two. Returns false
     * when there is nothing to scroll to, and in paged mode, where a jump to a locator already scrolls to its text.
     */
    public suspend fun scrollToDecoration(group: String, href: Url): Boolean {
        val book = continuousBook ?: return false
        return book.scrollToDecoration(group, href)
    }

    internal val viewModel: EpubNavigatorViewModel by viewModels {
        EpubNavigatorViewModel.createFactory(
            requireActivity().application,
            publication,
            config = this.config,
            initialPreferences = initialPreferences,
            listener = listener,
            layout = layout,
            defaults = defaults
        )
    }

    internal val readingOrder: List<Link> = readingOrder ?: publication.readingOrder

    /** The continuous-scroll surface's host, wired to this fragment's view model. */
    internal val bookHost: ContinuousBookWebView.Host by lazy {
        object : ContinuousBookWebView.Host {
            override val publication: Publication get() = this@EpubNavigatorFragment.publication

            override val backgroundColor: Int
                get() = viewModel.settings.value.effectiveBackgroundColor

            override val selectionActionModeCallback: ActionMode.Callback?
                get() = config.selectionActionModeCallback

            override fun shellUrl(): AbsoluteUrl =
                checkNotNull(viewModel.server.shellUrl()) { "No publication origin for the scroll shell" }

            override fun urlTo(link: Link): AbsoluteUrl = viewModel.urlTo(link)

            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                viewModel.shouldInterceptRequest(request)

            override fun shouldOverrideUrlLoading(request: WebResourceRequest): Boolean =
                ReaderLinkPolicy.shouldOverrideNavigation(request) { viewModel.navigateToUrl(it) }

            override fun onResourceLoaded(link: Link) {
                // Same per-resource initialization the paged fragment runs after a page
                // loads: current CSS, decoration templates, and saved decorations.
                run(viewModel.onResourceLoaded(link.url().removeFragment(), link))
            }

            override fun onBookReady() {
                if (state == State.Initializing || state is State.Loading) state = State.Ready
                publishReadiness(Readiness.Ready)
            }

            override fun onBookFailed(error: String) {
                publishReadiness(Readiness.Failed(error))
            }

            override fun positionCount(index: Int): Int =
                positionsByReadingOrder.getOrNull(index)?.size ?: 0

            override fun onRendererGone(didCrash: Boolean) {
                this@EpubNavigatorFragment.onRendererGone(didCrash)
            }

            override fun onProgressionChanged() {
                notifyCurrentLocation()
            }

            override fun onTap(point: PointF): Boolean =
                inputListener.onTap(TapEvent(point))

            override fun onDrag(
                type: ContinuousBookWebView.DragType,
                start: PointF,
                offset: PointF,
            ): Boolean {
                val dragType = when (type) {
                    ContinuousBookWebView.DragType.Start -> DragEvent.Type.Start
                    ContinuousBookWebView.DragType.Move -> DragEvent.Type.Move
                    ContinuousBookWebView.DragType.End -> DragEvent.Type.End
                }
                return inputListener.onDrag(DragEvent(type = dragType, start = start, offset = offset))
            }

            override fun onDecorationActivated(
                id: DecorationId,
                group: String,
                rect: RectF,
                point: PointF,
                href: Url,
            ): Boolean = viewModel.onDecorationActivated(id, group, rect, point)

            override fun onFootnoteLinkActivated(url: AbsoluteUrl, context: String) {
                viewModel.navigateToUrl(url, null)
            }

            override fun resourceAtUrl(url: AbsoluteUrl): Resource? =
                viewModel.internalLinkFromUrl(url)?.let { publication.get(it) }

            override fun javascriptInterfacesFor(link: Link): Map<String, Any?> =
                config.javascriptInterfaces.mapValues { (_, factory) -> factory(link) }

            override fun clearSelectionRequested() {
                run(viewModel.clearSelection())
            }

            override fun runScript(command: EpubNavigatorViewModel.RunScriptCommand) {
                this@EpubNavigatorFragment.run(command)
            }
        }
    }

    private val positionsByReadingOrder: List<List<Locator>> =
        if (readingOrder != null) {
            emptyList()
        } else {
            runBlocking { publication.positionsByReadingOrder() }
        }

    internal lateinit var positions: List<Locator>

    internal lateinit var resourcePager: R2ViewPager

    private lateinit var resourcesSingle: List<PageResource>
    private lateinit var resourcesDouble: List<PageResource>

    internal var currentPagerPosition: Int = 0
    internal lateinit var adapter: R2PagerAdapter
    private lateinit var currentActivity: FragmentActivity
    /** The eager continuous-scroll surface. Null while the resource pager is active. */
    internal var continuousBook: ContinuousBookWebView? = null

    /**
     * Memory pressure for the scroll window, kept across surface rebuilds: every surface starts at the
     * current tier and hears each change. Sampled while the reader is started (see [onViewCreated]).
     */
    private val memoryPressure by lazy {
        ScrollMemoryPressure(requireContext()) { tier -> continuousBook?.setPressureTier(tier.ordinal) }
    }

    /** Holds the scroll window at least at [tier] (null releases it), as real memory pressure would. For tests. */
    internal fun simulateMemoryPressure(tier: PressureTiers.Tier?) = memoryPressure.force(tier)

    /** The scroll window's current memory-pressure tier. For tests. */
    internal val memoryPressureTier: PressureTiers.Tier get() = memoryPressure.tier

    /** Scroll-window telemetry, kept across surface rebuilds. Benchmark builds only; null otherwise. */
    internal val scrollTelemetry: ScrollTelemetry? =
        if (com.quire.reader.BuildConfig.SCROLL_TELEMETRY) ScrollTelemetry() else null

    /**
     * Forwards a raw key-event JSON from the continuous surface's frames into the
     * input pipeline, mirroring [com.quire.reader.navigator.R2BasicWebView]'s parsing.
     */
    internal fun forwardKeyEventFromFrame(json: String): Boolean {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val type = when (obj.optString("type")) {
            "down" -> KeyEvent.Type.Down
            "up" -> KeyEvent.Type.Up
            else -> return false
        }
        val key = Key(obj.optString("code"))
        val modifiers = com.quire.reader.navigator.inputModifiers(obj)
        val characters = obj.optNullableString("characters")?.takeUnless { it.isBlank() }
        val event = KeyEvent(type = type, key = key, modifiers = modifiers, characters = characters)
        return inputListener.onKey(event)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        currentActivity = requireActivity()
        var view: View = inflater.inflate(R.layout.quire_navigator_viewpager, container, false)

        positions = positionsByReadingOrder.flatten()

        when (viewModel.layout) {
            Layout.REFLOWABLE, Layout.SCROLLED -> {
                resourcesSingle = readingOrder.mapIndexed { index, link ->
                    PageResource.EpubReflowable(
                        link = link,
                        url = viewModel.urlTo(link),
                        positionCount = positionsByReadingOrder.getOrNull(index)?.size ?: 0
                    )
                }
            }

            Layout.FIXED -> {
                val resourcesSingle = mutableListOf<PageResource>()
                val resourcesDouble = mutableListOf<PageResource>()

                // TODO needs work, currently showing two resources for fxl, needs to understand which two resources, left & right, or only right etc.
                var doublePageLeft: Link? = null
                var doublePageRight: Link?

                for ((index, link) in readingOrder.withIndex()) {
                    val url = viewModel.urlTo(link)
                    resourcesSingle.add(PageResource.EpubFxl(leftLink = link, leftUrl = url))

                    // add first page to the right,
                    if (index == 0) {
                        resourcesDouble.add(PageResource.EpubFxl(rightLink = link, rightUrl = url))
                    } else {
                        // add double pages, left & right
                        if (doublePageLeft == null) {
                            doublePageLeft = link
                        } else {
                            doublePageRight = link
                            resourcesDouble.add(
                                PageResource.EpubFxl(
                                    leftLink = doublePageLeft,
                                    leftUrl = viewModel.urlTo(doublePageLeft),
                                    rightLink = doublePageRight,
                                    rightUrl = viewModel.urlTo(doublePageRight)
                                )
                            )
                            doublePageLeft = null
                        }
                    }
                }
                // add last page if there is only a left page remaining
                if (doublePageLeft != null) {
                    resourcesDouble.add(
                        PageResource.EpubFxl(
                            leftLink = doublePageLeft,
                            leftUrl = viewModel.urlTo(doublePageLeft)
                        )
                    )
                }

                this.resourcesSingle = resourcesSingle
                this.resourcesDouble = resourcesDouble
            }
        }

        resetContainer(view)

        // Fixed layout publications cannot intercept JS events yet.
        if (publication.metadata.layout == Layout.FIXED) {
            view = KeyInterceptorView(view, inputListener)
        }

        return view
    }

    /**
     * Builds the container matching the current mode: the eager continuous-scroll
     * surface in scroll mode, the resource pager otherwise. Rebuilds on preference changes.
     */
    private fun resetContainer(root: View) {
        val parent = root as? ConstraintLayout
            ?: error("The EPUB navigator root must be a ConstraintLayout")

        // Tear down whatever container is currently built, including the static pager
        // from the layout. The pager's adapter must be nulled explicitly, otherwise
        // the page fragments leak.
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (child is R2ViewPager) {
                child.adapter = null
                parent.removeView(child)
            } else if (child is ContinuousBookWebView) {
                child.dispose()
                parent.removeView(child)
            }
        }
        continuousBook = null

        if (viewModel.isScrollEnabled.value && publication.metadata.layout != Layout.FIXED) {
            resetBook(parent)
        } else {
            resetPager(parent)
        }
    }

    /**
     * Disposes the live continuous surface and removes it from its container. The
     * surface's terminal paths all end here: container rebuilds (resetContainer disposes
     * the children it scans directly), the navigator view's destruction, and a renderer
     * lost twice.
     */
    private fun disposeContinuousBook() {
        val book = continuousBook ?: return
        continuousBook = null
        book.dispose()
        (book.parent as? ViewGroup)?.removeView(book)
    }

    /** Builds and installs an empty resource pager (paged mode). */
    private fun resetPager(parent: ViewGroup) {
        // The scroll frames' adapter must not leak into documents served for the pager:
        // it overrides `Android.getViewportWidth` with the shell's CSS-px width, which
        // Readium's paged layout reads as device pixels (the column then collapses to
        // ~157 px instead of 411 px and the page renders as clipped multi-column text).
        viewModel.server.injectsScrollFrameAdapter = false
        resourcePager = R2ViewPager(requireContext())
        resourcePager.id = R.id.resourcePager
        resourcePager.publicationType = when (publication.metadata.layout) {
            Layout.REFLOWABLE, Layout.SCROLLED, null -> R2ViewPager.PublicationType.EPUB
            Layout.FIXED -> R2ViewPager.PublicationType.FXL
        }
        resourcePager.setBackgroundColor(viewModel.settings.value.effectiveBackgroundColor)
        // Let the page views handle the keyboard events.
        resourcePager.isFocusable = false
        resourcePager.addOnPageChangeListener(PageChangeListener())

        parent.addView(resourcePager)

        resetResourcePagerAdapter()
    }

    /** Builds and installs the eager continuous-scroll surface (scroll mode). */
    private fun resetBook(parent: ViewGroup) {
        Log.d("ContinuousBook", "resetBook: creating surface #${System.identityHashCode(bookHost)}")
        val book = ContinuousBookWebView(requireContext(), this)
        book.setBackgroundColor(viewModel.settings.value.effectiveBackgroundColor)
        // The shell handles the keyboard events itself; the surface is not focusable.
        book.isFocusable = false
        continuousBook = book
        book.setPressureTier(memoryPressure.tier.ordinal)
        parent.addView(book)
        book.prepare(initialLocatorForSurface())
        notifyCurrentLocation()
    }

    /** The locator the surface should land on: the restored/current one, if any. */
    private fun initialLocatorForSurface(): Locator? =
        currentLocator.value.takeIf { state != State.Initializing } ?: initialLocator

    private inner class PageChangeListener : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            currentReflowablePageFragment?.webView?.let { webView ->
                if (viewModel.isScrollEnabled.value) {
                    if (currentPagerPosition < position) {
                        // handle swipe LEFT
                        webView.scrollToStart()
                    } else if (currentPagerPosition > position) {
                        // handle swipe RIGHT
                        webView.scrollToEnd()
                    }
                } else {
                    if (currentPagerPosition < position) {
                        // handle swipe LEFT
                        webView.setCurrentItem(0, false)
                    } else if (currentPagerPosition > position) {
                        // handle swipe RIGHT
                        webView.setCurrentItem(webView.numPages - 1, false)
                    }
                }
            }
            currentPagerPosition = position // Update current position

            notifyCurrentLocation()
        }
    }

    private fun resetResourcePagerAdapter() {
        adapter = when (publication.metadata.layout) {
            Layout.REFLOWABLE, Layout.SCROLLED, null -> {
                R2PagerAdapter(childFragmentManager, resourcesSingle)
            }
            Layout.FIXED -> {
                when (viewModel.dualPageMode) {
                    // FIXME: Properly implement DualPage.AUTO depending on the device orientation.
                    DualPage.OFF, DualPage.AUTO -> {
                        R2PagerAdapter(childFragmentManager, resourcesSingle)
                    }
                    DualPage.ON -> {
                        R2PagerAdapter(childFragmentManager, resourcesDouble)
                    }
                }
            }
        }
        adapter.listener = PagerAdapterListener()
        resourcePager.adapter = adapter
        resourcePager.direction = overflow.value.readingProgression
        resourcePager.layoutDirection = when (settings.value.readingProgression) {
            ReadingProgression.RTL -> LayoutDirection.RTL
            ReadingProgression.LTR -> LayoutDirection.LTR
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events
                    .onEach(::handleEvent)
                    .launchIn(this)

                var previousSettings = viewModel.settings.value
                viewModel.settings
                    .onEach {
                        onSettingsChange(previousSettings, it)
                        previousSettings = it
                    }
                    .launchIn(this)
            }
        }

        // Memory pressure, while the reader is on screen: trim callbacks and a memory sample every few
        // seconds in scroll mode. A stopped reader counts as hidden, which shrinks the window.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                memoryPressure.start()
                memoryPressure.onVisible()
                try {
                    while (true) {
                        if (continuousBook != null) memoryPressure.poll()
                        delay(ScrollMemoryPressure.POLL_INTERVAL_MS)
                    }
                } finally {
                    memoryPressure.onHidden()
                    memoryPressure.stop()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.withStarted {
                // Restore the last locator before a configuration change (e.g. screen rotation), or the
                // initial locator when given.
                val locator = savedInstanceState?.let {
                    BundleCompat.getParcelable(
                        it,
                        "locator",
                        Locator::class.java
                    )
                }
                    ?: initialLocator
                if (locator != null) {
                    go(locator)
                }
            }
        }
    }

    private fun handleEvent(event: EpubNavigatorViewModel.Event) {
        when (event) {
            is EpubNavigatorViewModel.Event.ApplyPreferences -> applyPreferences(event)
            is EpubNavigatorViewModel.Event.OpenInternalLink -> go(event.target)
        }
    }

    /**
     * Rebuilds the container around [locator]: the position was captured before the CSS
     * changed, and the jump never falls back to the chapter start.
     */
    private fun invalidateResourcePager(locator: Locator) {
        resetContainer(requireView())
        go(locator)
    }

    private fun onSettingsChange(previous: EpubSettings, new: EpubSettings) {
        if (previous.effectiveBackgroundColor != new.effectiveBackgroundColor) {
            // Scroll mode never builds the pager, so it may not exist.
            if (::resourcePager.isInitialized) resourcePager.setBackgroundColor(new.effectiveBackgroundColor)
            continuousBook?.setBackgroundColor(new.effectiveBackgroundColor)
        }

        if (viewModel.layout == Layout.REFLOWABLE) {
            if (previous.fontSize != new.fontSize) {
                r2PagerAdapter?.setFontSize(new.fontSize)
            }
        }
    }

    private var lastRendererLossAt = 0L

    /**
     * The scroll surface's renderer process died, almost always because the system ran out of
     * memory. The first loss rebuilds the surface at the current position; a second one within
     * [RENDERER_LOSS_WINDOW_MS] means the book does not fit even in the bounded live window, and
     * the reader is told instead of looping.
     */
    private fun onRendererGone(didCrash: Boolean) {
        val root = view ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val repeated = lastRendererLossAt != 0L && now - lastRendererLossAt < RENDERER_LOSS_WINDOW_MS
        lastRendererLossAt = now
        val locator = currentLocator.value
        Log.w("ContinuousBook", "renderer lost (didCrash=$didCrash, repeated=$repeated); locator=${locator.href}")
        if (repeated) {
            publishReadiness(Readiness.Failed("The reader's renderer was lost twice"))
            android.widget.Toast.makeText(
                requireContext(),
                "This book ran out of memory in scroll mode. Switch to pages in Display.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            // The surface died with the renderer: dispose it now instead of holding the
            // dead WebView until the reader closes.
            disposeContinuousBook()
            return
        }
        // The rebuilt surface starts with a smaller window, and keeps it: a second loss fails the book.
        memoryPressure.onRendererLost()
        publishReadiness(Readiness.Preparing)
        resetContainer(root)
        go(locator)
    }

    /**
     * Captures the reading anchor, remeasures every frame, and restores the anchor once
     * that remeasure commits. Used for viewport changes; preference applications go
     * through [applyPreferences] instead.
     */
    internal fun reflowContinuousSurface() {
        val book = continuousBook ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val anchor = book.captureAnchor()
            val target = book.remeasureAllFrames()
            book.restoreAnchor(anchor, target)
        }
    }

    // ── preference application ──────────────────────────────────────────────────

    /** Serializes preference applications: one capture/script/reflow pass at a time. */
    private val applyMutex = Mutex()

    /** Bumped per apply pass; stale completions check it. */
    private var applyGeneration = 0

    /**
     * True while an apply pass is reflowing: the intermediate locations a reflow produces
     * must not publish or persist — the pass publishes the restored position once, when
     * it finishes.
     */
    private var suppressLocationNotifications = false

    /** Test instrumentation: position-preserving reflows run by the apply path. */
    internal var applyReflowCount = 0
        private set

    /** Test instrumentation: resource-pager invalidations run by the apply path. */
    internal var applyInvalidationCount = 0
        private set

    /**
     * Applies one [EpubNavigatorViewModel.Event.ApplyPreferences] in a single serialized
     * pass: capture the position, run the CSS script, then either invalidate the pager or
     * reflow the surface — never both — and restore the captured position exactly once.
     * Failures keep the persisted values and the last usable surface, release the
     * position/update guards, and log only in debuggable builds.
     */
    private fun applyPreferences(event: EpubNavigatorViewModel.Event.ApplyPreferences) {
        viewLifecycleOwner.lifecycleScope.launch {
            applyMutex.withLock {
                val token = ++applyGeneration
                suppressLocationNotifications = true
                try {
                    applyPreferencesLocked(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    debugLog("Applying preferences failed", e)
                } finally {
                    if (token == applyGeneration) {
                        suppressLocationNotifications = false
                        notifyCurrentLocation()
                    }
                }
            }
        }
    }

    private suspend fun applyPreferencesLocked(event: EpubNavigatorViewModel.Event.ApplyPreferences) {
        if (event.needsInvalidation) {
            // The layout structure changed (direction, vertical text, spread, scroll or
            // mode): rebuild the container around the position captured before the CSS
            // changed, never falling back to the chapter start.
            val locator = currentLocator.value
            event.script?.let(::run)
            applyInvalidationCount++
            resetContainer(requireView())
            go(locator)
            return
        }
        val script = event.script ?: return
        applyReflowCount++
        val book = continuousBook
        if (book != null) {
            // Scroll: the anchor is captured before the style change is queued, the CSS is
            // applied once, and the restore waits for this reflow's own layout generation.
            val anchor = book.captureAnchor()
            run(script)
            val target = book.remeasureAllFrames()
            book.restoreAnchor(anchor, target)
        } else {
            // Pages: capture the locator, apply the CSS, wait for the page to re-layout,
            // then go back to the captured locator once.
            val locator = currentLocator.value
            run(script)
            currentReflowablePageFragment?.let { page ->
                page.awaitLoaded()
                page.awaitVisualStateUpdate()
                go(locator)
            }
        }
    }

    /** Logs only in debuggable builds; reflow failures are exercised by tests, not logcat. */
    private fun debugLog(message: String, error: Throwable) {
        val debuggable = context?.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) Log.w("EpubNavigator", message, error)
    }

    private fun R2PagerAdapter.setFontSize(fontSize: Double) {
        if (config.useReadiumCssFontSize) return

        mFragments.forEach { _, fragment ->
            (fragment as? R2EpubPageFragment)?.setFontSize(fontSize)
        }
    }

    private inner class PagerAdapterListener : R2PagerAdapter.Listener {
        override fun onCreatePageFragment(fragment: Fragment) {
            if (viewModel.layout == Layout.REFLOWABLE) {
                if (!config.useReadiumCssFontSize) {
                    (fragment as? R2EpubPageFragment)?.setFontSize(settings.value.fontSize)
                }
            }
        }
    }

    override fun onDestroyView() {
        disposeContinuousBook()
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("locator", currentLocator.value)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        if (publication == dummyPublication) {
            throw RestorationNotSupportedException
        }

        notifyCurrentLocation()
    }

    @OptIn(DelicateReadiumApi::class)
    override fun go(locator: Locator, animated: Boolean): Boolean {
        @Suppress("NAME_SHADOWING")
        val locator = publication.normalizeLocator(locator)

        if (state == State.Initializing) {
            state = State.Loading(locator.href)
        }

        listener?.onJumpToLocator(locator)

        val href = locator.href.removeFragment()

        fun setCurrent(resources: List<PageResource>) {
            val page = resources.withIndex().firstOrNull { (_, res) ->
                when (res) {
                    is PageResource.EpubReflowable ->
                        res.link.url().isEquivalent(href)
                    is PageResource.EpubFxl ->
                        res.leftUrl?.toString()?.endsWith(href.toString()) == true || res.rightUrl?.toString()?.endsWith(
                            href.toString()
                        ) == true
                    else -> false
                }
            } ?: return
            val (index, _) = page

            if (resourcePager.currentItem != index) {
                resourcePager.currentItem = index
            }
            r2PagerAdapter?.loadLocatorAt(index, locator)
        }

        if (publication.metadata.layout != Layout.FIXED) {
            val book = continuousBook
            if (book != null) return book.go(locator)
            // Scroll mode with the surface gone (renderer lost twice): the pager was never
            // built, so the navigation is dropped.
            if (!::resourcePager.isInitialized) return false
            setCurrent(resourcesSingle)
        } else {
            when (viewModel.dualPageMode) {
                // FIXME: Properly implement DualPage.AUTO depending on the device orientation.
                DualPage.OFF, DualPage.AUTO -> {
                    setCurrent(resourcesSingle)
                }
                DualPage.ON -> {
                    setCurrent(resourcesDouble)
                }
            }
        }

        return true
    }

    override fun go(link: Link, animated: Boolean): Boolean {
        val locator = publication.locatorFromLink(link) ?: return false
        return go(locator, animated)
    }

    private fun run(commands: List<RunScriptCommand>) {
        commands.forEach { run(it) }
    }

    private fun run(command: RunScriptCommand) {
        when (command.scope) {
            RunScriptCommand.Scope.CurrentResource -> {
                activeScriptRunner()?.runJavaScript(command.script)
            }
            RunScriptCommand.Scope.LoadedResources -> {
                val book = continuousBook
                if (book != null) {
                    book.forEachLoadedRunner { it.runJavaScript(command.script) }
                } else {
                    r2PagerAdapter?.mFragments?.forEach { _, fragment ->
                        (fragment as? R2EpubPageFragment)
                            ?.takeIf { it.isLoaded.value }
                            ?.runJavaScript(command.script)
                    }
                }
            }
            is RunScriptCommand.Scope.Resource -> {
                val book = continuousBook
                if (book != null) {
                    book.runnerFor(command.scope.href)
                        ?.takeIf { it.isLoaded.value }
                        ?.runJavaScript(command.script)
                } else {
                    // Paged mode: the migration replaced the concrete-WebView scope with
                    // the resource scope, so the pager's fragment for that href is the
                    // target. `runJavaScript` queues until the page finished, and the
                    // per-resource initialization is dispatched from `onPageFinished`
                    // *before* the fragment marks itself loaded — gating on `isLoaded`
                    // here silently dropped `registerDecorationTemplates` and the saved
                    // decorations in paged mode.
                    loadedFragmentForHref(command.scope.href)
                        ?.runJavaScript(command.script)
                }
            }
        }
    }

    // VisualNavigator

    override val publicationView: View
        get() = requireView()

    override val overflow: StateFlow<OverflowableNavigator.Overflow>
        get() = viewModel.overflow

    private val inputListener = CompositeInputListener()

    override fun addInputListener(listener: InputListener) {
        inputListener.add(listener)
    }

    override fun removeInputListener(listener: InputListener) {
        inputListener.remove(listener)
    }

    // SelectableNavigator
    override suspend fun currentSelection(): Selection? {
        // A selection belongs to the resource it started in, which is not necessarily the
        // resource at the viewport top (the reader may have scrolled after selecting).
        val book = continuousBook
        val selectionHref = book?.activeSelectionHref()
        val selectionRunner = book?.selectionRunner() ?: activeScriptRunner() ?: return null
        val json =
            selectionRunner.runJavaScriptSuspend("readium.getCurrentSelection();")
                .takeIf { it != "null" }
                ?.let { tryOrLog { JSONObject(it) } }
                ?: return null

        val rect = json.optRectF("rect")
            ?.run { adjustedToViewport(selectionHref) }

        // The selection's locator carries its own resource, so highlights/notes saved from
        // it address the right document even when the visible one differs.
        val locatorBase = if (book != null && selectionHref != null) {
            currentLocator.value.copy(href = selectionHref, locations = Locator.Locations())
        } else {
            currentLocator.value
        }

        return Selection(
            locator = locatorBase.copy(
                text = Locator.Text.fromJSON(json.optJSONObject("text"))
            ),
            rect = rect
        )
    }

    override fun clearSelection() {
        // Clear in the resource the selection belongs to, not just the visible one.
        val book = continuousBook
        val href = book?.activeSelectionHref()
        if (book != null && href != null) {
            book.runnerFor(href)?.runJavaScript("window.getSelection().removeAllRanges();")
            book.clearSelectionHref()
            return
        }
        run(viewModel.clearSelection())
    }

    private fun PointF.adjustedToViewport(href: Url? = null): PointF {
        continuousBook?.let { book ->
            val resolved = href ?: book.activeRunner()?.href ?: return this
            book.frameRectToView(resolved, RectF(this.x, this.y, this.x, this.y))?.let { rect ->
                return PointF(rect.left, rect.top)
            }
        }
        return activePaddingTop()?.let { top ->
            PointF(x, y + top)
        } ?: this
    }

    private fun RectF.adjustedToViewport(href: Url? = null): RectF {
        continuousBook?.let { book ->
            val resolved = href ?: book.activeRunner()?.href ?: return this
            book.frameRectToView(resolved, this)?.let { return it }
        }
        return activePaddingTop()?.let { topOffset ->
            RectF(left, top + topOffset, right, bottom)
        } ?: this
    }

    /** Top padding of the page the user is reading, for moving child coordinates
     * into the navigator's viewport space. */
    private fun activePaddingTop(): Int? =
        currentReflowablePageFragment?.paddingTop

    // DecorableNavigator

    override fun <T : Decoration.Style> supportsDecorationStyle(style: KClass<T>): Boolean =
        viewModel.supportsDecorationStyle(style)

    override fun addDecorationListener(group: String, listener: DecorableNavigator.Listener) {
        viewModel.addDecorationListener(group, listener)
    }

    override fun removeDecorationListener(listener: DecorableNavigator.Listener) {
        viewModel.removeDecorationListener(listener)
    }

    @OptIn(DelicateReadiumApi::class)
    override suspend fun applyDecorations(decorations: List<Decoration>, group: String) {
        @Suppress("NAME_SHADOWING")
        val decorations = decorations
            .map { it.copy(locator = publication.normalizeLocator(it.locator)) }

        run(viewModel.applyDecorations(decorations, group))
    }

    // R2BasicWebView.Listener

    internal val webViewListener: R2BasicWebView.Listener = WebViewListener()

    private inner class WebViewListener : R2BasicWebView.Listener {

        override val readingProgression: ReadingProgression
            get() = viewModel.readingProgression

        override val verticalText: Boolean
            get() = viewModel.verticalText

        override fun onResourceLoaded(webView: R2BasicWebView, link: Link) {
            run(viewModel.onResourceLoaded(link.url().removeFragment(), link))
        }

        override fun onPageLoaded(webView: R2BasicWebView, link: Link) {
            paginationListener?.onPageLoaded()

            val href = link.url()
            if (state is State.Initializing || (state as? State.Loading)?.initialResourceHref?.isEquivalent(
                    href
                ) == true
            ) {
                state = State.Ready
                publishReadiness(Readiness.Ready)
            }

            notifyCurrentLocation()
        }

        override fun javascriptInterfacesForResource(link: Link): Map<String, Any?> =
            config.javascriptInterfaces.mapValues { (_, factory) -> factory(link) }

        override fun onTap(point: PointF): Boolean =
            inputListener.onTap(TapEvent(point))

        override fun onDragStart(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.Start, event)

        override fun onDragMove(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.Move, event)

        override fun onDragEnd(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.End, event)

        private fun onDrag(type: DragEvent.Type, event: R2BasicWebView.DragEvent): Boolean =
            inputListener.onDrag(
                DragEvent(
                    type = type,
                    start = event.startPoint.adjustedToViewport(),
                    offset = event.offset
                )
            )

        override fun onKey(event: KeyEvent): Boolean =
            inputListener.onKey(event)

        override fun onDecorationActivated(
            id: DecorationId,
            group: String,
            rect: RectF,
            point: PointF,
        ): Boolean =
            viewModel.onDecorationActivated(
                id = id,
                group = group,
                rect = rect.adjustedToViewport(),
                point = point.adjustedToViewport()
            )

        override fun onProgressionChanged() {
            notifyCurrentLocation()
        }

        override fun goToPreviousResource(jump: Boolean, animated: Boolean): Boolean {
            return this@EpubNavigatorFragment.goToPreviousResource(jump = jump, animated = animated)
        }

        override fun goToNextResource(jump: Boolean, animated: Boolean): Boolean {
            return this@EpubNavigatorFragment.goToNextResource(jump = jump, animated = animated)
        }

        override val selectionActionModeCallback: ActionMode.Callback?
            get() = config.selectionActionModeCallback

        /**
         * Prevents opening external links in the web view and handles internal links.
         */
        override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean =
            ReaderLinkPolicy.shouldOverrideNavigation(request) { viewModel.navigateToUrl(it) }

        override fun onFootnoteLinkActivated(
            url: AbsoluteUrl,
            context: HyperlinkNavigator.FootnoteContext,
        ) {
            viewModel.navigateToUrl(url, context)
        }

        override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? =
            viewModel.shouldInterceptRequest(request)

        override fun resourceAtUrl(url: AbsoluteUrl): Resource? =
            viewModel.internalLinkFromUrl(url)
                ?.let { publication.get(it) }
    }

    override fun goForward(animated: Boolean): Boolean {
        if (publication.metadata.layout == Layout.FIXED) {
            return goToNextResource(jump = false, animated = animated)
        }

        // Scroll mode: a "page turn" moves by one reader viewport within the prepared
        // book, the way the paged web view pages within its resource. Without this the
        // reader's left/right tap zones would need a native page WebView that the
        // continuous surface deliberately does not have.
        continuousBook?.let { book ->
            return book.pageForward()
        }

        val webView = activeWebView() ?: return false

        when (settings.value.readingProgression) {
            ReadingProgression.LTR ->
                webView.scrollRight(animated)

            ReadingProgression.RTL ->
                webView.scrollLeft(animated)
        }
        return true
    }

    override fun goBackward(animated: Boolean): Boolean {
        if (publication.metadata.layout == Layout.FIXED) {
            return goToPreviousResource(jump = false, animated = animated)
        }

        continuousBook?.let { book ->
            return book.pageBackward()
        }

        val webView = activeWebView() ?: return false

        when (settings.value.readingProgression) {
            ReadingProgression.LTR ->
                webView.scrollLeft(animated)

            ReadingProgression.RTL ->
                webView.scrollRight(animated)
        }
        return true
    }

    private fun goToNextResource(jump: Boolean, animated: Boolean): Boolean {
        if (!::resourcePager.isInitialized) return false
        val adapter = resourcePager.adapter ?: return false
        if (resourcePager.currentItem >= adapter.count - 1) {
            return false
        }

        if (jump) {
            locatorToNextResource()?.let { listener?.onJumpToLocator(it) }
        }

        resourcePager.setCurrentItem(resourcePager.currentItem + 1, animated)

        currentReflowablePageFragment?.webView?.let { webView ->
            if (settings.value.readingProgression == ReadingProgression.RTL) {
                webView.setCurrentItem(webView.numPages - 1, false)
            } else {
                webView.setCurrentItem(0, false)
            }
        }

        return true
    }

    private fun goToPreviousResource(jump: Boolean, animated: Boolean): Boolean {
        if (!::resourcePager.isInitialized) return false
        if (resourcePager.currentItem <= 0) {
            return false
        }

        if (jump) {
            locatorToPreviousResource()?.let { listener?.onJumpToLocator(it) }
        }

        resourcePager.setCurrentItem(resourcePager.currentItem - 1, animated)

        currentReflowablePageFragment?.webView?.let { webView ->
            if (settings.value.readingProgression == ReadingProgression.RTL) {
                webView.setCurrentItem(0, false)
            } else {
                webView.setCurrentItem(webView.numPages - 1, false)
            }
        }

        return true
    }

    private fun locatorToPreviousResource(): Locator? =
        locatorToResourceAtIndex(resourcePager.currentItem - 1)

    private fun locatorToNextResource(): Locator? =
        locatorToResourceAtIndex(resourcePager.currentItem + 1)

    private fun locatorToResourceAtIndex(index: Int): Locator? =
        readingOrder.getOrNull(index)
            ?.let { publication.locatorFromLink(it) }

    private val r2PagerAdapter: R2PagerAdapter?
        get() = if (::resourcePager.isInitialized) {
            resourcePager.adapter as? R2PagerAdapter
        } else {
            null
        }

    private val currentReflowablePageFragment: R2EpubPageFragment? get() =
        currentFragment as? R2EpubPageFragment

    /** The scripting page the user is currently reading: the book surface's frame at the
     * reading position in scroll mode, the pager's current page fragment otherwise. */
    private fun activeScriptRunner(): com.quire.reader.navigator.ScriptRunner? {
        continuousBook?.let { book -> return book.activeRunner() }
        return currentReflowablePageFragment
    }

    private fun activeWebView(): R2WebView? {
        check(continuousBook == null) { "The continuous surface does not expose a native page WebView" }
        return currentReflowablePageFragment?.webView
    }

    private val currentFragment: Fragment? get() =
        if (!::resourcePager.isInitialized) null else fragmentAt(resourcePager.currentItem)

    private fun fragmentAt(index: Int): Fragment? =
        r2PagerAdapter?.mFragments?.get(adapter.getItemId(index))

    /**
     * Returns the reflowable page fragment matching the given href, if it is already loaded in the
     * view pager.
     */
    private fun loadedFragmentForHref(href: Url): R2EpubPageFragment? {
        val adapter = r2PagerAdapter ?: return null
        adapter.mFragments.forEach { _, fragment ->
            val pageFragment = fragment as? R2EpubPageFragment ?: return@forEach
            val link = pageFragment.link ?: return@forEach
            if (link.url() == href) {
                return pageFragment
            }
        }
        return null
    }

    override val currentLocator: StateFlow<Locator> get() = _currentLocator
    private val _currentLocator = MutableStateFlow(
        initialLocator
            ?: requireNotNull(publication.locatorFromLink(this.readingOrder.first()))
    )

    /**
     * Returns the [Locator] to the first HTML *block* element that is visible on the screen, even
     * if it begins on previous screen pages.
     */
    @ExperimentalReadiumApi
    override suspend fun firstVisibleElementLocator(): Locator? {
        val book = continuousBook
        if (book != null) {
            val index = book.resourceIndexAt(book.outerScrollY()) ?: return null
            val resource = readingOrder[index]
            return Locator(
                href = resource.url(),
                mediaType = resource.mediaType ?: MediaType.XHTML,
                locations = Locator.Locations(progression = book.progressionAt(book.outerScrollY()))
            )
        }

        if (!::resourcePager.isInitialized) return null

        return when (viewModel.layout) {
            Layout.FIXED ->
                currentLocator.value

            Layout.REFLOWABLE, Layout.SCROLLED -> {
                val resource = readingOrder[resourcePager.currentItem]
                currentReflowablePageFragment?.webView?.findFirstVisibleLocator()
                    ?.copy(
                        href = resource.url(),
                        mediaType = resource.mediaType ?: MediaType.XHTML
                    )
            }
        }
    }

    /**
     * While scrolling we receive a lot of new current locations, so we use a coroutine job to
     * debounce the notification.
     */
    private var debounceLocationNotificationJob: Job? = null

    /**
     * Mapping between reading order hrefs and the table of contents title.
     */
    private val tableOfContentsTitleByHref: Map<Href, String> by lazy {
        fun fulfill(linkList: List<Link>): MutableMap<Href, String> {
            var result: MutableMap<Href, String> = mutableMapOf()

            for (link in linkList) {
                val title = link.title ?: ""

                if (title.isNotEmpty()) {
                    result[link.href] = title
                }

                val subResult = fulfill(link.children)

                result = (subResult + result) as MutableMap<Href, String>
            }

            return result
        }

        fulfill(publication.tableOfContents).toMap()
    }

    private fun notifyCurrentLocation() {
        // Make sure viewLifecycleOwner is accessible.
        view ?: return

        // A preference application reflows the surface and moves the reading position
        // transiently; the apply pass publishes the restored position itself.
        if (suppressLocationNotifications) return

        debounceLocationNotificationJob?.cancel()
        debounceLocationNotificationJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(100L)
            if (suppressLocationNotifications) return@launch

            // We don't want to notify the current location if the navigator is still loading a
            // locator, to avoid notifying intermediate locations.
            val book = continuousBook
            if (book != null) {
                // The book surface drives the geometry itself: the active resource is the
                // one the viewport top is in (half-open intervals, zero-height skipped),
                // and the progression follows Readium's scroll-mode convention.
                if (state != State.Ready) return@launch
                val y = book.outerScrollY()
                val index = book.resourceIndexAt(y) ?: return@launch
                emitCurrentLocation(readingOrder[index], book.progressionAt(y))
            } else if (currentReflowablePageFragment?.isLoaded?.value == false || state != State.Ready) {
                return@launch
            } else {
                val reflowableWebView = currentReflowablePageFragment?.webView
                val currentProgression = reflowableWebView?.run {
                    // The transition has stabilized, so we can ask the web view to refresh its
                    // current item to reflect the current scroll position.
                    updateCurrentItem()
                    progression.coerceIn(0.0, 1.0)
                } ?: 0.0
                val currentLink = when (val pageResource = adapter.getResource(resourcePager.currentItem)) {
                    is PageResource.EpubFxl -> checkNotNull(
                        pageResource.leftLink ?: pageResource.rightLink
                    )
                    is PageResource.EpubReflowable -> pageResource.link
                    else -> throw IllegalStateException(
                        "Expected EpubFxl or EpubReflowable page resources"
                    )
                }
                emitCurrentLocation(currentLink, currentProgression)
                // Deprecated notifications
                reflowableWebView?.let {
                    paginationListener?.onPageChanged(
                        pageIndex = it.mCurItem,
                        totalPages = it.numPages,
                        locator = _currentLocator.value
                    )
                }
            }
        }
    }

    /** Builds and publishes the locator for [link] at [progression]. */
    private fun emitCurrentLocation(link: Link, progression: Double) {
        val positionLocator = publication.positionsByResource[link.url()]?.let { positions ->
            val index = ceil(progression * (positions.size - 1)).toInt()
            positions.getOrNull(index)
        }

        val currentLocator = Locator(
            href = link.url(),
            mediaType = link.mediaType ?: MediaType.XHTML,
            title = tableOfContentsTitleByHref[link.href] ?: positionLocator?.title ?: link.title,
            locations = (positionLocator?.locations ?: Locator.Locations()).copy(
                progression = progression
            ),
            text = positionLocator?.text ?: Locator.Text()
        )

        _currentLocator.value = currentLocator
    }

    public companion object {

        /** Two renderer losses closer together than this make the scroll surface give up. */
        private const val RENDERER_LOSS_WINDOW_MS = 30_000L

        /**
         * Creates a factory for a dummy [EpubNavigatorFragment].
         *
         * Used when Android restore the [EpubNavigatorFragment] after the process was killed. You
         * need to make sure the fragment is removed from the screen before [onResume] is called.
         */
        public fun createDummyFactory(): FragmentFactory = createFragmentFactory {
            EpubNavigatorFragment(
                publication = dummyPublication,
                initialLocator = Locator(href = Url("#")!!, mediaType = MediaType.XHTML),
                readingOrder = null,
                initialPreferences = EpubPreferences(),
                listener = null,
                paginationListener = null,
                layout = Layout.REFLOWABLE,
                defaults = EpubDefaults(),
                configuration = Configuration()
            )
        }

        /**
         * Returns a URL to the application asset at [path], served in the web views.
         *
         * Returns null if the given [path] is not valid or an absolute URL.
         */
        public fun assetUrl(path: String): Url? =
            WebViewServer.assetUrl(path)
    }
}

@ExperimentalReadiumApi
private val EpubSettings.effectiveBackgroundColor: Int get() =
    backgroundColor?.int ?: theme.backgroundColor
