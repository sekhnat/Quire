# Tasks

- [x] 0. Reproduce on the emulator (`.dbtest` build, throwaway `/sdcard/QuireVerify`): app crash on `/favicon.ico` (SecurityException, no INTERNET permission); with that fixed, renderer killed by `lmkd` at ~990 MB RSS + 1.1 GB swap on `ORV.epub`.
- [x] 1. Publication-only serving in `WebViewServer`; `onRenderProcessGone` handling and one-shot rebuild.
- [x] 2. Shell slots, mount/unmount, window policy, pins, immediate geometry on compensation.
- [x] 3. Runner unload/reload, `withFrame`, readiness on the initial window, selection pins.
- [x] 4. Background measurement, estimate calibration, reflow (`remeasureAll`, stale frames).
- [x] 5. Shell-resolved jumps and reflow anchor (`scrollToOffset`, `viewportPosition`, `captureAnchor`); seam tolerance in `resourceIndexAt`.
- [x] 6. Tests: `ReaderBoundedScrollTest` (bounded window, far fragment holds still, reflow keeps text, seam reporting, unload/reload shows all text, script loads unloaded chapter); existing scroll and target tests unchanged.
- [x] 7. README and this change.
- [ ] 8. Optional: larger mount range while scrolling fast; `ResizeObserver` for late layout changes in live frames.
