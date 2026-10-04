# Truly continuous vertical scroll across chapters — implementation plan

> **Status: superseded (2026).** The windowed chapter-stack design in this document
> was **replaced** by the eager whole-book continuous surface
> (`openspec/changes/eager-seamless-scroll`): one native WebView whose shell document
> holds every reading-order resource as a same-origin, full-content-height iframe,
> prepared eagerly before readiness. `ContinuousChapterLayout.kt`, `ChapterWebView.kt`
> and the windowed stack's gesture controller (`DragTracker.kt`) are gone. This file is
> kept as the historical record of the windowed approach and of the requirement it
> established — resource identity and locators must survive the scroll surface; the
> sections below describe the old engine and are **not** the current architecture.
> See `README.md` ("Scroll mode") for the current description.

## Context

In scroll mode, reaching the end of a chapter breaks the reading flow: there is a
dead "rubber wall" (the reader must drag ~48 dp past the pinned chapter edge),
then a hard cut to the next chapter, and the scroll gesture dies at that moment
(fling momentum is lost; the finger must be lifted and re-placed). The user
wants **completely continuous** scrolling — chapter changes should be
invisible, the way Google Play Books / Moon+ Reader behave: the next chapter's
text is already on screen directly below the current chapter's last line.

### Root cause (verified in Readium 3.3.0 sources)

The reader embeds Readium's `EpubNavigatorFragment`. Its architecture
(`readium-navigator-3.3.0-sources.jar`, Maven Central):

- One `R2WebView` (custom WebView) **per chapter** (`R2EpubPageFragment`), hosted
  as pages of a horizontal `R2ViewPager` (`EpubNavigatorFragment.kt:430`,
  `pager/R2PagerAdapter.kt`).
- In scroll mode each chapter WebView is viewport-sized and scrolls *internally*;
  chapter changes are **pager page-turns** — the current chapter view is swapped
  for the next one (`resourcePager.currentItem = index`, `EpubNavigatorFragment.go()`).
- Readium has **no continuous-scroll support and no plan to add it**
  (kotlin-toolkit #563 — maintainer: "Continuous scroll is quite tricky to
  implement with WebViews… not our short-term priority"). Upgrading Readium is
  not possible anyway (pinned 3.3.0; 3.4.0 needs compileSdk 37).

The app's original workaround (`reader/EdgeScrollLayout.kt`) watched the touch
stream; after 48 dp of over-drag at a pinned chapter edge it called
`ReaderSession.goToAdjacentResource()` → `navigator.go()`, which performs the
pager swap. This is inherently a discontinuity:

- the 48 dp drag does nothing (dead zone before the swap),
- the swap replaces the screen content in one frame (end of chapter N vanishes,
  top of chapter N+1 appears — never both visible),
- the in-progress touch gesture belongs to the now-detached WebView, so it dies
  mid-drag and fling velocity is discarded.

### Intended outcome

Scrolling reads as one continuous column of text for the whole book: chapter
boundaries carry no gesture break, no jump, no visible cut. All existing reader
features (highlights, search, TOC jumps, progress restore, fonts, themes, paged
mode) keep working.

## Approach (historical, superseded) — vendor the navigator, stack the chapters

Readium's per-chapter machinery (locators, decorations, selection, Readium CSS,
search) is worth keeping: **one spine item per WebView stays**. What changed was
the *geometry*: in scroll mode, instead of a horizontal pager showing one
viewport-sized chapter at a time, the forked navigator hosted a windowed vertical
stack of full-content-height chapter WebViews in a single scroll coordinate
space.

Concretely, we **forked the Readium 3.3.0 EPUB navigator into the app** (BSD-style
license — headers kept, attribution noted) under a new package, and made one
structural change to the fork:

1. **`ContinuousChapterLayout`** (new `ViewGroup`): the scroll-mode container.
   Owned an `OverScroller`; stacked attached chapter views vertically; sized each
   to its measured content height (no internal WebView scrolling); windowing
   kept only chapters [current−1 … current+1] attached. This window plus
   estimated heights for unloaded chapters is exactly what the eager surface
   replaced.
2. **`ChapterWebView`** (forked from `R2EpubPageFragment` + `R2WebView`): a
   chapter view that reported its content height once laid out
   (`computeVerticalScrollRange()` after `onPageFinished` + visual-state
   callback, re-measured via a JS `ResizeObserver` → bridge callback so image
   loads / font-size changes / late CSS reflow update the stack geometry
   without moving the text under the user's eyes — the container re-anchored on
   the visible chapter + progression).
3. **Forked `EpubNavigatorFragment`** kept the identical public surface
   (`go(locator)`, `currentLocator`, `goForward/goBackward`, `SelectableNavigator`,
   `DecorableNavigator`, `submitPreferences`, input/decoration listeners,
   `EpubNavigatorViewModel` settings pipeline) and swapped its container:
   paged mode keeps the vendored `R2ViewPager` path; scroll mode used
   `ContinuousChapterLayout`. Locator mapping changed only where the pager was
   touched:
   - `go(locator)` → resolve chapter index by href → ensure attached →
     `smoothScrollTo(chapterTop + progression × chapterHeight)`;
   - `notifyCurrentLocation()` → from viewport y: chapter = the view under the
     viewport top, progression = (y − chapterTop) / chapterHeight → same
     `Locator` shape as today (href, progression, position via
     `positionsByResource`), so `ReaderSession`'s progress, chapter title,
     minutes-left and restore logic are untouched;
   - `RunScriptCommand` scopes route over *all attached* chapter views
     (`LoadedResources`, `LoadedResource(href)`), so CSS/font updates,
     decorations (highlights, search hits) and `clearSelection` work per
     chapter exactly as the ViewModel already produces them
     (`EpubNavigatorViewModel.onResourceLoaded` re-applies decorations when a
     chapter loads — lazy attachment gets highlights for free).
4. **Delete the workaround**: `EdgeScrollLayout`, `goToAdjacentResource`,
   `EpubHost`'s `continuousScroll`/`onEdgeScroll` parameters.
   `disablePageTurnsWhileScrolling` stays (horizontal swipe page-turns stay
   disabled in scroll mode; vertical scroll is native to the surface).

### Why not the alternatives

- *Polish the swap* (no dead zone, velocity carry-over into the swapped
  WebView): cheap, but a one-frame content cut can never be continuous — the
  last screen of chapter N and first screen of N+1 can never coexist on screen.
  Rejected: the user explicitly wants full continuity.
- *Concatenate chapters into one WebView's DOM* ("infinite scroll" style):
  breaks Readium's per-resource locator/decoration/position machinery — every
  href-keyed feature (highlight JS, search-hit `go()`, progress) would need
  custom shims with silent failure modes. Rejected here as a **HTML** concatenation; the
  eager surface keeps one document per resource inside iframes, so nothing is lost —
  see the current design.
- *Upstream upgrade*: impossible (3.3.0 pin) and useless (no such feature
  exists upstream).

## Files (historical)

**Added:** the vendored fork under `app/src/main/java/com/quire/reader/navigator/`
(`EpubNavigatorFragment`, `EpubNavigatorFactory`, `EpubNavigatorViewModel`,
settings/preferences, `HtmlInjector`, `WebViewServer`, pager classes,
`R2WebView`/`R2BasicWebView`), `ChapterWebView`, `ContinuousChapterLayout`.

**Deleted:** `reader/EdgeScrollLayout.kt` (the original edge-swap workaround).

**Later deleted by the eager-surface cutover:** `ContinuousChapterLayout.kt`,
`ChapterWebView.kt`, `DragTracker.kt`, plus the stack-only fields, jump helpers and
callers in `EpubNavigatorFragment` (see `openspec/changes/eager-seamless-scroll`
tasks 6.4).

## Steps (historical, all completed for the windowed engine)

- [x] 1. Vendor the Readium 3.3.0 navigator sources into
  `com.quire.reader.navigator` (mechanical package rename + layout/resource
  prefix), keep behavior byte-identical to stock (still pager-based), wire
  `EpubHost` to the fork, delete nothing else yet. Verify: reader behaves
  exactly as today (both modes, highlights, search).
- [x] 2. Build `ChapterWebView` (fork of `R2EpubPageFragment` as a plain view)
  + content-height measuring in the vendored `R2WebView` variant
  (`ResizeObserver` bridge → height callback).
- [x] 3. Build `ContinuousChapterLayout`: OverScroller-driven vertical
  scrolling, stack layout, ±1 windowing with preload, re-anchoring on
  height changes.
- [x] 4. Wire scroll mode in the forked fragment: container swap on
  `InvalidateViewPager`, `go()`/`notifyCurrentLocation()` locator mapping,
  `RunScriptCommand` scopes, `goForward`/`goBackward` stay inert in scroll
  mode, end-of-book edges.
- [x] 5. Remove the workaround: `EdgeScrollLayout`, `goToAdjacentResource`,
  `EpubHost`/`ReaderScreen` edge parameters; update `ReaderSession` navigator
  typing; add attribution note for the vendored code.
- [x] 6. Polish + hardening: height-change anchoring under font-size/theme
  changes mid-scroll, orientation change, process restore, fast flings across
  many short chapters, very large chapters, memory pressure (detach policy).
- [x] 7. Verification pass: `./gradlew assembleDebug test lint`.

## Verification (historical)

Manual, on the `Android_API_36` emulator:

- [x] Scroll slowly across a chapter end; fling across several boundaries; scroll back.
- [x] Left/right tap zones inert in scroll mode at the time; paged-mode zones unchanged.
- [x] Highlights, search hits, TOC jump, progress slider, restore, chapter title/%.
- [x] Font size / theme / margin change mid-scroll; paged ⇄ scroll toggle; orientation.
- [x] `./gradlew assembleDebug test lint` green.

For the current architecture's verification — instrumentation plus the recorded
emulator smoke (readiness timing, memory, seam/selection screenshots, traversal
without reloads) — see `openspec/changes/eager-seamless-scroll/tasks.md` and
`docs/screenshots/scroll-*.png`.

## Decisions

1. **Approach**: fork & stack (approved) — continuous scroll, staged delivery. Later
   replaced by the eager whole-book iframe surface, which keeps the same requirement
   (original-resource locators and identity) without windowing, estimated heights,
   mount/unmount transitions or a custom fling controller.
2. **Tap zones in scroll mode**: the windowed engine kept them dead (middle zone only).
   The eager surface **restores** them as one-reader-viewport steps within the prepared
   book (`ContinuousBookWebView.pageForward()`/`pageBackward()`), so the app's
   left/right zones work in scroll mode and paged-mode zones are unchanged.
