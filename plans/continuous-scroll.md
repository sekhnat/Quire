# Truly continuous vertical scroll across chapters — implementation plan

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

The app's current workaround (`reader/EdgeScrollLayout.kt`) watches the touch
stream; after 48 dp of over-drag at a pinned chapter edge it calls
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

## Approach — vendor the navigator, stack the chapters

Readium's per-chapter machinery (locators, decorations, selection, Readium CSS,
search) is worth keeping: **one spine item per WebView stays**. What changes is
the *geometry*: in scroll mode, instead of a horizontal pager showing one
viewport-sized chapter at a time, the forked navigator hosts a windowed vertical
stack of full-content-height chapter WebViews in a single scroll coordinate
space.

Concretely, we **fork the Readium 3.3.0 EPUB navigator into the app** (BSD-style
license — headers kept, attribution noted) under a new package, and make one
structural change to the fork:

1. **`ContinuousChapterLayout`** (new `ViewGroup`): the scroll-mode container.
   Owns an `OverScroller`; stacks attached chapter views vertically; sizes each
   to its measured content height (no internal WebView scrolling); windowing
   keeps only chapters [current−1 … current+1] attached (same memory profile as
   today's `ViewPager` offscreen pages — a WebView is ~10–30 MB); preloads the
   next chapter when the viewport approaches the stack edge.
2. **`ChapterWebView`** (forked from `R2EpubPageFragment` + `R2WebView`): a
   chapter view that reports its content height once laid out
   (`computeVerticalScrollRange()` after `onPageFinished` + visual-state
   callback, re-measured via a JS `ResizeObserver` → bridge callback so image
   loads / font-size changes / late CSS reflow update the stack geometry
   without moving the text under the user's eyes — the container re-anchors on
   the visible chapter + progression).
3. **Forked `EpubNavigatorFragment`** keeps the identical public surface
   (`go(locator)`, `currentLocator`, `goForward/goBackward`, `SelectableNavigator`,
   `DecorableNavigator`, `submitPreferences`, input/decoration listeners,
   `EpubNavigatorViewModel` settings pipeline) and swaps its container:
   paged mode keeps the vendored `R2ViewPager` path; scroll mode uses
   `ContinuousChapterLayout`. Locator mapping changes only where the pager was
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
   disabled in scroll mode; vertical scroll is native to the stack).

### Why not the alternatives

- *Polish the swap* (no dead zone, velocity carry-over into the swapped
  WebView): cheap, but a one-frame content cut can never be continuous — the
  last screen of chapter N and first screen of N+1 can never coexist on screen.
  Rejected: the user explicitly wants full continuity.
- *Concatenate chapters into one WebView's DOM* ("infinite scroll" style):
  breaks Readium's per-resource locator/decoration/position machinery — every
  href-keyed feature (highlight JS, search-hit `go()`, progress) would need
  custom shims with silent failure modes. Rejected: too fragile.
- *Upstream upgrade*: impossible (3.3.0 pin) and useless (no such feature
  exists upstream).

### Verified starting points (from code exploration)

- Readium 3.3.0 sources are on Maven Central
  (`readium-navigator-3.3.0-sources.jar`); the fork set is ~4.3k lines:
  `EpubNavigatorFragment.kt` (1143), `R2WebView.kt` (1147),
  `R2BasicWebView.kt` (665), `R2EpubPageFragment.kt` (525),
  `EpubNavigatorViewModel.kt` (386), `EpubNavigatorFactory.kt` (86),
  `EpubSettingsResolver.kt` (121), `EpubSettings/Preferences/Defaults/Serializer`
  (~430), `HtmlInjector.kt` (90), `WebViewServer.kt` (281), `R2PagerAdapter.kt`
  (141) + two small layouts (`readium_navigator_viewpager*.xml`) + public
  helpers reused from the AAR as-is (`navigator/epub/css/*`, input listeners,
  `DecorableNavigator` interfaces, `createFragmentFactory` util).
- `R2WebView` in scroll mode already defers to native WebView scrolling
  (`computeScroll() { if (scrollMode) return super }`), and the injected
  `readium-reflowable.js` handles taps/drags/selections/decoration rects via
  DOM events + `getBoundingClientRect` — all layout-based, so they work
  unchanged in full-height, non-internally-scrolling WebViews.
- `EpubNavigatorViewModel.onResourceLoaded(webView, link)` returns per-WebView
  `RunScriptCommand`s (CSS props + that resource's decorations) — lazy chapter
  attachment inherits highlights/search decorations automatically.
- `submitPreferences` emits `InvalidateViewPager` on `scroll` toggle — the
  fork's mode-switch hook (rebuild container, keep current locator).
- App side: `EpubHost.kt` owns fragment creation via
  `EpubNavigatorFactory.createFragmentFactory(...)`; `ReaderSession` types the
  navigator as `EpubNavigatorFragment` and uses only interface-level APIs;
  `ReaderScreen.kt` wires `onEdgeScroll` (to be removed); `ReaderPrefs.ReadMode`
  = `Paged`/`Scroll`, `PrefsMapper` maps `scroll = mode == ReadMode.Scroll`
  (unchanged).
- `EdgeScrollLayout` is the only consumer of `goToAdjacentResource`; both go.
- In scroll mode today the tap zones ("back"/"next") are **dead**
  (`disablePageTurnsWhileScrolling = true` makes `R2BasicWebView.scrollRight`
  a no-op) — a UX gap the new navigator can fill (see Open questions).

## Files to modify

**Add (vendored fork, ~4.3k lines copied from Readium 3.3.0 sources, then
modified where noted):**

- `app/src/main/java/com/quire/reader/navigator/epub/…` — vendored
  `EpubNavigatorFragment` (modified: container swap, locator mapping,
  script scopes), `EpubNavigatorFactory` (modified: instantiate fork),
  `EpubNavigatorViewModel`, `EpubSettingsResolver`, `EpubSettings`,
  `EpubPreferences*`, `EpubDefaults`, `HtmlInjector`, `WebViewServer`,
  copied layouts under a `quire` resource prefix.
- `app/src/main/java/com/quire/reader/navigator/web/…` — vendored
  `R2WebView` (modified: content-height measuring mode for the stack,
  `scrollModeFlow` reuse), `R2BasicWebView` (unchanged), `ChapterWebView`
  (new; forked from `R2EpubPageFragment`, de-fragmented to a plain view the
  navigator fragment owns directly).
- `app/src/main/java/com/quire/reader/navigator/pager/…` — vendored
  `R2PagerAdapter` + `R2FragmentPagerAdapter` + `R2ViewPager` (paged-mode
  path, kept so both modes live in one fragment).
- `app/src/main/java/com/quire/reader/navigator/ContinuousChapterLayout.kt`
  (new — the windowed vertical stack: OverScroller, touch handling, window
  attach/detach, height bookkeeping, scroll→locator derivation).

**Modify:**

- `app/src/main/java/com/quire/reader/reader/EpubHost.kt` — create the forked
  fragment via the vendored factory; drop `continuousScroll`/`onEdgeScroll`
  parameters.
- `app/src/main/java/com/quire/reader/reader/ReaderSession.kt` — retype
  `navigator` to the forked fragment class; delete `goToAdjacentResource`.
- `app/src/main/java/com/quire/reader/ui/reader/ReaderScreen.kt` — drop
  `onEdgeScroll` wiring (and possibly give tap zones viewport-page behaviour,
  per Open questions).
- `app/src/main/java/com/quire/reader/ui/QuireViewModel.kt` — only if the
  end-of-book toast moves (EdgeScrollLayout previously surfaced it).

**Delete:**

- `app/src/main/java/com/quire/reader/reader/EdgeScrollLayout.kt`

**Untouched:** `PrefsMapper.kt` (same `EpubPreferences`), `ReaderFonts.kt`
(served assets keep working — vendored `WebViewServer` honours
`servedAssets = listOf("fonts/.*")`), highlight/search/bookmark flows,
library/data layers, `ReaderLogicTest` (locator logic unchanged).

## Reuse

- Fork sources: Readium 3.3.0 `readium-navigator` sources jar (exact pinned
  version) — copied with original license headers, package renamed
  `org.readium.r2.navigator.*` → `com.quire.reader.navigator.*` (rename
  required: same FQCNs would clash with the AAR classes on the classpath).
- Unchanged public Readium APIs stay library-provided: `EpubPreferences`,
  `epub/css/*` (fonts, ReadiumCss), `DecorableNavigator`, `SelectableNavigator`,
  input listeners, `Locator`/`Publication` services (positions, search).
- App: `ReaderSession` locator consumers, `QuireViewModel` highlight/search
  flows, `ReaderFontList` + asset serving — all keep their current shape.

## Steps

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
  many short chapters, very large chapters (height > WebView max?
  verify 16-bit scroll range is a non-issue at container level), memory
  pressure (detach policy).
- [x] 7. Verification pass (below) + `./gradlew assembleDebug test lint`.

## Verification

Manual, on the `Android_API_36` emulator (books already on `/sdcard/Books`):

- [x] Scroll slowly across a chapter end: the next chapter's first lines appear
  *below* the last lines of the current one — no jump, no dead drag, gesture
  never dies mid-motion.
- [x] Fling fast across several chapter boundaries in one gesture; momentum
  carries through all of them.
- [x] Scroll backwards across a chapter start (same, upwards).
- [x] Left/right tap zones stay inert in scroll mode (and no misleading
  toasts); middle-zone chrome toggle unchanged; paged-mode tap zones unchanged.
- [x] Highlights render in chapters that scrolled into view *after* opening the
  reader (lazy decoration); tapping a highlight still opens the action row;
  creating a highlight mid-scroll works; search hits render + jump-to-hit
  lands exactly.
- [x] TOC jump, progress slider drag, position restore after close/reopen,
  chapter title + % correct near boundaries (progression crosses 1.0 → 0.0
  smoothly).
- [x] Font size / theme / margin change mid-scroll: text under the viewport
  top stays anchored (no jump), stack geometry updates.
- [x] Paged ⇄ Scroll mode toggle mid-book keeps position; paged mode unchanged
  (horizontal swipes, no regressions).
- [x] Orientation change + process death restore in scroll mode.
- [x] `./gradlew assembleDebug test lint` green.

## Decisions

1. **Approach**: fork & stack (approved) — truly continuous scroll, staged delivery.
2. **Tap zones in scroll mode**: stay dead, as today — only the middle zone
   responds (chrome toggle). The forked fragment's `goForward`/`goBackward`
   keep today's scroll-mode behaviour (no-op that returns `true`, so no
   misleading "End of book" toast mid-book). Paged-mode tap zones unchanged.