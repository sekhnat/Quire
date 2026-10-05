# Design

## Context

See `proposal.md`. This change supersedes the premise of `eager-seamless-scroll` (every document resident) and keeps everything else it established: one native scroll range, same-origin iframes per resource, original-href runners, original-href locators, the reader-viewport adapter in each frame.

Measured on the Pixel 7 emulator (4 GB), `ORV.epub`: eager build killed at ~990 MB renderer RSS plus 1.1 GB swap; this design opens in ~0.3 s and holds 400-520 MB through well over a hundred hard flings. `MWM.epub` (39 chapters) and `TASH.epub` (90) behave as before.

## Decisions

### 1. Slots, not frames

The shell keeps one `div.slot` per resource with an explicit height. A slot holds an iframe only while `loading` or `live`. Heights: measured content height once known, otherwise `positionCount * pxPerPosition`, where `pxPerPosition` is the average over measured slots (initially 0.9 of the reader viewport). Unmounting leaves the slot at its measured height, so the document never shrinks or jumps.

Rejected: a larger preload window (still a function of book size at its edges), and virtualizing in native code with several WebViews (reintroduces the child-scroll synchronization the eager design removed).

### 2. Window policy

Mount slots intersecting `[y - 1.5vp, y + vp + 2.5vp]`; unload live slots wholly outside `[y - 4vp, y + vp + 6vp]` (hysteresis); never more than 8 live frames, farthest unpinned first; pinned slots are never unloaded. Policy runs from a rAF-throttled passive scroll listener and after every settle. Constants live at the top of the script.

### 3. Background measurement

When nothing visible is loading and the reader has not scrolled for 250 ms, load the nearest unmeasured slot (at most two at a time), measure it, recalibrate `pxPerPosition`, rescale the other estimates, and let the window policy unload it again. Mount unmeasured chapters in a 150 px box: a document cannot measure shorter than the frame it is laid out in, so a tall box would pin a short chapter to the estimate. A chapter measured earlier is mounted at its known height so it is visible at once.

### 4. Compensation

`applyHeights` applies a batch of height changes and moves the scroll position by the summed change of every slot ending within 4 px of the viewport top or above it, before the next paint. While the reader has not moved, the next compensation starts from the exact position last requested rather than the device-pixel-snapped read-back, so rounding does not accumulate over hundreds of corrections. Whenever it compensates it publishes the full geometry table immediately; otherwise geometry is batched every 100 ms.

Rejected: native `overflow-anchor`, which does not see iframe swaps; it is explicitly off in the shell.

### 5. The shell is authoritative for positions

The native geometry table trails the shell by up to a batch, and a measurement rescales every estimate above the target. Therefore jumps (`scrollToOffset(href, within)`), decoration scrolls, reflow anchor restores and the reflow anchor capture (`captureAnchor()`, one synchronous evaluation, because the reflow's own style change is queued right behind it) all resolve against the shell's current heights. `scrollToOffset` floors the offset so a target lands at or just below the viewport top; the native reading position is sampled 1 CSS px below the viewport top so such a landing reports its own chapter.

### 6. Pins and `withFrame`

`ContinuousBookWebView.withFrame(href) { runner -> ... }` pins the slot, waits for the runner to load (20 s), runs the block and unpins. Used by jumps, decoration scrolls and `evaluateJavascript(script, href)`. A selection pins its origin resource until it is cleared. `LoadedResources`/`Resource` script scopes still drop commands for unloaded documents: a reloaded document re-runs the per-resource initialization (CSS properties, decoration templates, saved decorations), which is what the paged path relies on too.

### 7. Reflow

A size-affecting change captures the anchor, remeasures live frames, rescales unmeasured slots by the same factor and marks them for re-measurement; live frames are marked stale so their next load starts from the small box (a document cannot measure shorter than its frame, so a size decrease can leave a live frame too tall until then).

### 8. Renderer loss and publication-only serving

`onRenderProcessGone` returns true. First loss: rebuild the surface at the current locator. A second within 30 s: publish `Failed` and show a message pointing to pages mode. `WebViewServer` answers 404 for `/favicon.ico` and for any URL not on the package origin, and looks unlisted package paths up in the container by relative path.

## Risks / Trade-offs

- **Fast flings outrun loading** → blank until the chapters mount (hundreds of ms). Mitigation left for later: a larger mount range while scrolling fast.
- **Estimates drift while measuring** → absorbed by compensation; the reader never sees them (no scrollbar, progress comes from Readium positions).
- **A chapter that never loads** (before ready: terminal error; after: slot stays an empty box and is retried after 30 s).
- **Live-frame count includes background-measurement frames** → the cap is 8 live plus 2 measuring.
