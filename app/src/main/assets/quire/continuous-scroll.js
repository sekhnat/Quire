/*
 * Quire's continuous-scroll shell logic (openspec changes eager-seamless-scroll and
 * bounded-scroll-window). Loaded by continuous-scroll.html inside the single scroll-mode
 * WebView.
 *
 * The book is one column of SLOTS, one per reading-order resource, each with a height. Only
 * the slots near the viewport hold a live iframe (the document, its Readium runtime, fonts
 * and decoded images); every other slot is an empty box of its last measured height, or of an
 * estimate until a background pass has measured it. This keeps memory bounded by the window,
 * not by the size of the book, while the outer document stays the only scrollable range.
 *
 * The Android side drives everything through the `QuireShellHost` global:
 *
 *   addFrame(index, url, href, positionCount)  create the slot for a reading-order resource
 *   initialWindow(index)                       scroll to a slot, mount around it, report `ready`
 *   scrollToOffset(href, within)               scroll to a CSS-px offset inside a slot
 *   viewportPosition()                         {href, within, height} at the reading position
 *   captureAnchor()                            viewportPosition() plus the first visible block, for a reflow
 *   pin(href) / unpin(href)                    keep a slot live (jumps, selections, scripts)
 *   remeasureAll()                             a reflow: remeasure live frames, rescale the rest
 *   frameByHref(href)                          the live iframe of a slot, or null
 *
 * and the shell reports back through `QuireShell.event(json)`:
 *
 *   { kind: "frameLoaded",  href, height }  a slot's document loaded and was measured
 *   { kind: "frameEvicted", href }          a slot's document was unloaded
 *   { kind: "geometry",     heights }       every slot's height, one entry per href, CSS px
 *   { kind: "ready",        heights }       the initial window settled
 *   { kind: "remeasured" }                  a remeasureAll() finished
 *   { kind: "error",        href, message } a document failed before the book was ready
 *
 * Heights are CSS pixels of the frame's intrinsic content. Changes above the viewport are
 * compensated with a scroll adjustment in the same task as the height change, so the text
 * being read does not move (native scroll anchoring is switched off in the shell document: it
 * does not see through iframe swaps).
 */

var QuireShellHost = (function () {
  'use strict';

  // The live window, in reader viewports around the viewport. Slots inside MOUNT_* are
  // loaded; live slots outside KEEP_* are unloaded. The gap is hysteresis against thrash.
  var MOUNT_BEHIND = 1.5, MOUNT_AHEAD = 2.5;
  var KEEP_BEHIND = 4, KEEP_AHEAD = 6;
  // Hard cap on live frames; the farthest unpinned frame goes first.
  var MAX_LIVE = 8;
  // Background measurement: slots loaded only to learn their height.
  var BG_CONCURRENCY = 2;
  var BG_PAUSE_MS = 150;
  var SCROLL_QUIET_MS = 250;
  var RETRY_AFTER_FAILURE_MS = 30000;
  var DEFAULT_PX_PER_POSITION_RATIO = 0.9; // of the viewport, until the first measurement
  var LOADING_FRAME_PX = 150; // an iframe's default height, as before slots existed
  var SEAM_SLACK = 4; // CSS px: see applyHeights

  // slots[i] = {href, url, el, frame, state, height, measured, stale, positions, pins, bg, failedAt}
  //   state: 'placeholder' | 'loading' | 'live'
  var slots = [];
  var ready = false;
  var initialIndex = -1;
  var pxPerPosition = 0;
  var lastScrollAt = 0;
  var windowQueued = false;
  var geometryQueued = false;
  var desiredY = null; // the exact scroll position last asked for by a compensation, or null

  function emit(event) {
    if (window.QuireShell && window.QuireShell.event) {
      window.QuireShell.event(JSON.stringify(event));
    }
  }

  /** The reader viewport height in CSS px (the shell's own height is zero before the book is ready). */
  function viewportHeight() {
    var h = 0;
    try { h = window.QuireShell && window.QuireShell.getViewportHeight ? window.QuireShell.getViewportHeight() : 0; } catch (e) { }
    return Math.max(h || 0, window.innerHeight || 0, 600);
  }

  /** Intrinsic content height of a frame document, in CSS px. */
  function measure(el) {
    try {
      var d = el.contentDocument;
      if (!d) return 0;
      var de = d.documentElement;
      var body = d.body;
      return Math.max(
        de ? Math.ceil(de.getBoundingClientRect().height) : 0,
        body ? Math.ceil(body.getBoundingClientRect().height) : 0,
        de ? de.scrollHeight : 0,
        body ? body.scrollHeight : 0
      );
    } catch (e) {
      return 0;
    }
  }

  function estimateFor(slot) {
    var per = pxPerPosition || viewportHeight() * DEFAULT_PX_PER_POSITION_RATIO;
    return Math.max(1, Math.round(Math.max(1, slot.positions) * per));
  }

  /**
   * Applies new heights to slots in one batch. A slot that lies above the viewport top shifts
   * everything below it, so the scroll position is moved by the same amount before the next paint
   * and the visible text stays where it is. A slot whose bottom edge is within [SEAM_SLACK] of the
   * viewport top counts as above: a jump lands within a pixel of a chapter start, and the chapter
   * before it is the one that changes next.
   */
  function applyHeights(changes) {
    var y = window.pageYOffset;
    var top = 0;
    var compensation = 0;
    var any = false;
    for (var i = 0; i < slots.length; i++) {
      var s = slots[i];
      var old = s.height;
      var h = changes[i];
      if (h !== undefined && h > 0 && h !== old) {
        if (top + old <= y + SEAM_SLACK) compensation += h - old;
        s.height = h;
        s.el.style.height = h + 'px';
        if (s.frame) s.frame.style.height = h + 'px';
        any = true;
      }
      top += old;
    }
    if (compensation !== 0) {
      // The browser snaps the scroll offset to device pixels, and reading it back would add that
      // rounding to every correction. While the reader has not moved, carry on from the exact
      // position we asked for last time instead.
      var base = desiredY !== null && Math.abs(desiredY - y) < 1.5 ? desiredY : y;
      desiredY = base + compensation;
      window.scrollTo(0, desiredY);
      // The scroll position and the heights moved together: publish them together, or the native
      // table would classify the new position against the old heights for up to a batch.
      flushGeometry();
    } else if (any) {
      queueGeometry();
    }
  }

  /** Re-derives the px-per-position estimate from measured slots and rescales the unmeasured ones. */
  function recalibrate() {
    var px = 0, pos = 0;
    slots.forEach(function (s) {
      if (s.measured) { px += s.height; pos += Math.max(1, s.positions); }
    });
    if (!pos) return;
    var next = px / pos;
    if (pxPerPosition && Math.abs(next - pxPerPosition) / pxPerPosition < 0.03) return;
    pxPerPosition = next;
    var changes = {};
    slots.forEach(function (s, i) {
      if (!s.measured && s.state === 'placeholder') changes[i] = estimateFor(s);
    });
    applyHeights(changes);
  }

  var geometryTimer = 0;

  function queueGeometry() {
    if (geometryQueued) return;
    geometryQueued = true;
    geometryTimer = window.setTimeout(flushGeometry, 100);
  }

  /** Publishes every slot height now. */
  function flushGeometry() {
    window.clearTimeout(geometryTimer);
    geometryQueued = false;
    emit({ kind: 'geometry', heights: currentHeights() });
  }

  function currentHeights() {
    var out = {};
    slots.forEach(function (s) { out[s.href] = s.height; });
    return out;
  }

  // ── slots ───────────────────────────────────────────────────────────────────

  /** Creates the slot for the resource at reading-order [index]; no document is loaded yet. */
  function addFrame(index, url, href, positionCount) {
    var el = document.createElement('div');
    el.className = 'slot';
    el.setAttribute('data-href', href);
    var slot = {
      href: href, url: url, el: el, frame: null, state: 'placeholder', height: 0,
      measured: false, stale: false, positions: positionCount || 1, pins: 0, bg: false, failedAt: 0
    };
    slot.height = estimateFor(slot);
    el.style.height = slot.height + 'px';
    slots[index] = slot;
    document.body.appendChild(el);
    queueGeometry();
  }

  function indexOfHref(href) {
    for (var i = 0; i < slots.length; i++) if (slots[i] && slots[i].href === href) return i;
    return -1;
  }

  /** Loads the document of slot [i]: iframe, then fonts, then images, then a measurement. */
  function mount(i, background) {
    var s = slots[i];
    if (!s || s.state !== 'placeholder') return;
    s.state = 'loading';
    s.bg = !!background;
    var el = document.createElement('iframe');
    el.setAttribute('scrolling', 'no');
    el.setAttribute('data-href', s.href);
    el.style.overflow = 'hidden';
    // An unmeasured chapter loads in a small box, not the slot's estimate: a document cannot measure
    // shorter than the frame it is laid out in, so a tall box would stop a short chapter from ever
    // reporting its real height. A chapter that was measured before is shown at that height at once.
    el.style.height = (s.measured && !s.stale ? s.height : LOADING_FRAME_PX) + 'px';
    s.frame = el;
    var alive = function () { return s.frame === el && s.state === 'loading'; };
    var fail = function (message) {
      if (!alive()) return;
      unmount(i);
      s.failedAt = Date.now();
      // Before the book is ready a missing document is terminal; afterwards the slot just
      // stays an empty box and is retried later.
      if (!ready) emit({ kind: 'error', href: s.href, message: message });
    };
    el.addEventListener('load', function () {
      window.setTimeout(function () {
        if (!alive()) return;
        var doc = el.contentDocument, win = el.contentWindow;
        if (!doc || !win) { fail('frame vanished'); return; }
        var afterFonts = function () {
          if (!alive()) return;
          var imgs = Array.prototype.slice.call(doc.images || []);
          var remaining = imgs.filter(function (img) { return !img.complete; });
          var done = function () {
            if (!alive()) return;
            var h = measure(el);
            if (h <= 0) { fail('frame loaded without measurable content'); return; }
            s.state = 'live';
            s.measured = true;
            s.stale = false;
            // applyHeights only restyles a slot whose height changed; a chapter loaded again at the
            // height it already had still has to leave the small loading box.
            el.style.height = h + 'px';
            var changes = {};
            changes[i] = h;
            applyHeights(changes);
            recalibrate();
            emit({ kind: 'frameLoaded', href: s.href, height: h });
            afterChange();
          };
          if (!remaining.length) { done(); return; }
          var left = remaining.length;
          var one = function () { left--; if (left === 0) window.setTimeout(done, 30); };
          remaining.forEach(function (img) {
            img.addEventListener('load', one, { once: true });
            img.addEventListener('error', one, { once: true });
          });
          // Broken/slow images must not hold the frame forever: settle after 10s.
          window.setTimeout(function () { left = Math.min(left, 1); one(); }, 10000);
        };
        if (win.document && win.document.fonts && win.document.fonts.ready) {
          win.document.fonts.ready.then(afterFonts, afterFonts);
        } else {
          afterFonts();
        }
      }, 60);
    });
    el.addEventListener('error', function () { fail('frame failed to load'); });
    el.src = s.url;
    s.el.appendChild(el);
  }

  /** Unloads the document of slot [i]; the slot keeps its height. */
  function unmount(i) {
    var s = slots[i];
    if (!s || s.state === 'placeholder') return;
    var wasLive = s.state === 'live';
    if (s.frame) {
      try { s.frame.src = 'about:blank'; } catch (e) { }
      if (s.frame.parentNode) s.frame.parentNode.removeChild(s.frame);
    }
    s.frame = null;
    s.state = 'placeholder';
    s.bg = false;
    if (wasLive) emit({ kind: 'frameEvicted', href: s.href });
  }

  // ── the live window ─────────────────────────────────────────────────────────

  /** Mounts what the viewport needs and unloads what is far away or over the cap. */
  function updateWindow() {
    windowQueued = false;
    if (!slots.length) return;
    var vp = viewportHeight();
    var y = window.pageYOffset;
    var mountLo = y - MOUNT_BEHIND * vp, mountHi = y + vp + MOUNT_AHEAD * vp;
    var keepLo = y - KEEP_BEHIND * vp, keepHi = y + vp + KEEP_AHEAD * vp;
    var centre = y + vp / 2;
    var now = Date.now();

    var tops = [], top = 0;
    for (var i = 0; i < slots.length; i++) { tops[i] = top; top += slots[i].height; }

    var live = [];
    for (i = 0; i < slots.length; i++) {
      var s = slots[i];
      var lo = tops[i], hi = tops[i] + s.height;
      var inMount = hi > mountLo && lo < mountHi;
      var inKeep = hi > keepLo && lo < keepHi;
      if (s.state === 'placeholder') {
        if ((inMount || s.pins > 0) && now - s.failedAt > RETRY_AFTER_FAILURE_MS) mount(i, false);
      } else if (!inKeep && s.pins === 0) {
        unmount(i);
      }
      if (s.state !== 'placeholder') live.push({ i: i, dist: Math.abs((lo + hi) / 2 - centre), inMount: inMount });
    }
    // Over the cap: drop the farthest unpinned frame that the viewport does not need.
    if (live.length > MAX_LIVE) {
      live.sort(function (a, b) { return b.dist - a.dist; });
      for (var k = 0; k < live.length && live.length - k > MAX_LIVE; k++) {
        var cand = live[k];
        if (slots[cand.i].pins === 0 && !cand.inMount) unmount(cand.i);
      }
    }
    scheduleBackgroundMeasurement();
    checkReady();
  }

  function queueWindow() {
    if (windowQueued) return;
    windowQueued = true;
    window.requestAnimationFrame(updateWindow);
  }

  /** Called after a document settled: its neighbours may now be inside or outside the window. */
  function afterChange() { queueWindow(); }

  // ── background measurement ──────────────────────────────────────────────────

  var bgTimer = 0;

  function scheduleBackgroundMeasurement() {
    if (bgTimer || !ready) return;
    bgTimer = window.setTimeout(function () { bgTimer = 0; measureNextInBackground(); }, BG_PAUSE_MS);
  }

  /** Loads the nearest unmeasured slot just to learn its height, a couple at a time, never mid-scroll. */
  function measureNextInBackground() {
    if (Date.now() - lastScrollAt < SCROLL_QUIET_MS) { scheduleBackgroundMeasurement(); return; }
    var loadingBg = 0, loadingAny = 0;
    slots.forEach(function (s) {
      if (s.state === 'loading') { loadingAny++; if (s.bg) loadingBg++; }
    });
    if (loadingAny - loadingBg > 0 || loadingBg >= BG_CONCURRENCY) { scheduleBackgroundMeasurement(); return; }
    var vp = viewportHeight(), centre = window.pageYOffset + vp / 2;
    var best = -1, bestDist = Infinity, top = 0, now = Date.now();
    for (var i = 0; i < slots.length; i++) {
      var s = slots[i];
      if (s.state === 'placeholder' && !s.measured && now - s.failedAt > RETRY_AFTER_FAILURE_MS) {
        var d = Math.abs(top + s.height / 2 - centre);
        if (d < bestDist) { bestDist = d; best = i; }
      }
      top += s.height;
    }
    if (best < 0) return; // everything is measured: nothing left to do
    mount(best, true);
    scheduleBackgroundMeasurement();
  }

  // ── readiness ───────────────────────────────────────────────────────────────

  /** The book is ready once every slot around the initial position is loaded. */
  function checkReady() {
    if (ready || initialIndex < 0) return;
    var vp = viewportHeight(), y = window.pageYOffset;
    var lo = y - 0.5 * vp, hi = y + 1.5 * vp, top = 0;
    for (var i = 0; i < slots.length; i++) {
      var s = slots[i];
      if (top + s.height > lo && top < hi && s.state !== 'live') return;
      top += s.height;
    }
    ready = true;
    emit({ kind: 'ready', heights: currentHeights() });
    scheduleBackgroundMeasurement();
  }

  /** Scrolls to the top of slot [index] (an estimate if unmeasured) and mounts around it. */
  function initialWindow(index) {
    initialIndex = Math.max(0, Math.min(index, slots.length - 1));
    var top = 0;
    for (var i = 0; i < initialIndex; i++) top += slots[i].height;
    window.scrollTo(0, top);
    updateWindow();
  }

  /**
   * Scrolls to [within] CSS px inside the slot of [href], from the slot heights as they are right
   * now. The native side resolves jump targets here rather than from its own table, which trails
   * the shell by a geometry batch and goes stale the moment a measurement rescales the estimates.
   */
  function scrollToOffset(href, within) {
    var i = indexOfHref(href);
    if (i < 0) return false;
    var top = 0;
    for (var j = 0; j < i; j++) top += slots[j].height;
    desiredY = Math.max(0, Math.floor(top + within));
    window.scrollTo(0, desiredY);
    return true;
  }

  /**
   * The resource at the reading position (one CSS px below the viewport top) and the offset inside
   * it, from the slot heights as they are right now.
   */
  function viewportPosition() {
    var y = window.pageYOffset + 1;
    var top = 0;
    for (var i = 0; i < slots.length; i++) {
      var s = slots[i];
      if (y < top + s.height || i === slots.length - 1) {
        return { href: s.href, within: Math.max(0, y - top), height: s.height };
      }
      top += s.height;
    }
    return null;
  }

  /**
   * The reading anchor for a reflow, in one synchronous step: the resource at the reading position,
   * the offset inside it, and the first block element visible at the viewport top (tag and index,
   * with its offset in the frame). Capturing in one evaluation matters: the reflow's own style
   * change is queued right behind it, and a second round trip would measure the new layout.
   */
  function captureAnchor() {
    var pos = viewportPosition();
    if (!pos) return null;
    var i = indexOfHref(pos.href);
    var s = slots[i];
    pos.anchor = null;
    if (s && s.state === 'live' && s.frame) {
      try {
        // The shell's viewport top, in the frame's own coordinates (the frame is as tall as its document).
        var viewportTop = -s.frame.getBoundingClientRect().top;
        var blocks = s.frame.contentDocument.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote');
        for (var k = 0; k < blocks.length; k++) {
          var r = blocks[k].getBoundingClientRect();
          if (r.bottom > viewportTop) { pos.anchor = { selector: blocks[k].tagName + ':' + k, top: r.top }; break; }
        }
      } catch (e) { }
    }
    return pos;
  }

  // ── pins ────────────────────────────────────────────────────────────────────

  function pin(href) {
    var i = indexOfHref(href);
    if (i < 0) return;
    slots[i].pins++;
    slots[i].failedAt = 0;
    if (slots[i].state === 'placeholder') mount(i, false);
  }

  function unpin(href) {
    var i = indexOfHref(href);
    if (i < 0 || slots[i].pins === 0) return;
    slots[i].pins--;
    queueWindow();
  }

  // ── reflow ──────────────────────────────────────────────────────────────────

  /**
   * A reflow (font size, theme, viewport): remeasure every live frame, scale the other slots by
   * the same factor and let the background pass re-measure them. Reports once the live frames are
   * done, so the native side can restore the reading anchor against the new table.
   */
  function remeasureAll() {
    var changes = {}, oldSum = 0, newSum = 0;
    for (var i = 0; i < slots.length; i++) {
      var s = slots[i];
      if (s.state !== 'live') continue;
      // A new reader viewport re-derives the frame's viewport-relative lengths (the frame box is
      // content height, so `vh` must come from the reader viewport) before measuring again.
      try {
        if (s.frame.contentWindow && s.frame.contentWindow.__quireViewportUnits) {
          s.frame.contentWindow.__quireViewportUnits();
        }
      } catch (e) { }
      var h = measure(s.frame);
      // A document cannot measure shorter than the frame it sits in, so after a size decrease this
      // height can be too tall. Do not carry it into the next load: that one starts again from the
      // small loading box and measures properly.
      s.stale = true;
      if (h > 0) { oldSum += s.height; newSum += h; changes[i] = h; }
    }
    var ratio = oldSum > 0 ? newSum / oldSum : 1;
    for (i = 0; i < slots.length; i++) {
      s = slots[i];
      if (s.state === 'live') continue;
      s.measured = false;
      if (ratio !== 1) changes[i] = Math.max(1, Math.round(s.height * ratio));
    }
    pxPerPosition = pxPerPosition * ratio;
    applyHeights(changes);
    emit({ kind: 'geometry', heights: currentHeights() });
    emit({ kind: 'remeasured' });
    scheduleBackgroundMeasurement();
  }

  window.addEventListener('scroll', function () {
    lastScrollAt = Date.now();
    queueWindow();
  }, { passive: true });

  return {
    addFrame: addFrame,
    initialWindow: initialWindow,
    scrollToOffset: scrollToOffset,
    viewportPosition: viewportPosition,
    captureAnchor: captureAnchor,
    pin: pin,
    unpin: unpin,
    remeasureAll: remeasureAll,
    frameByHref: function (href) {
      var i = indexOfHref(href);
      return i >= 0 && slots[i].state === 'live' ? slots[i].frame : null;
    },
    isReady: function () { return ready; },
    liveCount: function () {
      return slots.filter(function (s) { return s.state !== 'placeholder'; }).length;
    },
    measuredCount: function () {
      return slots.filter(function (s) { return s.measured; }).length;
    }
  };
})();
