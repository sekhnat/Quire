# Proposal

## Why

Scroll mode (`eager-seamless-scroll`) loads every reading-order document of the book into one WebView before the reader appears and keeps all of them alive. Each document is a full browsing context with its own Readium runtime, style sheets, fonts and decoded images, so renderer memory grows with the size of the book. Large books do not open: on the 4 GB Pixel 7 emulator, `ORV.epub` (553 chapters, 96 MB of images) drove the WebView renderer to about 990 MB resident plus 1.1 GB of swap within seconds, `lmkd` killed it, and because nothing handled `onRenderProcessGone` the app died with it. The only way to read such a book was pages mode.

A second, separate fault hid behind the first: any request for a path that is not in the manifest (the WebView's own `/favicon.ico`, a remote image) fell through to Readium's HTTP client, and the app has no network permission, so the lookup threw on a WebView network thread and killed the app.

## What Changes

- Keep exactly one native WebView and one scroll range, but make each reading-order resource a **slot** whose document is loaded only while it is near the viewport. Slots outside the live window are empty boxes of their measured height, or of an estimate (resource position count times a calibrated px-per-position) until measured.
- Measure the unloaded slots in the background, a couple at a time and never while the reader is scrolling, so estimates converge on exact heights. Changes above the viewport are compensated with a scroll adjustment in the same task, so the text being read does not move. Native scroll anchoring is switched off in the shell: it does not follow documents swapped in and out of slots.
- Readiness means the documents around the starting position are live and measured, not the whole book. The geometry table still covers the whole book.
- Anything that must address a resource that may be unloaded (a jump, a search underline, a script by href, a text selection) pins it first and the shell loads it.
- Handle `onRenderProcessGone`: rebuild the surface once at the current position, and tell the reader if it is lost twice within 30 seconds, instead of letting the app die.
- Serve only publication resources: a path on the package origin that is not in the manifest is looked up in the container by path, and anything else answers 404. No request reaches the HTTP client.
- Fix three position-reporting faults found on the way: jump targets and reflow anchors are resolved by the shell from its own current heights (the native table trails them by a geometry batch); the reflow anchor is the first block at the *reader* viewport top (it used the frame's own viewport, which is the whole chapter); a position within a pixel above a chapter seam reports that chapter.

Paged and fixed-layout reading, the reading-mode preference, locator formats and database schemas are unchanged.

## Capabilities

### Modified Capabilities

- `seamless-scroll`: whole-book eager preparation becomes a bounded live window with whole-book geometry; readiness, failure and interaction requirements are adjusted to match; renderer-loss recovery and publication-only serving are added.

## Impact

- `assets/quire/continuous-scroll.{html,js}`: slots, window policy, background measurement, compensation, pins, `scrollToOffset`, `viewportPosition`, `captureAnchor`.
- `navigator/epub/ContinuousBookWebView.kt`: runner unload/reload, `withFrame`, readiness on the initial window, renderer-gone, shell-resolved jumps and anchors. `EpubNavigatorFragment.kt`: position counts, renderer-lost handling, `evaluateJavascript(script, href)` through `withFrame`. `WebViewServer.kt`: publication-only serving.
- Tests: new `ReaderBoundedScrollTest` and a generated long-book fixture; the existing scroll and target tests pass unchanged.
- README: scroll-mode description. No dependency, SDK or schema change.
- Accepted trade-offs: a fast fling can briefly outrun loading (blank until the chapters mount); an unmeasured chapter's height is an estimate until the background pass reaches it (about 90 s for 553 chapters on the emulator); the saved position is still only as precise as a Readium position.
