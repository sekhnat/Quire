# Plan: finish library text search — scale run, docs, polish, housekeeping

Status: EXECUTED. Results and measurements: `docs/superpowers/specs/2026-10-04-library-text-index-scale-results.md`.

## Context

The library text search feature (FTS4 index, background indexer, grouped search UI, reader
navigation) is built and verified through Phase 6 on `feature/library-text-index` (tip `5d59371`,
pushed). What is left, per `balance_plan.md`: the 1,500-book scale verification, README +
screenshot, two optional polish items, disposition of the "verified-nowhere" list, and housekeeping.
This plan covers balance items 1–4 and 6.

## Decisions made this session

- **Fallback extractor: deferred.** This session = scale run + docs + polish + housekeeping. The
  README documents the current Readium-extraction limit; `PLAN_FALLBACK_EXTRACTOR.md` stays PLAN
  ONLY for a later session.
- **Scale run: clean re-run from zero.** The 706/1500 partial index from the stopped attempt is
  deleted first so first-index duration and batch count are really measured (the stopped attempt's
  monitor log serves only as a cross-check).
- **Polish: both items land before the timed run** (cssSelector drop + multi-term excerpt window),
  so the run measures final code and final stored size.
- **Verified-nowhere: close the cheap ones, log the rest.** `rebuild()`/`deleteIndex()` e2e through
  WorkManager and the `PausedForReader` label are exercised during the scale session; everything
  else goes into a Known-unverified section of the report.

## Findings (corrections to the balance plan)

- **The fixture and its generator already exist.** "No generator exists anywhere" is stale: the
  stopped attempt left `genfixture.py` (throwaway, not committed) and a 1,500-book Calibre-style
  fixture (1,054 real EPUBs copied from `~/Calibre Library` + 441 derived slice-EPUBs) in the
  session scratchpad (`/tmp/claude-1000/-home-caan9-Projects-AndroidApps-Quire/80ff28f9-…/scratchpad/scale/`).
  All **1,500 EPUBs are already on the scale emulator** (`emulator-5556`, AVD `quire_scale`,
  `/sdcard/Calibre Library`). ⚠️ The scratchpad is tmpfs — gone on reboot; step 0 preserves what matters.
- **The stopped attempt froze at 706/1500** (~45 min in, 1 failed, ~625k chunks, db 2.79 GB, PSS
  ~210 MB); the DB has not changed since. No query timings or report were produced.
- **`quire_scale` is still running** with `com.quire.reader.dbtest` (+test) installed. The user's
  `emulator-5554` holds only the real `com.quire.reader`.
- **Settings status-line fix already landed** (balance item 3.1): `TextSearchPresentation.kt:54`
  `inSettings` param, `SettingsScreen.kt:105` passes it, test
  "the Settings status does not send the user to Settings…" covers it. Closed — no work.
- **`main` was NOT fast-forwarded** (balance plan claims it was): `main` = `cfba78a` (pre-feature),
  `feature/library-text-index` = `5d59371`. Still to do, after this session's commits.
- **Probe/agent worktrees live in the OLD clone**, `~/Projects/AndroidApps/Quire/.claude/worktrees/`
  (823 MB): `phase0-probe`, two `worktree-agent-*`, and a `feature-library-text-index` worktree at
  the same tip. The current repo has none of them. `.gitignore` does not list `.claude/`.
- **`ScaleBench.kt` exists and is committed** (`app/src/androidTest/…/scale/ScaleBench.kt`,
  self-labelled "deleted after the scale run"): instrumented harness timing `TextSearcher` against
  the real `quire.db` with args q/runs/filters/page/cap/probe/prefixdocs, logging first-run and
  warm-median ms. It is the query-timing tool.
- Scratchpad also holds `monitor.sh` (db/wal/counts/PSS sampler), `fling.sh` (library fling for
  gfxinfo), `integrity.sh`, `q.sh` (sqlite via run-as), the `a` adb wrapper, and
  `p6shots/text_pemberley_results.png` — **the README screenshot candidate still exists**.
- Tuning constants: `IndexRules.kt` `MAX_COUNTED_PASSAGES = 5000` (l.18) and
  `MAX_PREFIX_DOCUMENTS = 200_000` (l.45); `TextSearcher.kt:175` `BROAD_FILTER_PROBE_BOOKS = 8`.
- `cssSelector` is written only by `LibraryIndexer.slimLocator` (l.307-313) into the stored mapping
  JSON; **nothing reads it at query time** (chapter labels are baked into `text_chunk.chapter`;
  navigation resolves by quoted text; `fullLocatorJson()` splices raw JSON). Index-time labeling
  (`markFor`, `isHeadingSelector`) uses the live locator and is unaffected by dropping the stored
  copy. Old blobs keep the field harmlessly.
- Multi-term excerpt: `buildExcerpt` in `Excerpt.kt` windows around the *first* hit
  (EXCERPT_BEFORE=70 / EXCERPT_AFTER=110 chars), so hits of other query terms can fall outside.
- Fallback extractor: **nothing implemented** (no `unreadableResources` anywhere); consistent with
  the deferral decision.
- Disk: `/home` 52 GB free; emulator `/sdcard` 11 GB free (fixture + 2.79 GB db already there).

## Files to modify

- `app/src/main/java/com/quire/reader/data/index/LibraryIndexer.kt` — `slimLocator` drops the
  `cssSelector` parameter/`otherLocations`.
- `app/src/main/java/com/quire/reader/data/index/Excerpt.kt` — window extends toward the nearest
  other-term hit (bounded).
- `app/src/test/java/com/quire/reader/data/index/ExcerptTest.kt` — cover the widened window.
- `app/src/androidTest/java/com/quire/reader/data/index/LibraryIndexerTest.kt` — assert stored
  locator JSON has no cssSelector (method names must not contain commas).
- `app/src/androidTest/java/com/quire/reader/scale/ScaleBench.kt` — **deleted** after the run
  (source preserved in the report appendix).
- `README.md` — Features entry + limits, architecture tree `data/index/`, vendored-navigator note,
  Screenshots row.
- `docs/screenshots/search-text.png` — NEW, copied from scratchpad
  `p6shots/text_pemberley_results.png`.
- `docs/superpowers/specs/2026-10-04-library-text-index-scale-results.md` — NEW report.
- `.gitignore` — add `.claude/`.
- `balance_plan.md` — removed once its items are done (the report supersedes it).
- Git: commits on `feature/library-text-index`; fast-forward `main` and push both.

## Reuse

- `ScaleBench.kt` for every query timing (already parameterised for cap/probe/prefixdocs).
- Scratchpad `monitor.sh` / `fling.sh` / `integrity.sh` / `q.sh` / `a` for run monitoring; inline
  them (and `genfixture.py`, `ScaleBench.kt`) into the report appendix so they survive /tmp.
- `tools/dbtest-suffix.init.gradle` for every instrumented run (never plain
  `connectedDebugAndroidTest`).
- Settings UI paths — "Rebuild index" and "Turn off and delete index" — as the real e2e levers.

## Steps

- [x] **0. Preserve the volatile scratchpad.** Copy `p6shots/text_pemberley_results.png` into
  `docs/screenshots/search-text.png`. (The report doc in step 7 inlines the scripts as appendices.)
- [x] **1. Polish (before the timed run).** (a) `slimLocator`: store only href, mediaType,
  progression — no `otherLocations`; keep index-time css use for labeling. (b) `buildExcerpt`:
  after cutting around the first hit, if the nearest hit from another term lies outside the
  window, extend the window toward it with the same padding, bounded by a named cap (≈ 2× current
  window) so excerpts stay short. Tests for both. Green:
  `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug test lint'`.
- [x] **2. Install.** `ANDROID_SERIAL=emulator-5556 ./gradlew -I tools/dbtest-suffix.init.gradle
  installDebug`; `adb -s emulator-5556 shell appops set com.quire.reader.dbtest
  MANAGE_EXTERNAL_STORAGE allow`; confirm "Index only while charging" is off.
- [x] **3. Zero the index through the real UI path.** Start `monitor.sh`; Settings → "Turn off and
  delete index" → confirm; time it (also proves the delete e2e path at 706-book scale). Turn
  "Index book text" back on → the clean first-index pass starts at t=0.
- [x] **4. Monitored first-index run (~2–3 h; no host builds during timed stretches).** monitor.sh
  samples db/wal bytes, done/failed/skipped/chunks, PSS every 15–20 s; logcat `IndexWorker` tag
  captured to count 5-minute batches. Mid-run: library fling (`fling.sh`) + `dumpsys gfxinfo
  com.quire.reader.dbtest` while indexing; open a book → verify "Indexing is paused while you
  read." is actually visible (uiautomator/screenshot) → reader fps → close → resume; kill
  (`am force-stop com.quire.reader.dbtest`) + relaunch mid-queue → confirm resume without redoing
  finished books. Record first-index duration, batch count, growth curve, peak memory.
- [x] **5. Query + storage measurements on the full index.** Totals (searchable/failed/skipped/
  truncated); db+WAL vs indexed text (Settings shows both). Cold: `adb root`, per-query
  `sync; echo 3 > /proc/sys/vm/drop_caches` then a fresh `ScaleBench` process; warm: median of ≥9.
  Queries: rare word, mid word, common word, typed common prefix (expect frequency-guard
  downgrade note), rare prefix, quoted phrase, AND of terms, filtered (author/series/tag/status),
  single-book "show all" page (ScaleBench `page` arg). Production constants left at defaults.
- [x] **6. Rebuild/delete at full scale.** Settings → "Rebuild index": time the clear+requeue
  action (its `clearAll` replaces the 26-s extrapolation); then "Turn off and delete index":
  time it. Closes the rebuild()/deleteIndex() e2e item with 1,500-book numbers. All measurements
  are done once these complete.
- [x] **7. Tune only if targets fail** (warm median > 100 ms for target queries): adjust
  `MAX_COUNTED_PASSAGES` / `MAX_PREFIX_DOCUMENTS` / `BROAD_FILTER_PROBE_BOOKS` minimally via
  ScaleBench's args first, update every test/doc quoting them, re-measure the affected queries.
- [x] **8. Write the report** (`docs/superpowers/specs/2026-10-04-library-text-index-scale-results.md`):
  environment + fixture recipe; results table (metric | value | cold/warm | target | pass/fail)
  for first-index duration, batches, totals, storage, each query, gfx, reader-pause, kill/resume,
  rebuild/delete/clearAll, peak memory; explicit "generator was recreated" statement;
  Known-unverified section for what remains (SQLite 3.28 SQL, worker-unplug, fixed-layout/RTL,
  real hardware, LazyColumn load-more, Room query cancellation, reactive re-query);
  appendices: `genfixture.py`, `monitor.sh`, `fling.sh`, `integrity.sh`, `ScaleBench.kt`.
- [x] **9. README + screenshot.** Features entry for library text search (grouped by book, counts
  with "N+" for very common queries, chapter labels, jump-to-passage, filters); limits list (no CJK
  word segmentation; token/phrase not byte matching; "N+" counts and exact-word prefix fallback;
  6 MiB text cap → partly indexed; stored data ≈ measured multiple of indexed text; first
  indexing slow on a large library; a few books Readium cannot parse in full index only in part
  until the fallback extractor lands); architecture tree gains `data/index/` (FTS4 chunks, indexed
  via a unique WorkManager chain, grouped search); note the vendored navigator additions
  (`evaluateJavascript(script, href)`, `scrollToDecoration`); Screenshots row with
  `search-text.png`.
- [x] **10. Cleanup + test gates.** Delete `ScaleBench.kt`.
  `ANDROID_SERIAL=emulator-5556 ./gradlew -I tools/dbtest-suffix.init.gradle
  connectedDebugAndroidTest`; then `IndexWorkerTest` by hand (appops already granted). Confirm on
  `emulator-5554`: `pm list packages | grep quire` → only `com.quire.reader`; `run-as
  com.quire.reader ls databases` → `quire.db` intact.
- [ ] **11. Housekeeping.** `adb -s emulator-5556 emu kill`; `avdmanager delete avd -n quire_scale`.
  Old clone: `git worktree remove` the four under `~/Projects/AndroidApps/Quire/.claude/worktrees/`,
  `git branch -D phase0-probe worktree-agent-*` (and its local `feature/library-text-index` once
  the worktree is gone; origin keeps it). Add `.claude/` to `.gitignore`. Remove
  `balance_plan.md`. Commit + push `feature/library-text-index`; fast-forward `main` and push
  (confirm with you first). Optionally free the 1.4 GB scratchpad once the appendices are written.

## Verification

- Every scale number traces to a run log (monitor.sh, ScaleBench output, gfxinfo, dumpsys meminfo);
  the report marks pass/fail against the targets (warm < 100 ms for the query set, bounded cold).
- `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug test lint'` green after the
  polish and after `ScaleBench.kt` deletion.
- Instrumented suite green on the scale AVD via the init script; the real app on emulator-5554 is
  untouched afterwards (package list + `quire.db` check).
- README references only existing screenshots; the report's scripts appendices match what ran.
- `git log main` contains the feature after the fast-forward; `git status` clean.

## Risks

- Host CPU noise skews timings: no Gradle builds or heavy commands during timed stretches;
  unattended run checked every ~30 min.
- Scratchpad (tmpfs) can vanish on reboot: step 0 copies the screenshot; scripts are inlined in
  step 8; the fixture itself lives on the emulator.
- The ~2–3 h run may outlive a session: monitor.log + logcat on disk let a later session resume
  analysis; kill/resume is itself one of the measured behaviors.