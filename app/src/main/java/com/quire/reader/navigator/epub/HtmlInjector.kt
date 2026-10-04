/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.


package com.quire.reader.navigator.epub

import com.quire.reader.navigator.epub.css.ReadiumCss
import org.json.JSONObject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isProtected
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingResource
import timber.log.Timber

/**
 * Injects the Readium CSS files and scripts in the HTML [Resource] receiver.
 *
 * @param assetsBaseHref Base URL where the Readium CSS and scripts are served.
 */
@OptIn(ExperimentalReadiumApi::class)
internal fun Resource.injectHtml(
    publication: Publication,
    mediaType: MediaType,
    css: ReadiumCss,
    assetsBaseHref: AbsoluteUrl,
    disableSelectionWhenProtected: Boolean,
    /** Injects the scroll-only frame adapter before Readium's scripts (continuous surface). */
    scrollFrameHref: Url? = null,
): Resource =
    TransformingResource(this) { bytes ->
        if (!mediaType.isHtml) {
            return@TransformingResource Try.success(bytes)
        }

        var content = bytes.toString(mediaType.charset ?: Charsets.UTF_8).trim()
        val injectables = mutableListOf<String>()

        if (publication.metadata.layout == Layout.FIXED) {
            injectables.add(
                script(assetsBaseHref.resolve(Url("readium/scripts/readium-fixed.js")!!))
            )
        } else {
            content = try {
                css.injectHtml(content)
            } catch (e: Exception) {
                return@TransformingResource Try.failure(ReadError.Decoding(e))
            }

            if (scrollFrameHref != null) {
                // The continuous surface's frame adapter: presents the Android facade
                // Readium's scripts expect, tagged with this frame's original resource
                // href, and forwards events through the QuireBook bridge. It must be in
                // place BEFORE Readium's script initializes. CDATA keeps the XHTML
                // parser away from the operators inside (XHTML documents are parsed as
                // XML, where raw `&` is an entity error).
                injectables.add(
                    """<script>
                    //<![CDATA[
                    (function () {
                      var href = ${JSONObject.quote(scrollFrameHref.toString())};
                      var book = window.parent && window.parent.QuireBook;
                      var shellWidth = function () {
                        try { return window.parent.innerWidth || window.innerWidth; } catch (e) { return window.innerWidth; }
                      };
                      var shellHeight = function () {
                        try { return window.parent.innerHeight || window.innerHeight; } catch (e) { return window.innerHeight; }
                      };
                      // Publisher content in this frame is laid out at full content height, so the
                      // frame's own viewport is NOT the reader viewport: a bare `100vh` would measure
                      // the frame's already-expanded box and inflate the layout again on every sizing
                      // pass. Resolve viewport-relative lengths against the reader viewport, once, from
                      // the original declaration text kept alongside each style.
                      var VU = /(-?(?:\d+\.?\d*|\.\d+))(vh|vw|vmin|vmax|dvh|dvw|dvmin|dvmax|svh|svw|svmin|svmax|lvh|lvw|lvmin|lvmax)/gi;
                      var unitPx = function (unit) {
                        var h = shellHeight() / 100, w = shellWidth() / 100;
                        var u = unit.toLowerCase().replace(/^[dsl]/, '');
                        if (u === 'vw') { return w; }
                        if (u === 'vmin') { return Math.min(h, w); }
                        if (u === 'vmax') { return Math.max(h, w); }
                        return h;
                      };
                      var toPx = function (value) {
                        return String(value).replace(VU, function (m, number, unit) {
                          return (parseFloat(number) * unitPx(unit)).toFixed(2) + 'px';
                        });
                      };
                      var originals = typeof WeakMap === 'function' ? new WeakMap() : null;
                      var originalsOf = function (style) {
                        if (!originals) { return null; }
                        var map = originals.get(style);
                        if (!map) { map = {}; originals.set(style, map); }
                        return map;
                      };
                      var rewriteStyle = function (style) {
                        var map = originalsOf(style);
                        if (!map) { return; }
                        for (var i = 0; i < style.length; i++) {
                          var prop = style[i];
                          if (!(prop in map)) { map[prop] = style.getPropertyValue(prop); }
                          style.setProperty(prop, toPx(map[prop]));
                        }
                      };
                      var rewriteRules = function (rules) {
                        for (var i = 0; i < rules.length; i++) {
                          var rule = rules[i];
                          try {
                            if (rule.style) { rewriteStyle(rule.style); }
                            if (rule.cssRules) { rewriteRules(rule.cssRules); }
                          } catch (e) { }
                        }
                      };
                      // Re-runnable: a new reader viewport (rotation, window resize) re-derives every
                      // rewritten length from the declaration text captured on the first pass. While
                      // the reader viewport is not laid out yet it is not applied at all: rewriting
                      // lengths to 0px would collapse publisher content instead of measuring it.
                      var applyViewportUnits = function () {
                        if (!shellWidth() || !shellHeight()) { return; }
                        try {
                          for (var i = 0; i < document.styleSheets.length; i++) {
                            try { rewriteRules(document.styleSheets[i].cssRules); } catch (e) { }
                          }
                        } catch (e) { }
                        var elements = document.querySelectorAll('[style]');
                        for (var j = 0; j < elements.length; j++) {
                          var el = elements[j];
                          if (typeof el.__quireStyle !== 'string') { el.__quireStyle = el.getAttribute('style') || ''; }
                          var rewritten = toPx(el.__quireStyle);
                          if (rewritten !== el.getAttribute('style')) { el.setAttribute('style', rewritten); }
                        }
                      };
                      window.__quireViewportUnits = applyViewportUnits;
                      applyViewportUnits();
                      document.addEventListener('DOMContentLoaded', applyViewportUnits);
                      window.Android = {
                        getViewportWidth: function () { return shellWidth(); },
                        log: function (m) { if (book) book.log(href, m); },
                        logError: function (m, f, l) { if (book) book.logError(href, m, f, l); },
                        onTap: function (e) { return book ? book.onTap(href, e) : false; },
                        onDragStart: function (e) { return book ? book.onDrag(href, 'start', e) : false; },
                        onDragMove: function (e) { return book ? book.onDrag(href, 'move', e) : false; },
                        onDragEnd: function (e) { return book ? book.onDrag(href, 'end', e) : false; },
                        onKey: function (e) { return book ? book.onKey(href, e) : false; },
                        onSelectionStart: function () { if (book) book.onSelectionStart(href); },
                        onSelectionEnd: function () { if (book) book.onSelectionEnd(href); },
                        onDecorationActivated: function (e) { return book ? book.onDecorationActivated(href, e) : false; }
                      };
                    })();
                    //]]>
                    </script>"""
                )
            }

            injectables.add(
                script(
                    assetsBaseHref.resolve(Url("readium/scripts/readium-reflowable.js")!!)
                )
            )
        }

        // Disable the text selection if the publication is protected.
        // FIXME: This is a hack until proper LCP copy is implemented, see https://github.com/readium/kotlin-toolkit/issues/221
        if (disableSelectionWhenProtected && publication.isProtected) {
            injectables.add(
                """
                <style>
                *:not(input):not(textarea) {
                    user-select: none;
                    -webkit-user-select: none;
                }
                </style>
            """
            )
        }

        val headEndIndex = content.indexOf("</head>", 0, true)
        if (headEndIndex == -1) {
            Timber.e("</head> closing tag not found in resource with href: $sourceUrl")
        } else {
            content = StringBuilder(content)
                .insert(headEndIndex, "\n" + injectables.joinToString("\n") + "\n")
                .toString()
        }

        Try.success(content.toByteArray())
    }

private fun script(src: Url): String =
    """<script type="text/javascript" src="$src"></script>"""
