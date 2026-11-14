# The vendored Readium navigator

This package is a copy of the EPUB navigator from the [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit)
(`readium-navigator`, the official sources jar on Maven Central), moved from `org.readium.r2.navigator` to
`com.quire.reader.navigator`. It was copied because the AAR's `EpubNavigatorFragment` is final and scroll mode needed its
own container (cfba78a). The copy keeps Readium's BSD 3-Clause licence (`assets/licenses/readium-LICENSE.txt`), and each
copied file carries a "Vendored from …" note under its original header.

**Baseline: Readium 3.4.0** (copied at 3.3.0, brought to 3.4.0 by porting its changes). The copied files match that
version's source apart from the changes listed below.

The copy is not standalone. The app still depends on the `readium-navigator` AAR **of the same version**, and the copied
code uses it at run time, so the AAR and the copy must always be upgraded together.

## What it takes from the AAR

- **Assets.** `WebViewServer` serves them from the AAR on the assets origin, and `HtmlInjector` injects them into every
  document:
  - `readium/readium-css/` (`epub/css/ReadiumCss.kt` builds the URLs);
  - `readium/scripts/readium-reflowable.js` and `readium-fixed.js`.
- **The JS API those scripts define,** which the copy and Quire's own scripts call: `readium.scrollToLocator`,
  `scrollToId`, `scrollToPosition`, `scrollLeft`/`scrollRight`, `scrollToStart`/`scrollToEnd`, `setProperty`/
  `removeProperty`, `getCurrentSelection`, `getDecorations`, `registerDecorationTemplates`, `findFirstVisibleLocator`.
- **The `Android` bridge the scripts call back.**
  - In paged mode, `R2BasicWebView` is the bridge.
  - In scroll mode, each chapter frame gets a JS facade from `HtmlInjector` (the `scrollFrameHref` block). It forwards to
    `QuireBook` in `ContinuousBookWebView`.
  - The facade must define every `Android.*` method the bundles call: getViewportWidth, log, logError, onTap, onDragStart,
    onDragMove, onDragEnd, onKey, onSelectionStart, onSelectionEnd, onDecorationActivated. Quire adds onLink and onMedia.
  - A method a new bundle calls that the facade lacks fails silently in scroll mode only.
- **Public API types,** imported from the AAR rather than copied:
  - `Navigator`, `VisualNavigator`, `OverflowableNavigator`, `SelectableNavigator`, `DecorableNavigator`,
    `HyperlinkNavigator`, `Decoration`/`DecorationId`/`DecorationChange`, `Selection`, `html.HtmlDecorationTemplates`;
  - `input.*` (`InputListener`, `TapEvent`, `DragEvent`, `KeyEvent`, `Key`, `InputModifier`);
  - `preferences.*`;
  - `util.createFragmentFactory`/`createViewModelFactory`, `extensions.normalizeLocator`.

  A type the AAR removes or changes breaks the build, which is the easy case.

## What Quire changed

`scripts/navigator-upstream-diff.sh local 3.4.0` lists these, with `--full` for the diffs. It ignores the vendored note,
the package and import lines, and blank-line changes.

**Scroll mode: one continuous surface for the whole book.**
- `ContinuousBookWebView` (Quire's own) hosts every chapter in one shell document (`assets/quire/continuous-scroll.*`)
  with a live window of chapters. Commits: cfba78a, 53f245b, 013a8ef, 151368f, c394784, 5e4244e.
- `EpubNavigatorFragment`:
  - builds either the pager or the surface (`resetPager`/`resetBook`, `bookHost`);
  - routes scripts, selection, decorations, locations and key events to whichever is active (`activeScriptRunner`,
    `activeWebView`, `forwardKeyEventFromFrame`);
  - reports `readiness`.
- `WebViewServer` serves the shell at the reserved `/quire/continuous-scroll` route on the publication origin, and checks
  that no book resource has that path.
- `HtmlInjector` injects the frame facade described above, plus the conversion of viewport units (vh/vw…) to pixels
  that the full-height frames need.
- `EpubNavigatorViewModel`: script scopes address a resource by href (`Scope.Resource`), not a `WebView`.
- `ScriptRunner` (Quire's own) is what both `R2EpubPageFragment` and a surface frame implement.
- `CompositeInputListener` is upstream's class from `input/InputListener.kt`, kept without the interface, which comes
  from the AAR.
- Memory pressure (`ScrollMemoryPressure`) and benchmark telemetry (`ScrollTelemetry`, `BuildConfig.SCROLL_TELEMETRY`)
  belong to the surface.

**Preferences in one position-preserving pass (1b794e7).**
- `EpubNavigatorViewModel` emits a single `ApplyPreferences(script, needsInvalidation)` event in place of upstream's
  separate CSS-flow `RunScript` and `InvalidateViewPager`.
- `EpubNavigatorFragment.applyPreferences` captures the position, applies the change once, reflows or invalidates, and
  restores the position.
- `R2EpubPageFragment.awaitVisualStateUpdate` waits for the repaint.

**WebView trust boundary (6848591, 8cb615a).**
- `WebViewServer` refuses any origin but the publication's and the assets' own, and anything that is not https.
- Every WebView gets `hardenForReaderContent()` (`ReaderWebViewSecurity`).
- `R2BasicWebView` documents that its `@JavascriptInterface` methods face untrusted content.
- Upstream's `allowCors` is kept, but accepts a response with no header map, as `WebViewAssetLoader` returns: upstream
  3.4.0 throws on it and crashes the reader on its first asset.

**Search (1754335, 013a8ef).**
- `evaluateJavascript(script, href)` runs a script in a given chapter.
- `scrollToDecoration(group, href)` and `servedUrlFor(href)` let a search result be located, underlined and scrolled to.

**Opening faster (3c0ad67, d694123).**
- `holdsBack`/`awaitNeighbourTurn`: neighbouring chapters wait until the opening page has shown.
- `positionsByResource` is computed once in the fragment, and removed from `extensions/Publication.kt`.
- `ReadiumCss` parses only the head of a document for its `lang` attributes (`langElements`, also fixes `<title/>`
  books: 273aaa9).

**Links, media, fonts and auto-scroll (f3faa8e).**
- `R2BasicWebView` gained the `onLink`/`onMedia` bridge methods, fed by `ContentScripts` (Quire's own).
- `ReaderLinkPolicy` decides where a link goes.
- `ReaderMedia` parses the image/table viewer report.
- `interceptViewerRequest` serves images to the viewer.
- `refreshUserFonts` and `setAutoScroll` are also part of this commit.

**Links in fixed-layout books.**
- `R2FXLLayout` takes every touch before the page sees it, and fixed-layout documents get no `ContentScripts`, so a
  tapped link was never followed (upstream behaves the same).
- `R2FXLPageFragment.onPageTap` now places the tap inside the page's WebView and asks the page for a link there
  (`ContentScripts.linkAtPoint`). A link goes to `onLinkActivated`, the same path as a reflowable link; any other tap
  stays a tap.
- `R2FXLLayout.TapInfo.relativeX`/`relativeY` became readable (`internal`, private setters) for this.
- Swipes in fixed-layout books need no change here: `EpubHost`'s `NavigatorHostLayout` (outside this package) keeps
  Compose from cancelling the drag the pager takes over.

**Read aloud (8451c22).** `selectionSpeechStart` and `SpeechStart` find where speech starts from a selection.

**Fonts from `res/font` (0e15a52).** `WebViewServer` answers the UI fonts' `fonts/…` paths from resources
(`ResourceFontPathHandler`).

**Robustness.**
- Recovery from WebView renderer loss (`onRendererGone`, 151368f).
  - Scroll mode first; paged and fixed-layout pages followed.
  - `R2EpubPageFragment` and `R2FXLPageFragment` implement `onRenderProcessGone` and report with the
    `containerGeneration` they were built in, so a dead page that reports after the pager was rebuilt is ignored.
  - `resetContainer` unwraps the `KeyInterceptorView` that fixed-layout books put around the root.
- A book closed mid-load (68396cd).
- A theme change in scroll mode (e095c27).
- `R2BasicWebView.TapEvent` and `inputModifiers` became `internal` so the surface can parse frame events.

**Taps in scroll mode carry no target element.**
- Since 3.4.0, a paged tap passes the image under it as `TapEvent.targetElement` (`TargetElementData`, built in
  `EpubNavigatorFragment.buildTargetElement`), as upstream.
- The scroll surface's `Host.onTap` passes only the point. Quire opens images with its own long-press viewer
  (`ReaderMedia`) and reads no tap's target element.

**Style rewrites not taken.** 3.4.0 changed `EpubNavigatorViewModel.settings` and `R2EpubPageFragment.isLoaded` to
explicit backing fields (`field =`; upstream compiles with Kotlin language version 2.4). The copy keeps the equivalent
3.3.0 `_settings`/`_isLoaded` form, so the port carries behaviour changes only. This is the only difference in those
lines.

**Build plumbing.**
- Layouts are inflated by id from Quire's own `res/layout/quire_navigator_*` and `R.dimen.quire_navigator_*`, not
  through the AAR's view bindings.
- `R2PagerAdapter` drops CBZ pages; `R2CbzPageFragment` is not copied.
- `app/build.gradle.kts` declares the AAR's runtime-only dependencies (timber, jsoup, webkit, constraintlayout,
  viewpager) for compilation.

## Upgrading Readium

1. **See what Readium changed:** `scripts/navigator-upstream-diff.sh upstream OLD NEW --full`. It lists:
   - Readium's changes to the files Quire copied;
   - new files in those packages;
   - changes to the assets the AAR serves;
   - any `Android.*` method the new bundles call that the scroll frame facade lacks.
2. **Check the requirements:** the new AAR's `minCompileSdk` (in `META-INF/com/android/build/gradle/aar-metadata.properties`)
   and its Kotlin standard library.
3. **Bump the AAR:** `readium` in `gradle/libs.versions.toml`, and `androidx-media3-session` to the version Readium
   builds against.
4. **Port the delta into the copy,** keeping Quire's changes above.
   - Leave out pure style rewrites, such as 3.4.0's move to explicit backing fields (`field =`), and record them under
     "Style rewrites not taken".
   - Bump the declared versions of the AAR's dependencies that the app also declares (webkit, fragment, core,
     constraintlayout, jsoup, coroutines, lifecycle) to at least what the AAR pulls in.
   - Give a new file a "Vendored from" note.
   - Change the bridge and the facade together.
5. **Update the version** in every "Vendored from" note and in the baseline above. Then run
   `scripts/navigator-upstream-diff.sh local NEW`: every file it reports as changed must be described here.
6. **Test on a device.**
   - In paged and scroll mode: taps, links, selection, highlights, the dictionary card, the media viewer, search
     jumps, theme and font changes, and read aloud.
   - Run `ScrollWindowBenchmark` before and after the upgrade.
