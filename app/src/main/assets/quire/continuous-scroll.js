/*
 * Quire's continuous-scroll shell logic (openspec change eager-seamless-scroll).
 * Loaded by continuous-scroll.html inside the single scroll-mode WebView.
 *
 * The Android side drives everything through the `QuireShell` JavascriptInterface
 * (bound by ContinuousBookWebView):
 *
 *   QuireShell.addFrame(url, href)   -> append a same-origin, full-height frame for a
 *                                       reading-order resource; reports readiness events
 *   QuireShell.scriptInFrame(href,…) -> frame-local script execution is provided by the
 *                                       native side per resource (see ContinuousBookWebView);
 *                                       the shell exposes frame lookup by original href
 *   QuireShell.commitHeights(...)    -> batched frame sizing from measured content
 *
 * The shell reports back through `QuireShell.event(json)`:
 *
 *   { kind: "frameLoaded",  href, height }  frame document loaded and measured
 *   { kind: "geometry",     heights }       one content height per href, in CSS px
 *   { kind: "scroll",       y, top }        outer scroll position moved (y px, top href)
 *   { kind: "ready",        heights }       all frames loaded; layout settled
 *   { kind: "error",        href, message } a frame failed to load (terminal for the book)
 *
 * All heights are CSS pixels of the frame's intrinsic content. The reader viewport
 * dimensions live only on the native side; the shell never sizes frames from them.
 *
 * Disabling scroll-context pagination and root viewport minimums happens through the
 * Readium CSS user view (`readium-scroll-on`), which the navigator requests for this
 * surface; no shell-level override duplicates it.
 */

var QuireShellHost = (function () {
  'use strict';

  // Sparse by chapter index in reading order: {href, el, height, loaded}
  var frames = [];
  var pendingFrames = 0;
  var ready = false;

  function emit(event) {
    if (window.QuireShell && window.QuireShell.event) {
      window.QuireShell.event(JSON.stringify(event));
    }
  }

  /** Intrinsic content height of a frame document, in CSS px. */
  function measure(el) {
    try {
      var d = el.contentDocument;
      if (!d) return 0;
      var de = d.documentElement;
      var body = d.body;
      var h = Math.max(
        de ? Math.ceil(de.getBoundingClientRect().height) : 0,
        body ? Math.ceil(body.getBoundingClientRect().height) : 0,
        de ? de.scrollHeight : 0,
        body ? body.scrollHeight : 0
      );
      return h;
    } catch (e) {
      return 0;
    }
  }

  /** Applies the measured content height to one frame. Returns the height. */
  function applyHeight(i) {
    var f = frames[i];
    if (!f || !f.el) return 0;
    var h = measure(f.el);
    if (h > 0) {
      if (f.height !== h) {
        f.height = h;
        f.el.style.height = h + 'px';
      }
      return h;
    }
    return f.height;
  }

  /**
   * Appends the frame for the resource at reading-order [index], served at [url] and
   * identified by its original [href]. Load completion and failures are reported as
   * events; heights settle before `frameLoaded` fires.
   */
  function addFrame(index, url, href) {
    var el = document.createElement('iframe');
    el.setAttribute('scrolling', 'no');
    el.setAttribute('data-href', href);
    el.style.overflow = 'hidden';
    frames[index] = { href: href, url: url, el: el, height: 0, loaded: false };
    pendingFrames++;
    el.addEventListener('load', function () {
      var settle = function () {
        var f = frames[index];
        if (!f) return;
        var doc = el.contentDocument;
        var win = el.contentWindow;
        if (!doc || !win) { emit({ kind: 'error', href: href, message: 'frame vanished' }); return; }
        // Wait for fonts, then for every static image (load or error), then measure.
        var afterFonts = function () {
          var imgs = Array.prototype.slice.call(doc.images || []);
          var remaining = imgs.filter(function (img) { return !img.complete; });
          var done = function () {
            var f2 = frames[index];
            if (!f2) return;
            var h = applyHeight(index);
            if (h <= 0) { emit({ kind: 'error', href: href, message: 'frame loaded without measurable content' }); return; }
            f2.loaded = true;
            pendingFrames--;
            emit({ kind: 'frameLoaded', href: href, height: h });
            checkAllLoaded();
          };
          if (!remaining.length) { done(); return; }
          var left = remaining.length;
          var one = function () { left--; if (left === 0) window.setTimeout(done, 30); };
          remaining.forEach(function (img) {
            img.addEventListener('load', one, { once: true });
            img.addEventListener('error', one, { once: true });
          });
          // Broken/slow images must not hold the book forever: settle after 10s.
          window.setTimeout(function () { left = Math.min(left, 1); one(); }, 10000);
        };
        if (win.document && win.document.fonts && win.document.fonts.ready) {
          win.document.fonts.ready.then(afterFonts, afterFonts);
        } else {
          afterFonts();
        }
      };
      window.setTimeout(settle, 60);
    });
    el.addEventListener('error', function () {
      emit({ kind: 'error', href: href, message: 'frame failed to load' });
    });
    el.src = url;
    document.body.appendChild(el);
  }

  /** Reports a batched geometry update; [heights] maps original href -> CSS px. */
  function commitHeights(heights) {
    // heights: { href: px } — applied by href so the native side owns batching.
    var changed = {};
    for (var i = 0; i < frames.length; i++) {
      var f = frames[i];
      if (!f) continue;
      var h = heights[f.href];
      if (h !== undefined && h > 0 && h !== f.height) {
        f.height = h;
        f.el.style.height = h + 'px';
        changed[f.href] = h;
      }
    }
    emit({ kind: 'geometry', heights: changed });
  }

  function checkAllLoaded() {
    if (ready || pendingFrames > 0) return;
    for (var i = 0; i < frames.length; i++) {
      if (frames[i] && !frames[i].loaded) return;
      if (!frames[i]) return; // a gap in the reading order: not all frames created
    }
    ready = true;
    emit({ kind: 'ready', heights: currentHeights() });
  }

  function currentHeights() {
    var out = {};
    frames.forEach(function (f, i) {
      if (f) out[f.href] = f.height;
    });
    return out;
  }

  /** The original href of the frame whose content starts at or above outer [y]. */
  function topHrefAt(y) {
    var top = 0;
    for (var i = 0; i < frames.length; i++) {
      var f = frames[i];
      if (!f) continue;
      if (y < top + f.height) return f.href;
      top += f.height;
    }
    return frames.length ? frames[frames.length - 1].href : null;
  }

  /** The book coordinate of the top of the frame at [href], in CSS px. */
  function topOf(href) {
    var top = 0;
    for (var i = 0; i < frames.length; i++) {
      var f = frames[i];
      if (!f) continue;
      if (f.href === href) return top;
      top += f.height;
    }
    return null;
  }

  /** Re-derives heights for every loaded frame and reports them in one batch. */
  function remeasureAll() {
    var out = {};
    var changedAny = false;
    for (var i = 0; i < frames.length; i++) {
      var f = frames[i];
      if (!f || !f.loaded) continue;
      // A new reader viewport re-derives the frame's viewport-relative lengths (the frame
      // box is content height, so `vh` must come from the reader viewport) before the
      // content is measured again.
      try {
        if (f.el.contentWindow && f.el.contentWindow.__quireViewportUnits) {
          f.el.contentWindow.__quireViewportUnits();
        }
      } catch (e) { }
      var h = applyHeight(i);
      if (h > 0) {
        out[f.href] = h;
        changedAny = true;
      }
    }
    if (changedAny) emit({ kind: 'geometry', heights: out });
    // A re-measure is a layout generation: the native side waits for this to commit the new
    // table and restore the reading anchor, so it must be reported even when nothing moved.
    emit({ kind: 'remeasured' });
    checkAllLoaded();
  }

  // The native side observes outer scrolling itself (onScrollChanged); the shell only
  // exposes geometry queries. Selection/decoration/tap events are frame-local: they
  // arrive through the per-frame bridge injected next to Readium's scripts, carrying
  // the originating href, so the outer document does not relay them.

  return {
    addFrame: addFrame,
    commitHeights: commitHeights,
    remeasureAll: remeasureAll,
    topHrefAt: topHrefAt,
    topOf: topOf,
    frameByHref: function (href) {
      for (var i = 0; i < frames.length; i++) {
        if (frames[i] && frames[i].href === href) return frames[i].el;
      }
      return null;
    },
    isReady: function () { return ready; }
  };
})();
