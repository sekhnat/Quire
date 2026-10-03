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

import android.graphics.PointF
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import com.quire.reader.navigator.ChapterWebView
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
import org.readium.r2.shared.util.toAbsoluteUrl

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
        val page = scriptRunnerFor(href) ?: return null
        page.awaitLoaded()
        return page.runJavaScriptSuspend(script)
    }

    /**
     * In continuous scroll, scrolls the first decoration of [group] in the chapter at [href] into view. A locator
     * jump there lands by progression (or element id), which can miss the quoted text by a page or two. Returns false
     * when there is nothing to scroll to, and in paged mode, where a jump to a locator already scrolls to its text.
     */
    public suspend fun scrollToDecoration(group: String, href: Url): Boolean {
        val stack = chapterStack ?: return false
        val index = readingOrder.indexOfFirst { it.url().isEquivalent(href) }
        if (index < 0) return false
        // A jump still on its way would otherwise land after, and undo, this one.
        withTimeoutOrNull(2_000) { while (pendingStackJump != null) delay(50) }
        delay(150)
        val chapter = stack.chapterAt(index) ?: return false
        chapter.awaitLoaded()
        val within = chapter.offsetTopForDecoration(group) ?: return false
        stack.jumpToY(stack.chapterTop(index) + within - stack.height / 4)
        return true
    }

    private fun scriptRunnerFor(href: Url): com.quire.reader.navigator.ScriptRunner? {
        val stack = chapterStack
        if (stack != null) {
            val index = readingOrder.indexOfFirst { it.url().isEquivalent(href) }
            return if (index < 0) null else stack.chapterAt(index)
        }
        return loadedFragmentForHref(href)
    }

    private val viewModel: EpubNavigatorViewModel by viewModels {
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

    private val readingOrder: List<Link> = readingOrder ?: publication.readingOrder

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
    /** The continuous-scroll chapter stack. Null while the resource pager is active. */
    internal var chapterStack: ContinuousChapterLayout? = null

    private var pendingStackJump: PendingStackJump? = null

    /** A stack jump waiting for its chapter to load and measure. */
    private class PendingStackJump(val index: Int, val htmlId: String?, val progression: Double)

    private val stackHost = object : ContinuousChapterLayout.Host {
        override val pageCount: Int get() = this@EpubNavigatorFragment.readingOrder.size
        override fun positionCountFor(index: Int): Int =
            this@EpubNavigatorFragment.positionsByReadingOrder.getOrNull(index)?.size ?: 0

        override fun createChapterView(index: Int): ChapterWebView {
            val link = this@EpubNavigatorFragment.readingOrder[index]
            return ChapterWebView(
                context = requireContext(),
                navigator = this@EpubNavigatorFragment,
                index = index,
                link = link,
                resourceUrl = viewModel.urlTo(link)
            )
        }

        override fun disposeChapterView(view: ChapterWebView) {
            val webView = view.webView
            webView.listener = null
            view.removeAllViews()
            webView.destroy()
        }
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
     * Builds the container matching the current mode: the continuous chapter stack in
     * scroll mode, the resource pager otherwise. Rebuilds on preference changes.
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
            } else if (child is ContinuousChapterLayout) {
                child.onScrollChanged = null
                child.onChapterAvailable = null
                child.forEachChapter(stackHost::disposeChapterView)
                child.removeAllViews()
                parent.removeView(child)
            }
        }
        chapterStack = null

        if (viewModel.isScrollEnabled.value && publication.metadata.layout != Layout.FIXED) {
            resetStack(parent)
        } else {
            resetPager(parent)
        }
    }

    /** Builds and installs an empty resource pager (paged mode). */
    private fun resetPager(parent: ViewGroup) {
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

    /** Builds and installs the continuous chapter stack (scroll mode). */
    private fun resetStack(parent: ViewGroup) {
        val stack = ContinuousChapterLayout(requireContext(), stackHost)
        stack.setBackgroundColor(viewModel.settings.value.effectiveBackgroundColor)
        // The chapter views handle the keyboard events.
        stack.isFocusable = false
        stack.onScrollChanged = { notifyCurrentLocation() }
        stack.onChapterAvailable = { resolvePendingStackJump(it) }
        chapterStack = stack
        parent.addView(stack)
        stack.updateWindow()
        notifyCurrentLocation()
    }

    /** Jumps to a pending [PendingStackJump] as soon as its chapter is measured. */
    private fun resolvePendingStackJump(index: Int) {
        val jump = pendingStackJump ?: return
        if (jump.index != index) return
        val stack = chapterStack ?: return
        val chapter = stack.chapterAt(index) ?: return
        if (!chapter.isLoaded.value) return
        pendingStackJump = null
        landStackJump(stack, jump)
    }

    private fun landStackJump(stack: ContinuousChapterLayout, jump: PendingStackJump) {
        viewLifecycleOwner.lifecycleScope.launch {
            val chapter = stack.chapterAt(jump.index) ?: return@launch
            chapter.awaitLoaded()
            val height = stack.chapterHeight(jump.index)
            val within = jump.htmlId?.let { chapter.offsetTopForId(it) }
                ?: (jump.progression.coerceIn(0.0, 1.0) * height).toInt()
            stack.jumpToY(stack.chapterTop(jump.index) + within)
        }
    }

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
            is EpubNavigatorViewModel.Event.RunScript -> {
                run(event.command)
            }
            is EpubNavigatorViewModel.Event.OpenInternalLink -> {
                go(event.target)
            }
            EpubNavigatorViewModel.Event.InvalidateViewPager -> {
                invalidateResourcePager()
            }
        }
    }

    private fun invalidateResourcePager() {
        val locator = currentLocator.value
        resetContainer(requireView())
        go(locator)
    }

    private fun onSettingsChange(previous: EpubSettings, new: EpubSettings) {
        if (previous.effectiveBackgroundColor != new.effectiveBackgroundColor) {
            resourcePager.setBackgroundColor(new.effectiveBackgroundColor)
            chapterStack?.setBackgroundColor(new.effectiveBackgroundColor)
        }

        if (viewModel.layout == Layout.REFLOWABLE) {
            if (previous.fontSize != new.fontSize) {
                r2PagerAdapter?.setFontSize(new.fontSize)
            }
        }
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
        chapterStack?.let {
            it.onScrollChanged = null
            it.onChapterAvailable = null
            it.forEachChapter(stackHost::disposeChapterView)
            it.removeAllViews()
            chapterStack = null
        }
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
            val stack = chapterStack
            if (stack != null) return goInStack(stack, locator, href)
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
                val stack = chapterStack
                if (stack != null) {
                    stack.forEachChapter { chapter ->
                        chapter.takeIf { it.isLoaded.value }
                            ?.runJavaScript(command.script)
                    }
                } else {
                    r2PagerAdapter?.mFragments?.forEach { _, fragment ->
                        (fragment as? R2EpubPageFragment)
                            ?.takeIf { it.isLoaded.value }
                            ?.runJavaScript(command.script)
                    }
                }
            }
            is RunScriptCommand.Scope.LoadedResource -> {
                val stack = chapterStack
                if (stack != null) {
                    stack.forEachChapter { chapter ->
                        chapter.takeIf { it.isLoaded.value && it.link.url() == command.scope.href }
                            ?.runJavaScript(command.script)
                    }
                } else {
                    loadedFragmentForHref(command.scope.href)
                        ?.takeIf { it.isLoaded.value }
                        ?.runJavaScript(command.script)
                }
            }
            is RunScriptCommand.Scope.WebView -> {
                command.scope.webView.runJavaScript(command.script)
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
        val fragment = activeScriptRunner() ?: return null
        val json =
            fragment.runJavaScriptSuspend("readium.getCurrentSelection();")
                .takeIf { it != "null" }
                ?.let { tryOrLog { JSONObject(it) } }
                ?: return null

        val rect = json.optRectF("rect")
            ?.run { adjustedToViewport() }

        return Selection(
            locator = currentLocator.value.copy(
                text = Locator.Text.fromJSON(json.optJSONObject("text"))
            ),
            rect = rect
        )
    }

    override fun clearSelection() {
        run(viewModel.clearSelection())
    }

    private fun PointF.adjustedToViewport(): PointF =
        activePaddingTop()?.let { top ->
            PointF(x, y + top)
        } ?: this

    private fun RectF.adjustedToViewport(): RectF =
        activePaddingTop()?.let { topOffset ->
            RectF(left, top + topOffset, right, bottom)
        } ?: this

    /** Top padding of the page the user is reading, for moving child coordinates
     * into the navigator's viewport space. */
    private fun activePaddingTop(): Int? =
        chapterStack?.let { stack -> stack.chapterAt(stack.focusChapter())?.paddingTop }
            ?: currentReflowablePageFragment?.paddingTop

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

    /**
     * Navigates to [locator] inside the continuous chapter stack. Locators pointing
     * at a chapter that is still loading wait in [pendingStackJump] until it is.
     */
    private fun goInStack(
        stack: ContinuousChapterLayout,
        locator: Locator,
        href: Url,
    ): Boolean {
        val index = readingOrder.indexOfFirst { it.url().isEquivalent(href) }
        if (index < 0) return false
        val jump = PendingStackJump(
            index = index,
            htmlId = locator.locations.htmlId,
            progression = locator.locations.progression ?: 0.0
        )
        val chapter = stack.chapterAt(index)
        if (chapter != null && chapter.isLoaded.value) {
            landStackJump(stack, jump)
        } else {
            pendingStackJump = jump
            stack.updateWindow(index)
        }
        return true
    }

    // R2BasicWebView.Listener

    internal val webViewListener: R2BasicWebView.Listener = WebViewListener()

    private inner class WebViewListener : R2BasicWebView.Listener {

        override val readingProgression: ReadingProgression
            get() = viewModel.readingProgression

        override val verticalText: Boolean
            get() = viewModel.verticalText

        override fun onResourceLoaded(webView: R2BasicWebView, link: Link) {
            run(viewModel.onResourceLoaded(webView, link))
        }

        override fun onPageLoaded(webView: R2BasicWebView, link: Link) {
            paginationListener?.onPageLoaded()

            val href = link.url()
            if (state is State.Initializing || (state as? State.Loading)?.initialResourceHref?.isEquivalent(
                    href
                ) == true
            ) {
                state = State.Ready
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
        override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toAbsoluteUrl() ?: return false
            viewModel.navigateToUrl(url)
            return true
        }

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

    /** The scripting page the user is currently reading: the stack's focus chapter in
     * scroll mode, the pager's current page fragment otherwise. */
    private fun activeScriptRunner(): com.quire.reader.navigator.ScriptRunner? {
        val stack = chapterStack ?: return currentReflowablePageFragment
        return stack.chapterAt(stack.focusChapter())
    }

    private fun activeWebView(): R2WebView? {
        val stack = chapterStack ?: return currentReflowablePageFragment?.webView
        return stack.chapterAt(stack.focusChapter())?.webView
    }

    private val currentFragment: Fragment? get() =
        fragmentAt(resourcePager.currentItem)

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
        val stack = chapterStack
        if (stack != null) {
            val resource = readingOrder[stack.focusChapter()]
            return Locator(
                href = resource.url(),
                mediaType = resource.mediaType ?: MediaType.XHTML,
                locations = Locator.Locations(progression = stack.focusProgression())
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

        debounceLocationNotificationJob?.cancel()
        debounceLocationNotificationJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(100L)

            // We don't want to notify the current location if the navigator is still loading a
            // locator, to avoid notifying intermediate locations.
            when (val stack = chapterStack) {
                null -> {
                    if (currentReflowablePageFragment?.isLoaded?.value == false || state != State.Ready) {
                        return@launch
                    }
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
                else -> {
                    // The stack drives the geometry itself: the focus chapter is the one the
                    // viewport top is in, and the progression follows Readium's scroll-mode
                    // convention (offset within the chapter / content height).
                    val focus = stack.focusChapter()
                    if (stack.chapterAt(focus)?.isLoaded?.value != true || state != State.Ready) {
                        return@launch
                    }
                    emitCurrentLocation(readingOrder[focus], stack.focusProgression())
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
