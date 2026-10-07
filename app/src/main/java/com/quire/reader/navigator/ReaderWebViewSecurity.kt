/*
 * Copyright 2024 Quire.
 */
//
// WebView capabilities the reader does not need, pinned off at every reader WebView
// (continuous-scroll shell, paged reflowable, fixed-layout). EPUB content is untrusted
// HTML running with JavaScript enabled; anything not required to read must not be reachable
// from it.

package com.quire.reader.navigator

import android.webkit.WebSettings

/**
 * Disables every WebView capability the reader does not require. Applied in addition to the
 * per-surface settings (JavaScript on for Readium's runtime, zoom/textZoom in the paged
 * paths, LOAD_NO_CACHE on the shell).
 */
@Suppress("DEPRECATION") // The file-URL settings are deprecated no-ops on modern WebView;
// pinned defensively so an app targeting an older API level stays safe too.
internal fun WebSettings.hardenForReaderContent() {
    // Local resources: EPUB content must not reach the device's file system or content
    // providers. Quire holds all-files access, so content:// URIs would otherwise resolve
    // inside a book.
    allowFileAccess = false
    allowContentAccess = false
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false

    // The publication origin is https (https://readium_package); plain-http subresources
    // are mixed content and must never load.
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

    // Nothing in the reader prompts for geolocation; book content must not be able to.
    setGeolocationEnabled(false)

    // No pop-up windows: a target=_blank link stays subject to the navigation policy in
    // the same view, and scripts cannot open windows on their own.
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)

    // Every book is served from one shared origin (https://readium_package), so DOM
    // storage (localStorage, IndexedDB) would be shared between all of the library's
    // untrusted books. Nothing in the reader uses it.
    domStorageEnabled = false
}
