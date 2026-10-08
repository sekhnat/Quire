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
 *   flingHint(stop, velocity, durationMs)      a fling started: its predicted stop (CSS px); null stops it
 *   setPressure(level)                         memory pressure: 0 normal, 1 reduced, 2 minimal
 *   setPolicy(name)                            'adaptive' (the default) or 'static', the fixed window
 *   planWindow(input)                          the window policy as a pure function (tests)
 *   enableTelemetry()                          benchmark builds only: start reporting `telemetry`
 *
 * and the shell reports back through `QuireShell.event(json)`:
 *
 *   { kind: "frameLoaded",  href, height }  a slot's document loaded and was measured
 *   { kind: "frameEvicted", href }          a slot's document was unloaded
 *   { kind: "geometry",     heights }       every slot's height, one entry per href, CSS px
 *   { kind: "ready",        heights }       the initial window settled
 *   { kind: "remeasured" }                  a remeasureAll() finished
 *   { kind: "error",        href, message } a document failed before the book was ready
 *   { kind: "telemetry",    type, ... }     benchmark builds only, see the telemetry section
 *
 * Heights are CSS pixels of the frame's intrinsic content. Changes above the viewport are
 * compensated with a scroll adjustment in the same task as the height change, so the text
 * being read does not move (native scroll anchoring is switched off in the shell document: it
 * does not see through iframe swaps).
 */

var QuireShellHost = (function () {
  'use strict';

  // The live window, in reader viewports around the viewport. Slots inside the mount range are
  // loaded; live slots outside the keep range are unloaded. The gap is hysteresis against thrash.
  //
  // STATIC is the fixed window the shell started with, kept as the benchmark's baseline. The
  // adaptive policy (the default) starts from the tier row for the current memory pressure and
  // leans it into the direction of travel as the reader scrolls faster, see planWindow.
  var STATIC = { mountBehind: 1.5, mountAhead: 2.5, keepBehind: 4, keepAhead: 6, maxLive: 8 };
  // Per memory-pressure tier (normal, reduced, minimal, set by the native side): ranges in
  // viewports, at most `maxLive` documents and `budget` × WEIGHT_BUDGET estimated bytes held, at
  // most `maxLoading` loads at once for documents the viewport does not show yet, and `bg`
  // background measurements at a time.
  var TIERS = [
    { mountBehind: 1.5, mountAhead: 2.5, keepBehind: 4, keepAhead: 6, maxLive: 8, maxLoading: 3, bg: 2, budget: 1 },
    { mountBehind: 0.5, mountAhead: 1.5, keepBehind: 2, keepAhead: 3, maxLive: 5, maxLoading: 2, bg: 1, budget: 0.5 },
    { mountBehind: 0.25, mountAhead: 0.75, keepBehind: 1, keepAhead: 1.5, maxLive: 3, maxLoading: 1, bg: 0, budget: 0.25 }
  ];
  // Moving faster than this (viewports per second) the window leans into the direction of travel:
  // the lookahead grows by the distance covered in LOOKAHEAD_S, up to LOOKAHEAD_MAX_VP, and the
  // trailing ranges shrink.
  var MOVING_VP_PER_S = 1;
  var LOOKAHEAD_S = 0.6;
  var LOOKAHEAD_MAX_VP = 12;
  // A fling's predicted stop (see flingHint): the slots around it load first, once the fling is due
  // to stop within LANDING_LEAD_MS. Not sooner: most flings are caught by a finger well short of their
  // stop, and a document loads in about a tenth of a second. A hint is dropped FLING_HINT_GRACE_MS
  // after the fling was due to stop.
  var LANDING_BEFORE_VP = 0.25, LANDING_AFTER_VP = 0.75;
  var LANDING_LEAD_MS = 1000;
  var FLING_HINT_GRACE_MS = 500;
  // About this long into a fling its observed velocity is checked against the hint; off by more than
  // FLING_TOLERANCE either way, the predicted distance is rescaled (see correctFling).
  var FLING_OBSERVE_MS = 100;
  var FLING_TOLERANCE = 1.25;
  // Android's fling spline: distance ∝ v^(r / (r − 1)) and duration ∝ v^(1 / (r − 1)), r = ln 0.78 / ln 0.9.
  var FLING_DISTANCE_EXPONENT = 1.736;
  var FLING_DURATION_EXPONENT = 0.736;
  // Velocity: an exponential average of the scroll events, each clamped to MAX_VP_PER_S; no event
  // for SCROLL_IDLE_MS is a stop.
  var VELOCITY_SMOOTHING = 0.4;
  var MAX_VP_PER_S = 60;
  var SCROLL_IDLE_MS = 150;
  // Estimated bytes a live document holds in the renderer: a fixed cost per document (its
  // Readium runtime and styles), a cost per element and the decoded pixels of its images.
  var DOC_BASE_BYTES = 1.5e6;
  var NODE_BYTES = 600;
  var DEFAULT_WEIGHT_BYTES = 4e6; // per document, until one has been weighed
  var WEIGHT_BUDGET = 192e6;
  // Expected time from mount to a live document, until loads have been timed; then an average.
  var DEFAULT_LOAD_MS = 300;
  var LOAD_MS_SMOOTHING = 0.2;
  // Background measurement: slots loaded only to learn their height.
  var BG_PAUSE_MS = 150;
  var SCROLL_QUIET_MS = 250;
  var RETRY_AFTER_FAILURE_MS = 30000;
  var DEFAULT_PX_PER_POSITION_RATIO = 0.9; // of the viewport, until the first measurement
  var LOADING_FRAME_PX = 150; // an iframe's default height, as before slots existed
  var SEAM_SLACK = 4; // CSS px: see applyHeights

  // slots[i] = {href, url, el, frame, state, height, measured, stale, positions, pins, bg, failedAt,
  //             weight, weighed, mountedAt, docLoaded, seen}
  //   state: 'placeholder' | 'loading' | 'live'
  var slots = [];
  var ready = false;
  var initialIndex = -1;
  var pxPerPosition = 0;
  var bytesPerPosition = 0;
  var expectedLoadMs = DEFAULT_LOAD_MS;
  var lastScrollAt = 0;
  var windowQueued = false;
  var geometryQueued = false;
  var desiredY = null; // the exact scroll position last asked for by a compensation, or null
  var policy = 'adaptive'; // the window policy, see setPolicy
  var tier = 0; // memory pressure: 0 normal, 1 reduced, 2 minimal, see setPressure
  // Motion: velocity in CSS px per second (positive is forwards), the last direction of travel, and
  // the active fling's predicted stop.
  var velocity = 0, direction = 1, lastY = 0, lastScrollEventAt = 0;
  // How far the shell itself moved the document since the last scroll event (compensations, jumps):
  // not the reader's motion, so it is taken out of the velocity.
  var selfShift = 0;
  var fling = null; // {stop, velocity, at, endAt, start, observed, corrected}

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

  /** Estimated bytes a loaded document holds in the renderer, see DOC_BASE_BYTES. */
  function weigh(doc) {
    var bytes = DOC_BASE_BYTES;
    try {
      bytes += doc.getElementsByTagName('*').length * NODE_BYTES;
      var imgs = doc.images || [];
      for (var i = 0; i < imgs.length; i++) bytes += (imgs[i].naturalWidth || 0) * (imgs[i].naturalHeight || 0) * 4;
    } catch (e) { }
    return bytes;
  }

  /** Re-derives the bytes-per-position prior for documents that have not been weighed yet. */
  function recalibrateWeight() {
    var bytes = 0, pos = 0;
    slots.forEach(function (s) {
      if (s.weighed) { bytes += s.weight; pos += Math.max(1, s.positions); }
    });
    if (pos) bytesPerPosition = bytes / pos;
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
      scrollSelf(desiredY);
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
      measured: false, stale: false, positions: positionCount || 1, pins: 0, bg: false, failedAt: 0,
      weight: 0, weighed: false, mountedAt: 0, docLoaded: false, seen: false
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
    s.mountedAt = Date.now();
    s.docLoaded = false;
    s.seen = false;
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
        s.docLoaded = true;
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
            s.weight = weigh(doc);
            s.weighed = true;
            recalibrateWeight();
            expectedLoadMs += LOAD_MS_SMOOTHING * ((Date.now() - s.mountedAt) - expectedLoadMs);
            // applyHeights only restyles a slot whose height changed; a chapter loaded again at the
            // height it already had still has to leave the small loading box.
            el.style.height = h + 'px';
            var changes = {};
            changes[i] = h;
            applyHeights(changes);
            recalibrate();
            emit({ kind: 'frameLoaded', href: s.href, height: h });
            if (telemetry) telemetryLoaded(s);
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
    if (telemetry) telemetryUnmounted(s);
    if (s.frame) {
      try { s.frame.src = 'about:blank'; } catch (e) { }
      if (s.frame.parentNode) s.frame.parentNode.removeChild(s.frame);
    }
    s.frame = null;
    s.state = 'placeholder';
    s.bg = false;
    s.docLoaded = false;
    if (wasLive) emit({ kind: 'frameEvicted', href: s.href });
    if (telemetry) checkBlank();
  }

  // ── the live window ─────────────────────────────────────────────────────────

  /**
   * Decides which slots to load and which to unload, from a description of the book and the reader's
   * motion alone, so the policy can be tested without documents:
   *
   *   input  { slots: [{height, state, pins, weight, retry, bg}], y, vp, policy, tier, velocity,
   *            direction, stop, stopIn, loadMs, budget, preparing }
   *   output { mount: [index, …] in the order to start them, unmount: [index, …] }
   *
   * `retry` is false while a slot is waiting out a failure; `weight` is its estimated bytes; `stop` is
   * the active fling's predicted stop, or null, and `stopIn` how many ms until it gets there (when
   * not given, estimated from the velocity). The static policy is the fixed window; the adaptive
   * policy ranks every slot:
   *
   *   0  required: in the viewport, or pinned: always loaded, whatever the caps and the budget
   *   1  landing: around the fling's predicted stop, once the fling is due there shortly
   *   2  ahead: in the mount range in the direction of travel, unless the fling will have carried
   *      the viewport past it before it could load
   *   3  behind: in the mount range behind
   *   4  keep: in the keep range: stays loaded if it is already, never loaded for this
   *   5  outside: unloaded
   *
   * and keeps the highest-ranked ones (nearest first within a rank, the cheapest first within the
   * keep range) while they fit `maxLive` documents and the tier's weight budget. Required slots
   * count against both but are never refused. Loads beyond the required ones are started at most
   * `maxLoading` at a time, highest rank first (all at once while the book is `preparing`: nothing
   * moves yet, and the first window should load in parallel); a slot waiting for its turn to load
   * takes no room yet. A background measurement still loading is left alone (and does not count against the
   * loads) unless the tier allows none.
   */
  function planWindow(input) {
    return input.policy === 'static' ? planStatic(input) : planAdaptive(input);
  }

  function planStatic(input) {
    var vp = input.vp, y = input.y, list = input.slots;
    var mountLo = y - STATIC.mountBehind * vp, mountHi = y + vp + STATIC.mountAhead * vp;
    var keepLo = y - STATIC.keepBehind * vp, keepHi = y + vp + STATIC.keepAhead * vp;
    var centre = y + vp / 2;
    var mount = [], unmount = [], live = [], top = 0;
    for (var i = 0; i < list.length; i++) {
      var s = list[i];
      var lo = top, hi = top + s.height;
      top = hi;
      var inMount = hi > mountLo && lo < mountHi;
      var inKeep = hi > keepLo && lo < keepHi;
      var held = s.state !== 'placeholder';
      if (!held) {
        if ((inMount || s.pins > 0) && s.retry) { mount.push(i); held = true; }
      } else if (!inKeep && s.pins === 0) {
        unmount.push(i); held = false;
      }
      if (held) live.push({ i: i, dist: Math.abs((lo + hi) / 2 - centre), inMount: inMount, pins: s.pins });
    }
    // Over the cap: drop the farthest unpinned frame that the viewport does not need.
    if (live.length > STATIC.maxLive) {
      live.sort(function (a, b) { return b.dist - a.dist; });
      for (var k = 0; k < live.length && live.length - k > STATIC.maxLive; k++) {
        var cand = live[k];
        if (cand.pins === 0 && !cand.inMount) {
          var at = mount.indexOf(cand.i);
          if (at >= 0) mount.splice(at, 1); else unmount.push(cand.i);
        }
      }
    }
    return { mount: mount, unmount: unmount };
  }

  function planAdaptive(input) {
    var vp = input.vp, y = input.y, list = input.slots;
    var t = TIERS[Math.max(0, Math.min(TIERS.length - 1, input.tier | 0))];
    var speed = Math.abs(input.velocity) / vp; // viewports per second
    var dir = input.velocity > 0 ? 1 : input.velocity < 0 ? -1 : (input.direction < 0 ? -1 : 1);
    var moving = speed > MOVING_VP_PER_S;
    var lean = moving ? 1 + speed / 4 : 1;
    var lead = t.mountAhead + (moving ? Math.min(LOOKAHEAD_MAX_VP * t.budget, speed * LOOKAHEAD_S) : 0);
    var trail = Math.max(0.25, t.mountBehind / lean);
    var keepLead = Math.max(t.keepAhead, lead + 1);
    var keepTrail = Math.max(trail + 0.5, t.keepBehind / lean);
    var mountLo, mountHi, keepLo, keepHi;
    if (dir > 0) {
      mountLo = y - trail * vp; mountHi = y + vp + lead * vp;
      keepLo = y - keepTrail * vp; keepHi = y + vp + keepLead * vp;
    } else {
      mountLo = y - lead * vp; mountHi = y + vp + trail * vp;
      keepLo = y - keepLead * vp; keepHi = y + vp + keepTrail * vp;
    }
    var stop = input.stop;
    var landLo = stop === null || stop === undefined || !landingDue(input) ? null : stop - LANDING_BEFORE_VP * vp;
    var landHi = landLo === null ? null : stop + vp + LANDING_AFTER_VP * vp;
    var budget = input.budget * t.budget;

    var ranked = [], top = 0, loading = 0, i;
    for (i = 0; i < list.length; i++) {
      var s = list[i];
      var lo = top, hi = top + s.height;
      top = hi;
      if (s.bg && s.state === 'loading') {
        if (t.bg === 0) ranked.push({ i: i, rank: 5, key: 0 });
        continue;
      }
      if (s.state === 'loading') loading++;
      // Distance from the viewport in viewports, signed along the direction of travel.
      var ahead = dir > 0 ? (lo - (y + vp)) / vp : (y - hi) / vp;
      var behind = dir > 0 ? (y - hi) / vp : (lo - (y + vp)) / vp;
      var dist = Math.max(0, ahead, behind);
      var rank;
      if ((hi > y && lo < y + vp) || s.pins > 0) rank = 0;
      else if (landLo !== null && hi > landLo && lo < landHi) rank = 1;
      else if (hi > mountLo && lo < mountHi) {
        // Skipping only ever refuses a load: a document already live stays.
        if (ahead >= 0) rank = s.state === 'placeholder' && passedBeforeLoaded(lo, hi, input, dir, stop) ? 5 : 2;
        else rank = 3;
      } else if (hi > keepLo && lo < keepHi) rank = 4;
      else rank = 5;
      var key = rank === 1 ? Math.abs((lo + hi) / 2 - (stop + vp / 2)) / vp
        : rank === 4 ? s.weight * (1 + dist * (behind > 0 ? 2 : 1))
        : dist;
      ranked.push({ i: i, rank: rank, key: key });
    }
    ranked.sort(function (a, b) { return a.rank - b.rank || a.key - b.key; });

    var mount = [], unmount = [], count = 0, weight = 0;
    var starts = input.preparing ? Infinity : t.maxLoading - loading;
    for (var k = 0; k < ranked.length; k++) {
      var r = ranked[k], slot = list[r.i];
      var held = slot.state !== 'placeholder';
      var wanted = r.rank === 0 ||
        (r.rank < 5 && (held || r.rank < 4) && count < t.maxLive && weight + slot.weight <= budget);
      if (wanted && !held) {
        // Only a load that starts now takes room: a document is not dropped for one that has to wait.
        if (!slot.retry || (r.rank !== 0 && starts <= 0)) continue;
        mount.push(r.i);
        if (r.rank !== 0) starts--;
      }
      if (wanted) {
        count++;
        weight += slot.weight;
      } else if (held) {
        unmount.push(r.i);
      }
    }
    return { mount: mount, unmount: unmount };
  }

  /**
   * True when the active fling will carry the viewport past [lo, hi] before a document mounted now
   * could load: loading it would only compete with the slots the reader will actually stop on. Only
   * with a predicted stop, and only for slots short of it. Assumes the fling decelerates
   * exponentially to its stop: covering d of the remaining D takes −τ·ln(1 − d/D), τ = D / v.
   */
  function passedBeforeLoaded(lo, hi, input, dir, stop) {
    if (stop === null || stop === undefined || !input.velocity) return false;
    var vp = input.vp, y = input.y;
    var remaining = dir > 0 ? stop - y : y - stop;
    // The distance the viewport travels until the slot is entirely behind it.
    var clear = dir > 0 ? hi - y : (y + vp) - lo;
    if (remaining <= 0 || clear >= remaining) return false;
    var tau = remaining / Math.abs(input.velocity);
    var passAt = -tau * Math.log(1 - clear / remaining) * 1000;
    return passAt < input.loadMs;
  }

  /**
   * True when the active fling is due to stop within LANDING_LEAD_MS. Without the fling's own timing,
   * estimated with the same exponential deceleration as passedBeforeLoaded: getting from R away to a
   * viewport away takes τ·ln(R / vp), τ = R / v.
   */
  function landingDue(input) {
    if (input.stopIn !== null && input.stopIn !== undefined) return input.stopIn <= LANDING_LEAD_MS;
    var remaining = Math.abs(input.stop - input.y), speed = Math.abs(input.velocity);
    if (remaining <= input.vp || !speed) return true;
    return remaining / speed * Math.log(remaining / input.vp) * 1000 <= LANDING_LEAD_MS;
  }

  /** The estimated bytes slot [s] holds while live: what it weighed, or a prior from the book so far. */
  function weightOf(s) {
    if (s.weighed) return s.weight;
    return bytesPerPosition ? Math.max(DOC_BASE_BYTES, bytesPerPosition * Math.max(1, s.positions)) : DEFAULT_WEIGHT_BYTES;
  }

  /** Mounts what the viewport needs and unloads what is far away, over the cap or over the budget. */
  function updateWindow() {
    windowQueued = false;
    if (!slots.length) return;
    var now = Date.now();
    var y = window.pageYOffset, vp = viewportHeight();
    if (now - lastScrollEventAt > SCROLL_IDLE_MS) velocity = 0;
    if (fling && now > fling.endAt + FLING_HINT_GRACE_MS) endFling('timeout');
    else if (fling && velocity !== 0 && (velocity > 0) !== (fling.stop > y)) endFling('overshoot');
    var plan = planWindow({
      slots: slots.map(function (s) {
        // pin() clears a failure, so a pinned slot is retried at once.
        return { height: s.height, state: s.state, pins: s.pins, weight: weightOf(s), retry: now - s.failedAt > RETRY_AFTER_FAILURE_MS, bg: s.bg };
      }),
      y: y, vp: vp, policy: policy, tier: tier, velocity: velocity, direction: direction,
      stop: fling ? fling.stop : null, stopIn: fling ? Math.max(0, fling.endAt - now) : null,
      loadMs: expectedLoadMs, budget: WEIGHT_BUDGET, preparing: !ready
    });
    plan.unmount.forEach(function (i) { unmount(i); });
    plan.mount.forEach(function (i) { mount(i, false); });
    // A window the policy capped (loads at a time) is finished as loads complete, see afterChange.
    scheduleBackgroundMeasurement();
    checkReady();
  }

  function queueWindow() {
    if (windowQueued) return;
    windowQueued = true;
    window.requestAnimationFrame(updateWindow);
  }

  /** Scrolls the outer document on the shell's own account, which is not reader motion (see selfShift). */
  function scrollSelf(target) {
    selfShift += target - window.pageYOffset;
    window.scrollTo(0, target);
  }

  /** Called after a document settled: its neighbours may now be inside or outside the window. */
  function afterChange() { queueWindow(); }

  // ── background measurement ──────────────────────────────────────────────────

  var bgTimer = 0;

  function scheduleBackgroundMeasurement() {
    if (bgTimer || !ready) return;
    bgTimer = window.setTimeout(function () { bgTimer = 0; measureNextInBackground(); }, BG_PAUSE_MS);
  }

  /**
   * Loads the nearest unmeasured slot just to learn its height, a couple at a time (fewer under memory
   * pressure, none at the minimal tier), never mid-scroll or mid-fling.
   */
  function measureNextInBackground() {
    var concurrency = policy === 'static' ? 2 : TIERS[tier].bg;
    if (!concurrency) return; // the next window update schedules it again
    if (Date.now() - lastScrollAt < SCROLL_QUIET_MS || (fling && policy !== 'static')) { scheduleBackgroundMeasurement(); return; }
    var loadingBg = 0, loadingAny = 0;
    slots.forEach(function (s) {
      if (s.state === 'loading') { loadingAny++; if (s.bg) loadingBg++; }
    });
    if (loadingAny - loadingBg > 0 || loadingBg >= concurrency) { scheduleBackgroundMeasurement(); return; }
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
    scrollSelf(top);
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
    scrollSelf(desiredY);
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

  /** Selects the window policy by name; the benchmark compares them on the same book. */
  function setPolicy(name) {
    policy = name === 'static' ? 'static' : 'adaptive';
    queueWindow();
  }

  /** Memory pressure from the native side: 0 normal, 1 reduced, 2 minimal. Applied at once. */
  function setPressure(level) {
    tier = Math.max(0, Math.min(TIERS.length - 1, level | 0));
    queueWindow();
  }

  /**
   * A fling just started (the finger lifted at speed): where the native side predicts it stops, in
   * CSS px of the outer document, its initial velocity and how long it runs. Null when a finger lands,
   * which stops it.
   */
  function flingHint(stop, initialVelocity, durationMs) {
    if (stop === null || stop === undefined || !isFinite(stop)) { endFling('touch'); return; }
    var now = Date.now();
    fling = {
      stop: Math.max(0, stop), velocity: initialVelocity || 0, at: now, endAt: now + (durationMs || 0),
      start: window.pageYOffset, observed: 0
    };
    if (initialVelocity) { velocity = initialVelocity; direction = initialVelocity > 0 ? 1 : -1; lastScrollEventAt = Date.now(); }
    queueWindow();
  }

  var idleTimer = 0;

  window.addEventListener('scroll', function () {
    var now = Date.now(), y = window.pageYOffset, dt = now - lastScrollEventAt;
    var moved = y - lastY - selfShift;
    selfShift = 0;
    if (dt > 0 && dt <= SCROLL_IDLE_MS) {
      var cap = MAX_VP_PER_S * viewportHeight();
      var instant = Math.max(-cap, Math.min(cap, moved / dt * 1000));
      velocity += VELOCITY_SMOOTHING * (instant - velocity);
      if (Math.abs(velocity) > 1) direction = velocity > 0 ? 1 : -1;
    } else if (dt > SCROLL_IDLE_MS) {
      velocity = 0;
    }
    lastY = y;
    lastScrollEventAt = now;
    lastScrollAt = now;
    if (fling && !fling.observed && now - fling.at >= FLING_OBSERVE_MS) correctFling();
    window.clearTimeout(idleTimer);
    idleTimer = window.setTimeout(onScrollIdle, SCROLL_IDLE_MS);
    queueWindow();
    if (telemetry) checkBlank();
  }, { passive: true });

  /** The outer document stopped moving: a fling, if one was running, is over. */
  function onScrollIdle() {
    velocity = 0;
    endFling('rest');
    queueWindow();
  }

  /**
   * Checks the fling's prediction against the velocity it is actually moving at, FLING_OBSERVE_MS in.
   * The native side predicts from the finger, which misses Chromium's fling boosting, among others;
   * on Android's fling curve the distance grows with velocity^FLING_DISTANCE_EXPONENT, so a fling
   * moving at a different speed than hinted is rescaled.
   */
  function correctFling() {
    fling.observed = velocity;
    if (!fling.velocity || !velocity || (velocity > 0) !== (fling.velocity > 0)) return;
    var ratio = velocity / fling.velocity;
    if (ratio > 1 / FLING_TOLERANCE && ratio < FLING_TOLERANCE) return;
    fling.stop = Math.max(0, fling.start + (fling.stop - fling.start) * Math.pow(ratio, FLING_DISTANCE_EXPONENT));
    fling.endAt = fling.at + (fling.endAt - fling.at) * Math.pow(ratio, FLING_DURATION_EXPONENT);
    fling.corrected = true;
    queueWindow();
  }

  /** Forgets the active fling: 'rest', 'touch' (a finger stopped it), 'overshoot' or 'timeout'. */
  function endFling(end) {
    if (fling && telemetry) {
      emit({
        kind: 'telemetry', type: 'fling', t: fling.at, start: fling.start, predicted: fling.stop, actual: window.pageYOffset,
        velocity: fling.velocity, observed: fling.observed, corrected: !!fling.corrected, ms: Date.now() - fling.at, end: end
      });
    }
    fling = null;
  }

  // ── telemetry (benchmark builds only) ───────────────────────────────────────
  //
  // Off unless the native side calls enableTelemetry(), which only benchmark builds do. Reports:
  //
  //   { type: "sample", t, y, live, loading, weight, required, budget, jsHeap, tier, policy, loadMs,
  //     mounts, evictions, wasted, cancelled, loads }
  //       every TELEMETRY_SAMPLE_MS: documents held, their estimated bytes (`required`: of the ones in the
  //       viewport or pinned, which the budget never refuses), the tier's budget, the pressure tier and policy,
  //       the expected load time; counters are cumulative (`mounts`: documents that became live), and
  //       `loads` lists the mount-to-live times (ms) of the documents that became live since the
  //       previous sample
  //   { type: "blank", t, ms, worst }
  //       one episode of the viewport showing empty slot space: when it started, how long it lasted,
  //       and the largest share of the viewport it covered
  //   { type: "fling", t, start, predicted, actual, velocity, observed, corrected, ms, end }
  //       a fling hint's predicted stop (after any correction) against where the document was when the
  //       hint ended: at rest, stopped by a finger, past the stop, or timed out; with the hinted
  //       velocity and the one observed FLING_OBSERVE_MS in
  //
  // A slot without a document is blank. A slot still loading is blank until its document's own
  // load event, and then below the bottom of its frame (a chapter not measured before loads in a
  // small box).

  var TELEMETRY_SAMPLE_MS = 500;
  var telemetry = null;

  function enableTelemetry() {
    if (telemetry) return;
    telemetry = { blankSince: 0, worst: 0, mounts: 0, evictions: 0, wasted: 0, cancelled: 0, loads: [] };
    window.setInterval(sampleTelemetry, TELEMETRY_SAMPLE_MS);
  }

  function telemetryLoaded(s) {
    telemetry.mounts++;
    if (s.mountedAt) telemetry.loads.push(Date.now() - s.mountedAt);
    checkBlank();
  }

  function telemetryUnmounted(s) {
    if (s.state === 'loading') { telemetry.cancelled++; return; }
    telemetry.evictions++;
    // A document the reader asked for (not a background measurement) that leaves without ever
    // having been on screen was loaded for nothing.
    if (!s.bg && !s.seen) telemetry.wasted++;
  }

  /** Opens or closes a blank episode from what the viewport shows right now. */
  function checkBlank() {
    if (!ready) return;
    var y = window.pageYOffset, vp = window.innerHeight || viewportHeight();
    var bottom = y + vp, top = 0, blank = 0;
    for (var i = 0; i < slots.length && top < bottom; i++) {
      var s = slots[i], lo = top, hi = top + s.height;
      top = hi;
      if (hi <= y) continue;
      var overlap = Math.min(hi, bottom) - Math.max(lo, y);
      if (overlap <= 0) continue;
      if (s.state === 'live') { s.seen = true; continue; }
      // A loading document whose own load event fired shows its text from the top of the slot down to
      // the height of its frame (all of it when the chapter was measured before); the rest is empty.
      var shown = s.state === 'loading' && s.docLoaded && s.frame ? parseFloat(s.frame.style.height) || 0 : 0;
      var covered = Math.max(0, Math.min(lo + shown, bottom) - Math.max(lo, y));
      blank += overlap - covered;
    }
    var now = Date.now();
    if (blank > 1) {
      if (!telemetry.blankSince) { telemetry.blankSince = now; telemetry.worst = 0; }
      telemetry.worst = Math.max(telemetry.worst, Math.min(1, blank / vp));
    } else if (telemetry.blankSince) {
      emit({ kind: 'telemetry', type: 'blank', t: telemetry.blankSince, ms: now - telemetry.blankSince, worst: telemetry.worst });
      telemetry.blankSince = 0;
    }
  }

  function sampleTelemetry() {
    var live = 0, loading = 0, weight = 0, required = 0, top = 0;
    var y = window.pageYOffset, vp = viewportHeight();
    slots.forEach(function (s) {
      if (s.state === 'live') live++; else if (s.state === 'loading') loading++;
      if (s.state !== 'placeholder') {
        weight += weightOf(s);
        if (s.pins > 0 || (top + s.height > y && top < y + vp)) required += weightOf(s);
      }
      top += s.height;
    });
    var heap = 0;
    try { heap = performance.memory ? performance.memory.usedJSHeapSize : 0; } catch (e) { }
    // The viewport may be blank without scrolling (an eviction, a document still loading).
    checkBlank();
    emit({
      kind: 'telemetry', type: 'sample', t: Date.now(), y: window.pageYOffset, live: live, loading: loading,
      weight: weight, required: required, budget: WEIGHT_BUDGET * TIERS[tier].budget, jsHeap: heap, tier: tier,
      policy: policy, loadMs: expectedLoadMs, mounts: telemetry.mounts, evictions: telemetry.evictions,
      wasted: telemetry.wasted, cancelled: telemetry.cancelled, loads: telemetry.loads
    });
    telemetry.loads = [];
  }

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
    pressureTier: function () { return tier; },
    enableTelemetry: enableTelemetry,
    setPolicy: setPolicy,
    setPressure: setPressure,
    flingHint: flingHint,
    planWindow: planWindow,
    liveCount: function () {
      return slots.filter(function (s) { return s.state !== 'placeholder'; }).length;
    },
    measuredCount: function () {
      return slots.filter(function (s) { return s.measured; }).length;
    }
  };
})();
