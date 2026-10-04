# Design

## Context

See `proposal.md` for motivation and `specs/seamless-scroll/spec.md` for the behavior contract.

The vendored Readium 3.3.0 navigator already owns the relevant flow:

- `navigator/epub/ContinuousChapterLayout.kt` maintains a current-plus-neighbors window (`windowHalf = 1`), estimates unloaded chapter heights from positions, and replaces estimates as chapters load. It synchronizes a virtual column with viewport-sized child WebViews and has its own touch/fling controller.
- `navigator/ChapterWebView.kt` loads one original publication resource, queues scripts, observes height changes, and resolves chapter-local targets.
- `navigator/epub/EpubNavigatorFragment.kt` chooses the scroll stack versus pager, routes navigation and scripts, and reports original-resource locators through the existing positions model.
- `EpubNavigatorViewModel` scopes CSS/decorations by resource but also has a concrete `Scope.WebView` initialization path. `ScriptRunner` already provides the reusable loaded/script-execution contract, including for `pager/R2EpubPageFragment.kt`.
- `WebViewServer` maps original links to served resource URLs; `HtmlInjector` inserts Readium scripts and CSS into each document. The effective publication origin can be `publication.baseUrl` rather than the default `https://readium_package/`.
- `reader/ReaderSession.kt` uses a five-second navigator/page wait and a separate 2.5-second exact-underline wait. The former is unsuitable as a deadline for preparing a complete book.

These observations explain the current architecture, not a reproduced cause of the user's unspecified rendering artifacts. Planning has not changed or runtime-tested the reader. `plans/continuous-scroll.md` describes the earlier windowed approach; this change explicitly supersedes its windowing premise, not its requirement to preserve resource identity. There are no existing main OpenSpec specs to conflict with.

## Goals / Non-Goals

**Goals:**

- Remove the virtual-column/child-scroll synchronization problem by giving Android one native scrollable WebView.
- Preserve document isolation and original resource locators while preparing all reading-order content eagerly.
- Reuse the served-publication pipeline, Readium scripts, positions, preferences, decorations, and session ownership.
- Make resource readiness, geometry, script targeting, and navigation explicit rather than equating a single page load with book readiness.

**Non-Goals:**

- Rewrite publisher HTML into a single concatenated chapter DOM, remove authored chapter headings, or migrate database/locator formats.
- Change pagination, fixed-layout handling, EPUB indexing, library scanning, or preference storage.
- Add cross-document selection, remote-content downloading, automatic retry machinery, telemetry, or a lazy-loading fallback.
- Pre-rasterize the entire book or pre-buffer embedded audio/video. Book-wide document/layout preparation is the requirement.

## Decisions

### 1. One native scroll surface; isolated documents inside it

Add `navigator/epub/ContinuousBookWebView.kt` owning one outer document and one Android WebView. The outer document contains every reading-order resource as a same-origin, full-content-height iframe in publication order. Frames have no borders, margins, reader-added separators, inner scrollbars, or viewport-height minimums; only the outer document scrolls. Reader chrome/insets are applied once. Preserve authored document styling and spacing; disable pagination-specific breaks/columns in scroll context.

Serve the shell through a reserved, collision-checked route on the effective publication origin, not `file:`, `data:`, or the unrelated assets origin. Frames use `WebViewServer`'s original served resource URLs, preserving relative assets, fragment resolution, CSS scope, and duplicate IDs. The reserved route must not appear in the manifest-to-locator mapping. Add `app/src/main/assets/quire/continuous-scroll.html` and `continuous-scroll.js` and explicitly allow the required shell/adapter assets through the existing served-assets configuration in `reader/EpubHost.kt`.

Alternatives rejected:

- **Eager stack of Android chapter WebViews:** simpler adaptation, but retains height/scroll synchronization and allocates a native WebView for every chapter. It does not remove the architectural source of boundary transitions.
- **Concatenated HTML:** one document, but changes stylesheet scope, duplicate-ID resolution, document bases, and Readium's resource-scoped scripts. Reconstructing those contracts is more invasive than retaining documents.
- **Larger preload window:** still lazy, retains estimated heights and mounting transitions, and does not satisfy the request.

This is a selected architecture, not a claim that iframe touch/selection integration already works. The first apply task must prove the identified Android WebView contracts before removing the old surface. A failed probe requires revising the design, not quietly shipping a windowed substitute.

### 2. Eager readiness is a book-wide barrier

The surface has session-owned `Preparing`, `Ready`, `Failed`, and `Disposed` states. Each resource runner becomes loaded only after its document, Readium runtime, current CSS, decoration templates, fonts, and static-image layout have settled. Set image loading to eager for this surface; await image load/error and decoding where available. Broken optional images settle rather than block forever. A required document/runtime failure becomes a book-loading error.

Measure intrinsic content at the actual reader width, excluding reader-added root viewport minimums. Measurement must not use an iframe's already-expanded viewport as an ever-growing content-height estimate. Scroll-specific root sizing and viewport-dependent Readium layout must use the reader viewport, not total book height. Batch height updates and confirm stable geometry after font/image completion before exposing the surface. Cover short chapters, publisher root `height: 100%`/`min-height`, and viewport-sized images in the geometry proof.

Readiness requires all resource runners initialized and the complete geometry table committed. Suppress partial-book locator emission and initial landing until then. Browser paint/raster work can remain viewport-driven; documents and layout inputs cannot be chapter-lazy.

Publish one lifecycle-aware readiness wait from `EpubNavigatorFragment` to `ReaderSession`. In scroll mode replace the five-second attachment/page polling path with a cancellable wait for the active session's readiness or failure, including fragment attachment. Retain the existing short exact-match timeout for a genuine unresolved text search after readiness and retain paginated-mode behavior. Closing or replacing the session destroys the WebView, cancels waits, and rejects old session/layout callbacks. Do not introduce a time-based unresolved-target warning while a valid book is preparing.

### 3. Resource-bound scripts instead of WebView-bound resources

Reuse `ScriptRunner`: the continuous surface exposes one runner per original resource, evaluating inside that frame's `contentWindow`. A frame runner's loaded state represents that resource's initialization; global readiness is the barrier above.

Replace the concrete `RunScriptCommand.Scope.WebView` target with a resource-runner target and change `onResourceLoaded` to receive its runner plus original `Link`. Migrate both the new surface and the paged caller in `R2EpubPageFragment`; do not retain aliases or dual initialization APIs. `CurrentResource` means the resource at the outer viewport's reading position; `LoadedResource(href)` resolves that exact resource; `LoadedResources` addresses every prepared resource. CSS, decoration registration, saved decorations, `evaluateJavascript(script, href)`, and search underline operations retain those scopes.

Add a scroll-only frame adapter through `HtmlInjector` before Readium's script executes. The adapter presents the existing JavaScript-facing callback facade locally and forwards events through a separate `QuireBook` bridge with resource identity, session generation, and layout generation. Audit the callbacks used by `R2BasicWebView`, including taps, links/footnotes, decorations, drag, selection, and viewport dimensions. Keep the paginated/fixed-layout injection path unchanged. Do not set a shared `resourceUrl` to whichever frame loaded last.

Only registered publication resources in the active session can address runners/events; frame hrefs are canonicalized through existing link mapping. The shell and adapter grant no new filesystem/network privileges. External links continue through existing navigation handling rather than replacing the shell inside the WebView.

### 4. One geometry contract for navigation, locations, and coordinates

Store resource start/end positions in outer-document CSS pixels, associated with original hrefs. Frame-local target offsets also use CSS pixels. A target's book coordinate is `resourceTop + localOffset`; native scrolling and interaction rectangles convert to Android coordinates once at the existing WebView/view boundary. Do not mix `devicePixelRatio`, density, frame-relative rectangles, and already-scrolled offsets as the old chapter helpers do.

For an interaction rectangle, combine frame position and its local rectangle, subtract outer scroll, then apply the view's scale/insets once. A selection event carries its originating href even if the top visible resource is different. This keeps selection menus, highlights, notes, and decoration activation attached to the correct document.

Find the active resource from the top content reading coordinate using half-open resource intervals; at a seam the following nonempty resource owns the position. Skip zero-height intervals and clamp the book-end position to the final readable resource. Compute resource-relative progression from committed geometry and feed it into the existing `positionsByResource`/locator emission model. Keep existing position numbering and total-progression semantics rather than persisting a pixel-height percentage of the entire book. The public locator always uses the original href.

TOC/fragment navigation resolves within the specified frame. Search navigation applies its original-resource decoration, obtains the decoration rectangle in that frame, and scrolls the outer surface. Restoration uses the original locator; explicit fragments/text anchors take precedence over progression. Keep one replaceable pending jump, keyed to the latest request and current session/layout generation, so an old readiness or measurement callback cannot override a newer jump.

### 5. Reflow retains a text anchor; native scrolling owns gestures

Before a CSS or viewport change, capture the visible resource's text/element anchor and its viewport-relative offset, with local progression as fallback. Apply preferences to every resource runner, await the new layout generation, update the complete geometry table, and restore that anchor once. Content above the viewport can resize without moving the reader to another chapter. Coalesce geometry notifications; do not correct scroll continuously during an ordinary fling.

Use native WebView drag/fling and touch-to-stop behavior. Inner frames do not own scroll ranges; chapter seams are not scroll edges. Retain the existing whole-book start/end behavior without the old stack's `OverScroller`, child translations, or fling restart during height updates.

### 6. Targeted clean cutover

`EpubNavigatorFragment.resetContainer` chooses the new surface only for non-fixed scroll mode; pager/fixed-layout branches remain intact. Replace `chapterStack`, stack host callbacks, stack jumps, active-runner selection, location reporting, and script dispatch together. Adapt `ReaderSession` readiness and existing `EpubHost` interaction wiring only where required.

After the new path satisfies the real-reader checks, remove `ContinuousChapterLayout.kt`, `ChapterWebView.kt`, and obsolete stack-only fields/helpers/comments. Do not keep a second scroll engine, compatibility switch, or lazy fallback. Audit every caller of the changed script/resource initialization contract; include the paginated caller in verification.

## Risks / Trade-offs

- **Whole-book DOM memory and startup latency** → One native WebView avoids N native WebViews, but all documents remain resident. Measure a large local EPUB on the configured Pixel 7 emulator; report actual preparation time and process/renderer memory. Do not conceal the cost with virtualization. Resource exhaustion is a loading failure, not a partial book.
- **Readium assumptions about top-level documents and native selection** → Prove frame script initialization, relative links, selection handles/menu placement, drag/fling, and per-resource decorations on Android WebView before cutover. A desktop browser alone is not acceptance evidence.
- **Iframe-height feedback or publisher page-layout CSS** → Use intrinsic-content measurement and reader-viewport sizing; test very short resources, root minimum heights, viewport-sized images, delayed fonts, and reflow. Never replace the old estimates with another provisional ready-state height table.
- **Long preparation versus target navigation** → Explicit session readiness separates loading from target resolution and preserves the existing unresolved-target behavior after loading actually completes.
- **Stale callbacks and incorrect resource identity** → Session/layout generations, canonical resource registry, original-href frame runners, and latest-jump ownership apply to readiness, geometry, scripts, selection, and navigation.
- **Data/paginated regressions from shared APIs** → Keep serialized locators and positions unchanged; exercise old saved locators and the paginated/fixed paths after migrating shared callback targeting.

## Migration Plan

1. In the apply workflow, extend the generated EPUB fixtures and prove the concrete same-origin frame, Readium, gesture, and selection contracts on Android WebView using an isolated test application ID.
2. Implement shell serving, frame runners, eager preparation, geometry, and failure/cancellation handling using existing publication serving and session ownership.
3. Switch scroll-mode navigation/interactions to the new surface and lifecycle-aware readiness, migrate the shared paginated script initialization caller, and preserve original locator emission.
4. Exercise all specification scenarios in real-reader instrumentation or explicit emulator smoke scenarios, including a large book and startup exceeding five seconds. Capture seam/selection screenshots and observed navigation/locator results.
5. Remove the obsolete scroll stack and update `README.md` plus `plans/continuous-scroll.md` to describe the new architecture and startup/memory tradeoff.

No data migration or dependency bump is required. If a release must be rolled back, revert the implementation as a unit; original-resource locators remain readable by the previous reader. Do not ship a permanent runtime fallback to the obsolete engine.
