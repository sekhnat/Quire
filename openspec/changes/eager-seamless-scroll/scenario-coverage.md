## 6.6 verification mapping — every `specs/seamless-scroll/spec.md` scenario

Commands (both green on the API 36 emulator, `.dbtest` application id):
`zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug test lint'` and
`zsh -fc 'source ~/.config/android/env.zsh; ./gradlew -I tools/dbtest-suffix.init.gradle connectedDebugAndroidTest'`.

| Spec scenario | Exercised by |
|---|---|
| Continuous book-wide scrolling · scroll through adjacent chapters | `ReaderContinuousScrollTest.scrolling across a chapter seam reports the visible resource and progression`; smoke seam screenshot `docs/screenshots/scroll-seam-chapter20-21.png` (Chapter 20's last paragraph flowing into 21, ordinary spacing) |
| · a chapter is shorter than the viewport | `ReaderContinuousScrollTest.a viewport sized image is one reader viewport tall` (frame content == frame box) + fixture chapter c0 = one paragraph; geometry table has no viewport minimums |
| · chapter headings remain navigable content | `ReaderContinuousScrollTest.the whole book is addressable in scroll mode beyond the old three-chapter window` (each chapter's `h2` text readable through its original href); `a toc fragment deep inside a resource is scrolled into view` |
| Eager whole-book preparation · a distant chapter is not yet prepared | `ReaderTargetTest.a slow but healthy scroll startup still resolves its exact target` (a real resource is held past the old 5 s deadline; readiness and the target still resolve, no partial-book ready) |
| · traverse the complete prepared book | 6.2 smoke: CDP-tagged liveness on all 40 frames + `Network.requestWillBeSent` monitoring — start→end→back traversal (`input swipe` drags) lost 0/40 frames and produced **0** network events |
| · delayed image and font affect layout | Same `a slow but healthy scroll startup…` (held response, release, successful final layout); 7.4's `a viewport sized image…` covers viewport-relative lengths after re-measure |
| Honest loading failure and cancellation · a required chapter fails | `ReaderTargetTest.a reader replaced while the screen is not drawing is shown without crashing when drawing resumes` (old session rejected, new session intact) + `a stale target …` (replaced book) + `a slow but healthy scroll startup…` (readiness never claimed early); the surface's `Failed` path is terminal by construction (a frame error event fails the book) but is not reproduced by a fixture here |
| · an optional image is unavailable | `ReaderTargetTest.a book with a broken optional image still becomes ready` — a fixture whose image bytes no decoder accepts still reaches whole-book readiness, with no failure state and no warning |
| · close or replace a loading book | `ReaderTargetTest.a slow but healthy scroll startup…` releases into a new surface; `a reader replaced while the screen is not drawing…`; 7.5's generation checks |
| Chapter/internal-link navigation · jump to a distant chapter | `ReaderContinuousScrollTest.a toc fragment deep inside a resource is scrolled into view`; 6.2 smoke TOC jump to Chapter 30 |
| · two TOC targets share a resource | `ReaderContinuousScrollTest.a toc fragment deep inside a resource is scrolled into view` (shallow `#target-a` and deep `#target-deep` in the same chapter, each landing on its own text) |
| · duplicate IDs in different resources | `ReaderContinuousScrollTest.duplicate ids in different chapters resolve to their own chapter` |
| Compatible original-resource locators · progress crosses a seam | `ReaderContinuousScrollTest.scrolling across a chapter seam…` (reported locator crosses into the next original href, saved position persisted) |
| · restore a saved position from the old reader | 6.2 smoke: paged Chapter 15 → scroll → paged retained Page 561 of 1600 at the same original resource; `ReaderTargetTest.an explicit target replaces the saved position for that opening only` |
| · existing bookmark or highlight is opened | Highlight rendering/activation on the real reader (7.5 fix) — a stored highlight renders coloured and opens its Note/Copy/Remove row; `ReaderTargetTest.opening a snippet target…` verifies saved-target navigation |
| Exact search navigation waits for readiness · exact match in a distant chapter | `ReaderTargetTest.opening a snippet target in continuous scroll mode underlines the exact passage` (fixture's distant chapter, rendered underline boxes + in-viewport check) |
| · slow but successful whole-book startup | `ReaderTargetTest.a slow but healthy scroll startup still resolves its exact target` |
| · missing match after readiness | `ReaderTargetTest.an unresolved target in continuous scroll mode also falls back without an underline`; `a target that cannot be found falls back to its progression with a message and no underline` |
| · a newer jump supersedes an older jump | `ReaderTargetTest.the newest jump and decoration win while the book is still preparing` (two jumps on a preparing surface; landing resource + rendered decoration are the newest) |
| Resource-scoped reading interactions · select text in a later chapter | 6.2 smoke: long-press selection in Chapter 25 showed the app menu, Highlight persisted row `text="last"` that renders; selection origin href fix (7.5b) |
| · same decoration identity in different resources | `ReaderContinuousScrollTest.duplicate ids in different chapters resolve to their own chapter` + per-resource runner resolution (`runnerFor(href)`) |
| · conflicting publisher styles and relative URLs | `ReaderContinuousScrollTest.relative assets and links resolve against the original resource` (relative image + relative link resolve against the resource's own base; fixture c2/c3 use conflicting body colors) |
| Stable reflow · typography or orientation changes mid-book | 6.2 smoke: font size 19→20 mid-Chapter-22 reflowed all 40 frames with the same text kept in view; `a viewport sized image is one reader viewport tall` covers viewport-driven re-measure |
| · touch stops a cross-chapter fling | 6.2 smoke: real `input swipe` drags (which end by touching the surface) stopped cleanly at the book edge; native scroll surface owns fling (no stack-managed restarts remain — the old `OverScroller` engine is deleted) |
| Reading-mode compatibility · switch between scroll and paginated | 6.3 smoke round trip: scroll→Pages kept Chapter 15 / Page 561; `scroll-paged-mode.png`; 7.6/7.7 fixed the two regressions this exposed |
| · open a fixed-layout publication | 6.3 smoke with a generated pre-paginated EPUB: FXL pager used (never the continuous surface), Page 1 of 2 → Page 2 of 2 by tap zone |

Disposable proof harnesses removed: `app/src/debug/` (ScrollHarnessActivity + assets) and
`ContinuousScrollHarnessTest.kt` are deleted; behavior regression coverage that replaced them is
`ReaderContinuousScrollTest` (7 tests), `ReaderTargetTest` (11 tests, including rendered-decoration,
latest-wins and broken-optional-image assertions) plus the recorded emulator smoke. Full suite: 99 tests, 0 failures,
4 pre-existing environment skips (`assumeTrue` all-files-access guards in `IndexWorkerTest`/`IndexDaoTest`).
