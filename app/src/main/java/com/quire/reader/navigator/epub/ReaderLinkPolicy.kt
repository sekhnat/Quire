/*
 * Copyright 2024 Quire.
 */
//
// The reader's navigation policy for content-initiated navigations. EPUB content is
// untrusted: a book may link to any scheme (web sites, mailto:, intent://, file://,
// content://, custom app schemes) and may build malformed URIs. The policy decides what a
// tapped link may do before anything else happens to the URL:
//
//   http / https   parsed (safely) and handed to the navigator funnel: internal links open
//                  in-reader, external ones reach the app's external-link handler.
//   everything else consumed (the WebView must not load it), logged, never parsed —
//                  notably, Readium's AbsoluteUrl throws for non-hierarchical URIs
//                  (mailto:, tel:, javascript:, data:), so parsing before the scheme gate
//                  would crash the reader on such a link.
//
// Used by both navigation sites: the continuous-scroll shell's WebViewClient and the
// paged/FXL per-chapter WebViews (EpubNavigatorFragment). EpubHost re-checks the scheme
// before firing its ACTION_VIEW intent as defense in depth.

package com.quire.reader.navigator.epub

import android.webkit.WebResourceRequest
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.toAbsoluteUrl
import timber.log.Timber

internal object ReaderLinkPolicy {

    /** Pure, JVM-testable: schemes the reader will parse and act on at all (case-insensitive). */
    fun isSupportedScheme(scheme: String?): Boolean =
        when (scheme?.lowercase()) {
            "http", "https" -> true
            else -> false
        }

    /**
     * Decides a content-initiated navigation. Always returns `true` — consumed, so the
     * WebView must never load the URL itself — for supported schemes too, where [navigate]
     * takes over (EpubNavigatorViewModel.navigateToUrl: internal link → in-reader jump,
     * external link → the app's external-link handler).
     */
    fun shouldOverrideNavigation(request: WebResourceRequest, navigate: (AbsoluteUrl) -> Unit): Boolean {
        if (!isSupportedScheme(request.url.scheme)) {
            Timber.w("Rejected navigation with unsupported scheme: ${request.url}")
            return true
        }
        val url = runCatching { request.url.toAbsoluteUrl() }.getOrNull()
        if (url == null) {
            Timber.w("Rejected malformed navigation: ${request.url}")
            return true
        }
        navigate(url)
        return true
    }

    /**
     * Pure, JVM-testable: the scheme of [raw] (lower-cased), or null when it has none. Read
     * from the text so that no URL parser sees an unsupported (possibly opaque) URI.
     */
    fun schemeOf(raw: String): String? =
        SCHEME.find(raw.trimStart())?.groupValues?.get(1)?.lowercase()

    /** Longer links are not real navigation targets; refusing them bounds the work done. */
    const val MAX_LINK_LENGTH = 8192

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /**
     * A link the user tapped in a document, reported by ContentScripts as an absolute URL.
     * Same rules as [shouldOverrideNavigation]: only web URLs reach [navigate].
     */
    fun onLinkActivated(raw: String, navigate: (AbsoluteUrl) -> Unit) {
        if (raw.length > MAX_LINK_LENGTH) {
            Timber.w("Rejected link longer than $MAX_LINK_LENGTH characters")
            return
        }
        if (!isSupportedScheme(schemeOf(raw))) {
            Timber.w("Rejected link with unsupported scheme: $raw")
            return
        }
        val url = runCatching { AbsoluteUrl(raw) }.getOrNull()
        if (url == null) {
            Timber.w("Rejected malformed link: $raw")
            return
        }
        navigate(url)
    }
}
