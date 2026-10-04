# Stop the reader's scroll the instant a finger lands

## Context

Bug: in the reader's continuous (scroll) mode, once a fling is under way the user
cannot stop it. Tapping, pressing and holding, or grabbing the text mid-flight
has no effect — momentum runs to completion. ("Unable to stop mid scroll or mid
scroll (fling) in the reader.")

**Root cause** — `ContinuousChapterLayout.kt` (the scroll-mode container) owns
the whole gesture. Fling momentum lives in the container's `OverScroller`,
advanced frame-by-frame in `computeScroll()` → `syncTo(scroller.currY)`, and
nothing ever stops that animation when a new touch arrives:

- `onInterceptTouchEvent(ACTION_DOWN)` records the new gesture (pointer id,
  down coords, fresh `VelocityTracker`) but never calls
  `scroller.abortAnimation()`.
- `onTouchEvent(ACTION_DOWN)` returns `false`.

Consequences (all observed by the user):

- **Tap mid-fling** — the DOWN lands on the chapter WebView (chrome toggle via
  the JS tap bridge still fires) while the container keeps animating behind it.
- **Press-and-hold mid-fling** — the column keeps sliding under a still finger.
- **Drag started mid-fling** — the container does intercept at touch-slop and
  adopts the chapter's `webView.scrollY`, but `computeScroll()` keeps calling
  `syncTo(scroller.currY)` every animation frame, overriding the drag. The
  scroll cannot be grabbed mid-momentum until the fling dies on its own.

Every stock Android scroll container (ScrollView, RecyclerView, WebView) stops
its scroller on `ACTION_DOWN`. The stack is missing exactly that.

## Approach

Adopt the standard scroll-container contract in `ContinuousChapterLayout`:
**the fling dies the moment a finger touches the stack.**

1. Add a private `stopFling()` helper: `if (!scroller.isFinished)
   scroller.abortAnimation()`. `y` is already in sync with the scroller's last
   applied frame (computeScroll applies `currY` immediately on the UI thread),
   so aborting freezes the column exactly where it is — no jump, no residual
   motion, no extra invalidation needed.
2. Call `stopFling()` at the top of the `ACTION_DOWN` branch of
   `onInterceptTouchEvent`. This single choke point covers every landing spot:
   `ViewGroup.dispatchTouchEvent` resets the disallow-intercept flag and
   consults `onInterceptTouchEvent` on **every** `ACTION_DOWN` — whether the
   down lands on a chapter WebView (the normal case) or on bare container
   (window churn while chapters attach/detach mid-flight).
3. Harden `onTouchEvent`'s `ACTION_DOWN` (currently `return false`): mirror
   the intercept path's state init — `stopFling()`, pointer id, down coords,
   `isDragging = false`, fresh `VelocityTracker` — and return `true`, so a
   gesture no child consumed is owned by the container and its UP/CANCEL
   cleanup arrives. Also recycle the tracker on the `!isDragging` early-return
   in the UP branch (dead today, becomes reachable once DOWN returns true).

Deliberately unchanged:

- A tap mid-fling still toggles chrome / fires zone actions exactly as today —
  it now also freezes the page under it.
- The `chapterMeasured` re-anchor re-fling (velocity > 800 continuation) rides
  the same `OverScroller`, so those become stoppable too — same UX, no extra
  code.
- Sub-slop flicks that fling natively *inside* a chapter WebView: Chromium
  already stops its own momentum on tap, and the container re-adopts
  `webView.scrollY` at the next interception. Untouched.
- Paged mode: Readium's deliberate catch-and-release on tap during a page
  settle (`R2WebView.mHasAbortedScroller`) is stock behavior. Untouched.
- Edge glows: released on UP/CANCEL as today.

## Files to modify

- `app/src/main/java/com/quire/reader/navigator/epub/ContinuousChapterLayout.kt`
  — the only file (~15 net lines).

## Reuse

- The existing `scroller`, `velocityTracker`, `drag` (`DragTracker`) and
  `dragBy`/`flingScroll` machinery — the fix only adds the missing abort at
  gesture start; no new state.
- `jumpToY()` already contains the `scroller.abortAnimation()` pattern to
  model the helper on.

## Steps

- [x] 1. Add `stopFling()` next to `flingScroll()`; call it first thing in the
      `ACTION_DOWN` branch of `onInterceptTouchEvent`.
- [x] 2. `onTouchEvent`: handle `ACTION_DOWN` (stopFling + state init +
      return `true`); make the UP/CANCEL `!isDragging` early-return recycle the
      velocity tracker before returning.
- [x] 3. `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug
      test lint'` — green.
- [ ] 4. Manual verification on the emulator (below).

## Verification

Manual, on the `Android_API_36` emulator (books on `/sdcard/Books`), scroll
mode:

- [ ] Fling hard, tap mid-flight → stops dead under the finger; a second tap
      toggles chrome as usual.
- [ ] Fling, press-and-hold → stops and stays put; lifting without moving does
      not resume the fling.
- [ ] Fling, then drag while still moving → the drag takes over smoothly from
      the stop point (no jump, no fighting); release re-flings with the new
      gesture's velocity.
- [ ] Fling across a not-yet-measured chapter (re-anchor re-fling fires) → a
      tap stops that too.
- [ ] Tap mid-fling still toggles chrome; side tap zones stay inert (no
      toasts); long-press text selection still works and is not hijacked by
      the container (the `isSelecting` path is unchanged).
- [ ] Paged mode unchanged: tap zones, horizontal swipes, settle animation.
- [ ] `./gradlew assembleDebug test lint` green (`DragTrackerTest` and the
      rest unaffected).

Unit-test note: touch dispatch + `OverScroller` behavior is View-level; the
project has no Robolectric setup, so verification is manual — same approach as
the continuous-scroll plan's verification pass.