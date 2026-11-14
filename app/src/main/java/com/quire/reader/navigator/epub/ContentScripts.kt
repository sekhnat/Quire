/*
 * Copyright 2024 Quire.
 */
//
// Quire's own script, injected into every reflowable document in both reading modes (after
// the scroll frame adapter, before Readium's scripts). It reports to native code through
// `window.Android`, which is R2BasicWebView in paged mode and the frame adapter's facade
// (forwarding to QuireBookBridge with the frame's href) in scroll mode. Both facades expose
// the same methods, so this script does not know which mode it runs in.
//
// Link taps. Every click on a link is cancelled here and reported as an absolute URL to
// `Android.onLink`, which sends it through ReaderLinkPolicy like any other navigation. This is
// the only reliable path: WebView never asks shouldOverrideUrlLoading about a navigation
// inside an iframe (scroll mode's chapters), nor about a fragment-only link within the same
// document (paged mode), so letting the click through would either load the target inside the
// chapter's frame or scroll the WebView natively and break the layout. The listener runs in
// the capture phase, so Readium's own click handler then sees `defaultPrevented` and does not
// report the tap as a page tap.

package com.quire.reader.navigator.epub

import com.quire.reader.reader.UserFontRegistry
import org.json.JSONObject
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url

internal object ContentScripts {

    /** The `<script>` element to inject into a reflowable document's head. */
    val injectable: String = """<script>
//<![CDATA[
(function () {
  if (window.__quireContent) { return; }
  window.__quireContent = true;
  var XLINK = 'http://www.w3.org/1999/xlink';
  var hrefOf = function (el) {
    return el.getAttribute('href') || (el.getAttributeNS && el.getAttributeNS(XLINK, 'href')) || null;
  };
  var linkOf = function (node) {
    for (var n = node; n && n !== document; n = n.parentNode) {
      if (n.nodeType === 1 && (n.localName === 'a' || n.localName === 'area') && hrefOf(n)) { return n; }
    }
    return null;
  };
  // ── Images, inline SVG and tables, opened full screen (spec feature 8) ──
  var report = function (payload) {
    if (window.Android && window.Android.onMedia) { window.Android.onMedia(JSON.stringify(payload)); }
  };
  var styles = function () {
    var out = [];
    var links = document.querySelectorAll('link[rel~="stylesheet"][href]');
    for (var i = 0; i < links.length && out.length < 20; i++) { out.push(links[i].href); }
    return out;
  };
  // The image or drawing a long press landed on: an <img> (inside <picture> too), an SVG <image>, or an inline <svg>.
  var mediaOf = function (node) {
    var svg = null;
    for (var n = node; n && n !== document; n = n.parentNode) {
      if (n.nodeType !== 1) { continue; }
      if (n.localName === 'img') { return n; }
      if (n.localName === 'image' && n.namespaceURI === 'http://www.w3.org/2000/svg') { return n; }
      if (n.localName === 'svg') { svg = n; }
    }
    return svg;
  };
  var reportMedia = function (el) {
    if (el.localName === 'img') {
      report({ kind: 'image', src: el.currentSrc || el.src, alt: el.getAttribute('alt') || '' });
    } else if (el.localName === 'image') {
      var src;
      try { src = new URL(hrefOf(el), document.baseURI).href; } catch (e) { return; }
      var title = el.parentNode && el.parentNode.querySelector && el.parentNode.querySelector('title');
      report({ kind: 'image', src: src, alt: title ? title.textContent : '' });
    } else {
      report({ kind: 'svg', html: el.outerHTML, base: document.baseURI, styles: styles() });
    }
  };
  var press = null;
  var swallowClick = false;
  var LONG_PRESS_MS = 500;
  document.addEventListener('touchstart', function (event) {
    if (event.touches.length !== 1) { press && clearTimeout(press.timer); press = null; return; }
    var target = mediaOf(event.target);
    if (!target) { return; }
    var t = event.touches[0];
    press = { x: t.clientX, y: t.clientY, target: target, timer: setTimeout(function () {
      if (!press) { return; }
      swallowClick = true;
      reportMedia(press.target);
      press = null;
    }, LONG_PRESS_MS) };
  }, { capture: true, passive: true });
  var cancelPress = function () { if (press) { clearTimeout(press.timer); press = null; } };
  document.addEventListener('touchmove', function (event) {
    if (!press) { return; }
    var t = event.touches[0];
    if (Math.abs(t.clientX - press.x) > 10 || Math.abs(t.clientY - press.y) > 10) { cancelPress(); }
  }, { capture: true, passive: true });
  document.addEventListener('touchend', cancelPress, { capture: true, passive: true });
  document.addEventListener('touchcancel', cancelPress, { capture: true, passive: true });
  // The long press already opened the viewer: no menu, no selection, no page tap after it.
  document.addEventListener('contextmenu', function (event) { if (mediaOf(event.target)) { event.preventDefault(); } }, true);
  document.addEventListener('selectstart', function (event) { if (swallowClick) { event.preventDefault(); } }, true);

  // A small expand button on tables too wide for the column or with many columns. It sits in a zero-height box
  // before the table, so the page's layout (and every saved position) is unchanged.
  var EXPAND = 'quire-expand-table';
  var addTableButtons = function () {
    var tables = document.querySelectorAll('table');
    for (var i = 0; i < tables.length; i++) {
      var table = tables[i];
      if (table.__quireExpand) { continue; }
      var cols = 0;
      var row = table.rows && table.rows[0];
      if (row) { for (var c = 0; c < row.cells.length; c++) { cols += row.cells[c].colSpan || 1; } }
      var parent = table.parentElement;
      var wide = parent && table.scrollWidth > parent.clientWidth + 2;
      if (!wide && cols <= 4) { continue; }
      table.__quireExpand = true;
      var holder = document.createElement('span');
      holder.setAttribute('style', 'display:block;position:relative;height:0;margin:0;padding:0;border:0;');
      var button = document.createElement('span');
      button.className = EXPAND;
      button.setAttribute('role', 'button');
      button.setAttribute('aria-label', 'Open the table full screen');
      button.setAttribute('style', 'position:absolute;right:0;top:-30px;width:28px;height:28px;box-sizing:border-box;' +
        'border:1px solid currentColor;border-radius:6px;opacity:0.5;cursor:pointer;z-index:1;' +
        '-webkit-user-select:none;user-select:none;display:flex;align-items:center;justify-content:center;');
      var SVGNS = 'http://www.w3.org/2000/svg';
      var icon = document.createElementNS(SVGNS, 'svg');
      icon.setAttribute('viewBox', '0 0 16 16');
      icon.setAttribute('width', '16');
      icon.setAttribute('height', '16');
      var path = document.createElementNS(SVGNS, 'path');
      path.setAttribute('d', 'M2 6V2h4M10 2h4v4M14 10v4h-4M6 14H2v-4');
      path.setAttribute('fill', 'none');
      path.setAttribute('stroke', 'currentColor');
      path.setAttribute('stroke-width', '1.5');
      icon.appendChild(path);
      button.appendChild(icon);
      button.__quireTable = table;
      holder.appendChild(button);
      table.parentNode.insertBefore(holder, table);
    }
  };
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', addTableButtons); } else { addTableButtons(); }
  window.addEventListener('load', addTableButtons);

  document.addEventListener('click', function (event) {
    if (swallowClick) { swallowClick = false; event.preventDefault(); event.stopPropagation(); return; }
    var expand = event.target && event.target.closest && event.target.closest('.' + EXPAND);
    if (expand && expand.__quireTable) {
      event.preventDefault();
      event.stopPropagation();
      var table = expand.__quireTable;
      var caption = table.querySelector('caption');
      report({ kind: 'table', html: table.outerHTML, base: document.baseURI, styles: styles(), caption: caption ? caption.textContent : '' });
      return;
    }
  }, true);

  document.addEventListener('click', function (event) {
    if (event.defaultPrevented || event.button !== 0) { return; }
    var link = linkOf(event.target);
    if (!link) { return; }
    // Never let the WebView follow the link itself (see ContentScripts.kt).
    event.preventDefault();
    // A tap while text is selected only dismisses the selection.
    var selection = window.getSelection();
    if (selection && !selection.isCollapsed) { return; }
    var url;
    try { url = new URL(hrefOf(link), document.baseURI).href; } catch (e) { return; }
    if (window.Android && window.Android.onLink) { window.Android.onLink(url); }
  }, true);
})();
//]]>
</script>"""

    /** The id of the `<style>` element holding the user fonts' `@font-face` rules. */
    private const val USER_FONTS_ID = "quire-user-fonts"

    private fun userFontCss(assetsBaseHref: AbsoluteUrl): String =
        UserFontRegistry.fontFaceCss { name -> assetsBaseHref.resolve(Url("fonts/user/$name")!!).toString() }

    /** The `<style>` element with the user fonts' rules, injected into every reflowable document. */
    fun userFontStyle(assetsBaseHref: AbsoluteUrl): String =
        """<style id="$USER_FONTS_ID">${userFontCss(assetsBaseHref)}</style>"""

    /** Replaces the user fonts' rules in a document that is already loaded. */
    fun userFontsScript(assetsBaseHref: AbsoluteUrl): String =
        "(function(){var s=document.getElementById('$USER_FONTS_ID');" +
            "if(!s){s=document.createElement('style');s.id='$USER_FONTS_ID';(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=${JSONObject.quote(userFontCss(assetsBaseHref))};})();"
}
