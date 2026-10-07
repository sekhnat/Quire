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
}
