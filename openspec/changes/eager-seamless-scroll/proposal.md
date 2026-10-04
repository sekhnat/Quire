# Proposal

## Why

Scroll mode still treats an EPUB as a moving window of chapter WebViews, leaving resource boundaries exposed through estimated heights, chapter loading, and synchronized scrolling. Readers need one fully prepared book that scrolls continuously without boundary-related rendering changes, while retaining chapter navigation.

## What Changes

- Replace scroll mode's windowed chapter layout with one continuous book-wide scroll surface containing every reading-order document before reading begins.
- Remove renderer-imposed chapter gaps, viewport-sized chapter minimums, per-chapter pagination breaks, and mount/unmount transitions. Preserve authored chapter headings, content, styling, and chapter targets; “no chapter boundaries” does not mean deleting book content.
- Prepare chapter text, styles, fonts, and static-image layout eagerly rather than loading chapters as the reader approaches them. Normal browser rasterization of offscreen content is not prohibited.
- Keep TOC, internal links, search-result jumps, bookmarks, highlights, reading progress, and restored positions keyed to the EPUB's original resource hrefs and local locations.
- Make initial readiness book-wide and cancellation-aware; do not expose partial chapters as a ready reader or silently fall back to lazy loading when preparation fails.
- Preserve paginated and fixed-layout reading behavior and the existing reading-mode preference.

## Capabilities

### New Capabilities

- `seamless-scroll`: Fully prepared, continuous EPUB scrolling, stable chapter/locator navigation, and compatibility with existing reading interactions and saved locations.

### Modified Capabilities

None. The project currently has no main OpenSpec capability specifications; this documents new requirements for an existing reader flow.

## Impact

- Replace `app/src/main/java/com/quire/reader/navigator/epub/ContinuousChapterLayout.kt` and scroll-only `navigator/ChapterWebView.kt`; adapt the vendored `EpubNavigatorFragment`, `EpubNavigatorViewModel`, script routing, and resource callbacks.
- Reuse `WebViewServer`, `HtmlInjector`, original publication URLs, and Readium's per-document scripts. Add a book shell and resource-aware frame bridge rather than concatenate publisher HTML.
- Integrate whole-book readiness with `ReaderSession` navigation; keep persisted Readium locator formats and database schemas unchanged.
- Extend real-reader instrumentation around continuous seams, eager readiness, resource identity, target navigation, selection, and lifecycle cancellation. Update `README.md` and the historical continuous-scroll plan to describe the cutover.
- Keep Readium 3.3.0, Coil 3.5.0, and the current SDK/toolchain. No dependency upgrade or library-scanning change is needed.
- Accept longer initial loading and book-sized DOM memory in exchange for eliminating chapter-load work during scrolling. The proposed frame-based design requires Android WebView touch/selection verification before cutover; it is not an already-verified implementation.
