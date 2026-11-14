/*
 * Module: r2-navigator-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann, Mostapha Idoubihi, Paul Stoica
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.4.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.


package com.quire.reader.navigator.pager

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.webkit.WebViewClientCompat
import com.quire.reader.R
import com.quire.reader.navigator.R2BasicWebView
import com.quire.reader.navigator.epub.ContentScripts
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import com.quire.reader.navigator.hardenForReaderContent
import com.quire.reader.navigator.epub.EpubNavigatorViewModel
import com.quire.reader.navigator.epub.fxl.R2FXLLayout
import com.quire.reader.navigator.epub.fxl.R2FXLOnDoubleTapListener
import org.json.JSONTokener
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url

internal class R2FXLPageFragment : Fragment() {

    private val firstResourceUrl: Url?
        get() = BundleCompat.getParcelable(requireArguments(), "firstUrl", Url::class.java)

    private val secondResourceUrl: Url?
        get() = BundleCompat.getParcelable(requireArguments(), "secondUrl", Url::class.java)

    private val firstResourceLink: Link?
        get() = BundleCompat.getParcelable(requireArguments(), "firstLink", Link::class.java)

    private val secondResourceLink: Link?
        get() = BundleCompat.getParcelable(requireArguments(), "secondLink", Link::class.java)

    private var webViews = mutableListOf<R2BasicWebView>()


    private val navigator: EpubNavigatorFragment?
        get() = parentFragment as? EpubNavigatorFragment

    /** The navigator's container generation this page was built in, reported with a renderer loss. */
    private var containerGeneration = -1

    private val viewModel: EpubNavigatorViewModel by viewModels(
        ownerProducer = { requireParentFragment() }
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        containerGeneration = navigator?.containerGeneration ?: -1
        secondResourceUrl?.let {
            val view: View = inflater.inflate(R.layout.quire_navigator_fragment_fxllayout_double, container, false)
            view.setPadding(0, 0, 0, 0)

            val r2FXLLayout = view.findViewById<R2FXLLayout>(R.id.r2FXLLayout)
            r2FXLLayout.isAllowParentInterceptOnScaled = true

            val left = view.findViewById<R2BasicWebView>(R.id.firstWebView)
            val right = view.findViewById<R2BasicWebView>(R.id.secondWebView)

            setupWebView(left, firstResourceLink, firstResourceUrl)
            setupWebView(right, secondResourceLink, secondResourceUrl)

            r2FXLLayout.addOnDoubleTapListener(R2FXLOnDoubleTapListener(true))
            r2FXLLayout.addOnTapListener(object : R2FXLLayout.OnTapListener {
                override fun onTap(view: R2FXLLayout, info: R2FXLLayout.TapInfo): Boolean =
                    onPageTap(view, info)
            })

            return view
        } ?: run {
            val view: View = inflater.inflate(R.layout.quire_navigator_fragment_fxllayout_single, container, false)
            view.setPadding(0, 0, 0, 0)

            val r2FXLLayout = view.findViewById<R2FXLLayout>(R.id.r2FXLLayout)
            r2FXLLayout.isAllowParentInterceptOnScaled = true

            val webview = view.findViewById<R2BasicWebView>(R.id.webViewSingle)

            setupWebView(webview, firstResourceLink, firstResourceUrl)

            r2FXLLayout.addOnDoubleTapListener(R2FXLOnDoubleTapListener(true))
            r2FXLLayout.addOnTapListener(object : R2FXLLayout.OnTapListener {
                override fun onTap(view: R2FXLLayout, info: R2FXLLayout.TapInfo): Boolean =
                    onPageTap(view, info)
            })

            return view
        }
    }

    /**
     * A tap on the page. The zoomable layout takes every touch, so the page never sees a click: a link under the tap is
     * found by asking the page ([ContentScripts.linkAtPoint]) and followed like a link tapped in a reflowable page, and any
     * other tap goes on as a tap (menu, page turns).
     */
    private fun onPageTap(layout: R2FXLLayout, info: R2FXLLayout.TapInfo): Boolean {
        val point = PointF(info.x, info.y)
        val content = layout.getChildAt(0)
        val hit = content?.let { c ->
            webViews.firstNotNullOfOrNull { wv ->
                pointIn(wv, c, info.relativeX, info.relativeY)
                    ?.takeIf { it.x >= 0 && it.y >= 0 && it.x < wv.width && it.y < wv.height }
                    ?.let { wv to it }
            }
        }
        if (hit == null) return webViews.firstOrNull()?.listener?.onTap(point, targetElement = null) ?: false
        val (webView, at) = hit
        webView.evaluateJavascript(ContentScripts.linkAtPoint(at.x, at.y, webView.width)) { result ->
            val listener = webView.listener ?: return@evaluateJavascript
            val url = result?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            if (url != null) listener.onLinkActivated(url) else listener.onTap(point, targetElement = null)
        }
        return true
    }

    /** ([x], [y]) in [ancestor]'s coordinates, in [view]'s own; null when [view] is not inside [ancestor]. */
    private fun pointIn(view: View, ancestor: View, x: Float, y: Float): PointF? {
        val chain = mutableListOf<View>()
        var v = view
        while (v !== ancestor) {
            chain += v
            v = v.parent as? View ?: return null
        }
        var px = x
        var py = y
        for (child in chain.asReversed()) {
            val parent = child.parent as View
            px -= child.left - parent.scrollX
            py -= child.top - parent.scrollY
        }
        return PointF(px, py)
    }

    override fun onDetach() {
        super.onDetach()

        // Prevent the web views from leaking when their parent is detached.
        // See https://stackoverflow.com/a/19391512/1474476
        for (wv in webViews) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }
    }

    override fun onDestroyView() {
        for (webView in webViews) {
            webView.listener = null
        }

        super.onDestroyView()
    }

    // MissingOnRenderProcessGone: the WebViewClient below implements it; lint also flags any subclass's constructor call.
    @SuppressLint("SetJavaScriptEnabled", "MissingOnRenderProcessGone")
    private fun setupWebView(webView: R2BasicWebView, link: Link?, resourceUrl: Url?) {
        webViews.add(webView)
        navigator?.let {
            webView.listener = it.webViewListener
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.hardenForReaderContent()
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        // If we don't explicitly override the [textZoom], it will be set by Android's
        // accessibility font size system setting which breaks the layout of some fixed layouts.
        // See https://github.com/readium/kotlin-toolkit/issues/76
        webView.settings.textZoom = 100

        webView.setInitialScale(1)

        webView.setPadding(0, 0, 0, 0)
        // Upstream Readium contract: the WebView itself is the "Android" bridge. Its
        // @JavascriptInterface methods are the whole exposed surface — untrusted EPUB
        // content can call them, so each must validate its inputs (JSON payloads parsed
        // defensively, hrefs resolved against the publication only). Any future
        // @JavascriptInterface method added to R2BasicWebView must do the same.
        webView.addJavascriptInterface(webView, "Android")

        webView.webViewClient = object : WebViewClientCompat() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                webView.shouldOverrideUrlLoading(request)

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                webView.shouldInterceptRequest(view, request)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Returning false (the default) would take the whole app down with the renderer.
                // The navigator rebuilds the pager, which destroys this page and its dead WebViews.
                navigator?.onPageRendererGone(containerGeneration, detail.didCrash())
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (link != null) {
                    webView.listener?.onResourceLoaded(webView, link)
                    webView.listener?.onPageLoaded(webView, link)
                }
            }
        }
        webView.isHapticFeedbackEnabled = false
        webView.isLongClickable = false
        webView.setOnLongClickListener {
            true
        }

        resourceUrl?.let { webView.loadUrl(it.toString()) }
    }

    companion object {

        fun newInstance(left: Pair<Link, Url>?, right: Pair<Link, Url>? = null): R2FXLPageFragment =
            R2FXLPageFragment().apply {
                arguments = Bundle().apply {
                    putParcelable("firstLink", left?.first)
                    putParcelable("firstUrl", left?.second)
                    putParcelable("secondLink", right?.first)
                    putParcelable("secondUrl", right?.second)
                }
            }
    }
}
