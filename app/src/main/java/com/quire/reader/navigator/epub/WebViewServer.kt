/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.


@file:OptIn(InternalReadiumApi::class)

package com.quire.reader.navigator.epub

import android.app.Application
import android.os.PatternMatcher
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.quire.reader.navigator.epub.css.ReadiumCss
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.data.asInputStream
import org.readium.r2.shared.util.http.HttpHeaders
import org.readium.r2.shared.util.http.HttpRange
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.StringResource
import org.readium.r2.shared.util.resource.fallback
import org.readium.r2.shared.util.toAbsoluteUrl

/**
 * Serves the publication resources and application assets in the EPUB navigator web views.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class WebViewServer(
    private val application: Application,
    private val publication: Publication,
    servedAssets: List<String>,
    private val disableSelectionWhenProtected: Boolean,
    private val onResourceLoadFailed: (Url, ReadError) -> Unit,
) {
    companion object {
        private const val TAG = "WebViewServer"
        const val PACKAGE_HOSTNAME = "readium_package"
        const val ASSETS_HOSTNAME = "readium_assets"

        /** Reserved path of the continuous-scroll shell document, on the publication origin. */
        const val SHELL_PATH = "/quire/continuous-scroll"
        const val SHELL_SCRIPT_PATH = "/quire/continuous-scroll.js"

        val packageBaseHref = AbsoluteUrl("https://$PACKAGE_HOSTNAME/")!!
        val assetsBaseHref = AbsoluteUrl("https://$ASSETS_HOSTNAME/")!!

        fun assetUrl(path: String): Url? =
            Url.fromDecodedPath(path)?.let { assetsBaseHref.resolve(it) }

        /**
         * Test-only gate installed by instrumentation to hold a resource response (slow
         * startup regression coverage). Null, and unused, in production.
         */
        @Volatile
        var onInterceptResource: ((url: String, stream: java.io.InputStream) -> java.io.InputStream)? = null
    }

    init {
        // The shell route must not shadow a real publication resource; fail loudly at
        // navigator construction instead of serving the wrong document while reading.
        val shellHref = Url(SHELL_PATH.removePrefix("/"))!!
        check(publication.get(shellHref) == null) {
            "Publication already contains a resource at $SHELL_PATH; the continuous-scroll shell route would shadow it."
        }
    }

    /** The URL the continuous-scroll shell document is served at, on the publication origin. */
    fun shellUrl(): AbsoluteUrl? =
        publication.baseUrl?.resolve(Url(SHELL_PATH)!!) as? AbsoluteUrl
            ?: packageBaseHref.resolve(Url(SHELL_PATH)!!)

    /**
     * Gets the url the given [link] is being served at.
     */
    fun linkToServedUrl(link: Link): AbsoluteUrl =
        when (val url = link.url()) {
            is AbsoluteUrl ->
                url
            is RelativeUrl ->
                (publication.baseUrl ?: packageBaseHref).resolve(link.url())
        }

    /**
     * Gets a link to the resource targeted by [url].
     */
    fun servedUrlToLink(url: AbsoluteUrl): Link? {
        val link = when (url.host) {
            PACKAGE_HOSTNAME -> {
                // The reserved shell routes are internal surfaces, never publication
                // resources: they must not resolve to a link (a locator).
                if (url.path == SHELL_PATH || url.path == SHELL_SCRIPT_PATH) return null
                val href = packageBaseHref.relativize(url)
                publication.linkWithHref(href)
            }
            else -> {
                publication.linkWithHref(url)
                    ?: publication.baseUrl?.relativize(url)
                        ?.let { relativeUrl ->
                            publication.linkWithHref(relativeUrl)
                        }
            }
        } ?: return null

        val hrefWithFragment = link.url().let { linkUrl ->
            url.fragment?.let { linkUrl.addFragment(it) } ?: linkUrl
        }

        // Fragment must be kept as it might be relevant to the caller.
        // For the rest of the url, we return precisely the version in the manifest.
        return link.copy(href = Href(hrefWithFragment))
    }

    /**
     * Serves the requests of the navigator web views.
     *
     * https://readium_package/ serves the publication resources through its container.
     * https://readium_assets/ serves the application assets.
     * The reserved continuous-scroll shell route is served from the app assets on the
     * publication origin.
     */
    fun shouldInterceptRequest(request: WebResourceRequest, css: ReadiumCss): WebResourceResponse? {
        val path = request.url.path ?: return null
        val hostname = request.url.host ?: return null
        val requestUrl = request.url.toAbsoluteUrl() ?: return null
        val range = HttpHeaders(request.requestHeaders).range

        return when {
            hostname == PACKAGE_HOSTNAME && path == SHELL_PATH -> serveShell(
                assetName = "quire/continuous-scroll.html",
                mediaType = MediaType("text/html")!!,
            )
            hostname == PACKAGE_HOSTNAME && path == SHELL_SCRIPT_PATH -> serveShell(
                assetName = "quire/continuous-scroll.js",
                mediaType = MediaType("application/javascript")!!,
            )
            hostname == ASSETS_HOSTNAME -> {
                if (isServedAsset(path.removePrefix("/"))) {
                    // Request is for a known asset.
                    assetsLoader.shouldInterceptRequest(request.url)
                        ?.apply { allowCors() }
                } else {
                    val error = ReadError.Decoding(
                        "Attempted to load an unknown asset from $requestUrl"
                    )
                    onResourceLoadFailed(requestUrl, error)
                    serveErrorResponse() // Request is for an unknown asset.
                }
            }
            else -> { // Request is for a publication resource
                servePublicationResourceWithUrl(
                    // Drop anchor because it is meant to be interpreted by the client.
                    url = requestUrl.removeFragment(),
                    range = range,
                    css = css
                )
            }
        }
    }

    /** Serves a shell asset from the app assets, on the publication origin. */
    private fun serveShell(assetName: String, mediaType: MediaType): WebResourceResponse {
        val resource = StringResource {
            withContext(Dispatchers.IO) {
                Try.success(
                    application.assets.open(assetName).bufferedReader().use { it.readText() }
                )
            }
        }
        return serveResource(resource, null, mediaType)
    }

    /**
     * Returns a new [Resource] to serve the given [url] in the publication.
     *
     * If the [Resource] is an HTML document, injects the required JavaScript and CSS files.
     */
    private fun servePublicationResourceWithUrl(
        url: AbsoluteUrl,
        range: HttpRange?,
        css: ReadiumCss,
    ): WebResourceResponse {
        val link = servedUrlToLink(url)

        val mediaType = link?.mediaType
            ?: mediaTypeFromUrl(url)

        // Just in case some resource is not in the manifest: look it up in the container by its
        // path. Anything else (the browser's `/favicon.ico`, a remote image or stylesheet) is
        // refused: handing an absolute URL to the publication would fetch it over HTTP, and the
        // app has no network permission, so the failed lookup throws on the WebView's network
        // thread and takes the whole app down.
        val href = link?.url() ?: unlistedHref(url) ?: return notFoundResponse(url)

        return servePublicationResourceWithHref(
            href = href,
            mediaType = mediaType,
            range = range,
            css = css
        )
    }

    /**
     * When the continuous surface is active, every reflowable HTML document is served
     * with the scroll-only frame adapter in front of Readium's scripts, tagged with
     * its original resource href.
     */
    @Volatile
    var injectsScrollFrameAdapter: Boolean = false

    private fun servePublicationResourceWithHref(
        href: Url,
        mediaType: MediaType?,
        range: HttpRange?,
        css: ReadiumCss,
    ): WebResourceResponse {
        var resource = publication
            .get(href)
            ?.fallback {
                onResourceLoadFailed(href, it)
                errorResource()
            } ?: run {
            val error = ReadError.Decoding(
                "Resource not found at $href in publication."
            )
            onResourceLoadFailed(href, error)
            errorResource()
        }

        // Only inject html when the profile is EPUB
        if (publication.conformsTo(Publication.Profile.EPUB)) {
            mediaType
                ?.takeIf { it.isHtml }
                ?.let {
                    resource = resource.injectHtml(
                        publication,
                        mediaType = it,
                        css,
                        assetsBaseHref,
                        disableSelectionWhenProtected,
                        scrollFrameHref = href.takeIf { injectsScrollFrameAdapter },
                    )
                }
        }

        return serveResource(resource, range, mediaType, href)
    }

    private fun serveResource(
        resource: Resource,
        range: HttpRange?,
        mediaType: MediaType?,
        href: Url? = null,
    ): WebResourceResponse {
        val headers = mutableMapOf(
            "Accept-Ranges" to "bytes"
        )

        val stream = resource.asInputStream()
        if (range == null) {
            val gated = onInterceptResource?.invoke(
                href?.toString() ?: resource.sourceUrl.toString(),
                stream,
            ) ?: stream
            return WebResourceResponse(
                mediaType?.toString(),
                null,
                200,
                "OK",
                headers,
                gated
            )
        } else { // Byte range request
            val length = stream.available()
            val longRange = range.toLongRange(length.toLong())
            headers["Content-Range"] = "bytes ${longRange.first}-${longRange.last}/$length"
            // Content-Length will automatically be filled by the WebView using the Content-Range header.
            // headers["Content-Length"] = (longRange.last - longRange.first + 1).toString()
            // Weirdly, the WebView will call itself stream.skip to skip to the requested range.
            return WebResourceResponse(
                mediaType?.toString(),
                null,
                206,
                "Partial Content",
                headers,
                stream
            )
        }
    }

    /** The container path of [url] when it is on the package origin, null for any other origin. */
    private fun unlistedHref(url: AbsoluteUrl): Url? =
        if (url.host == PACKAGE_HOSTNAME && url.path != "/favicon.ico") packageBaseHref.relativize(url) else null

    private fun notFoundResponse(url: AbsoluteUrl): WebResourceResponse {
        Log.d(TAG, "not served (not in the publication): $url")
        return WebResourceResponse(null, null, 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    /**
     * Resolve the [MediaType] from an [Url].
     */
    private fun mediaTypeFromUrl(href: Url): MediaType? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(href.normalize().toString()) ?: return null
        val mimetype = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: return null

        return MediaType.invoke(mimetype)
    }

    private fun errorResource(): Resource =
        StringResource {
            withContext(Dispatchers.IO) {
                Try.success(
                    application.assets
                        .open("readium/error.xhtml")
                        .bufferedReader()
                        .use { it.readText() }
                )
            }
        }

    /**
     * Allow the response to be consumed by publication documents served
     * from any origin, including the package domain.
     */
    private fun WebResourceResponse.allowCors() {
        responseHeaders = responseHeaders ?: mutableMapOf()
        responseHeaders["Access-Control-Allow-Origin"] = "*"
    }

    private fun serveErrorResponse(): WebResourceResponse {
        return serveResource(errorResource(), null, MediaType.XHTML)
    }

    private fun isServedAsset(path: String): Boolean =
        servedAssetPatterns.any { it.match(path) }

    private val servedAssetPatterns: List<PatternMatcher> =
        servedAssets.map { PatternMatcher(it, PatternMatcher.PATTERN_SIMPLE_GLOB) }

    private val assetsLoader =
        WebViewAssetLoader.Builder()
            .setDomain(ASSETS_HOSTNAME)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(application))
            .build()
}
