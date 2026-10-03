# Balance plan: library text search — what is left

Written 2026-10-03. Companion to `PLAN_LIBRARY_TEXT_INDEX.md` (the approved plan), `PLAN_FALLBACK_EXTRACTOR.md` (the next feature, planned only) and the spec `docs/superpowers/specs/2026-10-03-library-text-index-design.md`. Phase 0 evidence: `docs/superpowers/specs/2026-10-03-library-text-index-phase0-results.md`.

## Where things stand

Done and verified (JVM tests, instrumented tests on the emulator, and screenshots):

- Phase 0 probe: gates 1-5 passed with caveats; gate 6 (speed of very common queries) failed and was resolved by decisions below.
- Phase 1 storage: Room v2, external-content FTS4 `unicode61`, `Migration(1,2)`, `IndexDao`; migration tested on a file-backed v1 database.
- Phase 2 pure logic: tokenizer, `FtsQuery`, `TextChunker`, offset mapping, ranking, capped counts.
- Phase 3 indexer: `LibraryIndexer`, `IndexWorker` (unique chain, 5-minute batches), settings, reader-busy pause, bulk `clearAll`.
- Phase 4 search: grouped query with counting cap, snippets, single-book paging, common-prefix frequency guard, broad-filter handling, view-model state.
- Phase 5 reader: navigation outcome (`goToTarget`), stale-target handling, library-search overlay; a latent `EpubHost` crash fixed (`key(session)`).
- Phase 6 UI: scope switch, grouped result cards, status and coverage states, Settings "Library search" section.
- Audit: chapter labels 100% correct on 3,200 sampled hits in 8 books (was 83.3%).
- Smoke: 7-book and extended end-to-end checks passed on the emulator (kill and resume, reader pause, add/change/delete book, charging-only, permission, disable/rebuild/delete, notices).

Decisions already made by the user:

- Very common queries: cap the counted passages (shown as "N+"). Typed common prefixes use the frequency guard (exact word plus a note).
- Fallback extractor: build it, with the new `unreadableResources` column (option A in `PLAN_FALLBACK_EXTRACTOR.md`).
- Opening a search result overwrites the saved reading position (as specified); no "back to my place" feature.

## Remaining work, in order

### 1. 1,500-book scale verification (not run)

A first attempt was stopped part way and produced no measurements. Nothing about the 100 ms target, first-index time or storage at 1,500 books is verified yet.

- **Fixture:** no generator exists anywhere (repo, git history, machine). Recreate one: about 1,045 real EPUBs copied from `/home/caan9/Calibre Library` (copy only, never modify) plus about 455 derived EPUBs (re-zipped slices with distinct content), laid out Calibre-style. Check free disk first: `/home` was 96% full with about 25 GB free.
- **Isolation:** use a separate throwaway AVD, never the `Android_API_36` AVD that holds the real app. Install with the `.dbtest` init script (`tools/dbtest-suffix.init.gradle`).
- **Measure** (cold = first call after `drop_caches` as root, warm = median of at least 9): first-index duration and number of 5-minute batches; searchable/failed/skipped/truncated totals; database and WAL growth versus indexed text; query timings for a rare word, mid-frequency word, common word, typed common prefix, rare prefix, quoted phrase, AND of terms, filtered queries and a Show-all page; library scrolling while indexing (`gfxinfo`); reader use while paused; kill and resume on the large queue; rebuild and delete duration; peak memory.
- **Tune** from the data, keeping changes minimal and updating tests and docs that quote them: `MAX_COUNTED_PASSAGES` (5000), `MAX_PREFIX_DOCUMENTS` (200,000), `TextSearcher.BROAD_FILTER_PROBE_BOOKS` (8). Known so far on a 505k-chunk synthetic index: warm full pipeline under 100 ms for the typed inputs measured except `"king the"` (101 ms); cold 100-500 ms; indexing about 4-5.5 s per book on the emulator after the chapter-label fix, so a first pass over 1,500 books is roughly 2 hours.
- **Report** a results table (metric, value, cold/warm, target, pass/fail) and state that the generator was recreated.

### 2. Fallback extractor and partial-coverage reporting (planned, not started)

Follow `PLAN_FALLBACK_EXTRACTOR.md`. Start only after item 1, because Gradle builds and edits would disturb its timings. Summary: reconcile Readium's per-resource yield against the stored size, parse the XHTML ourselves where Readium yielded almost nothing (the `<title/>` books), count resources nobody can read, show them as partly indexed with a cause-specific note, and mark a book with no readable resource `failed`. Amend `Migration(1,2)` for the new column (nothing has shipped). Verify with *Juliet Takes a Breath* (a real Calibre book that currently indexes to one chunk) and a corrupted-chapters fixture.

### 3. README and real screenshot

Add to `README.md`: a Features entry for library text search; the `data/index` architecture and WorkManager chain; limitations (no CJK word segmentation; token-phrase rather than byte matching; counts are "N+" for very common queries and prefixes of very common words fall back to the exact word; books over the 6 MiB text cap are partly indexed; stored data is about 3.5 times the source text; first indexing is slow on a large library; Readium limits until item 2 lands); and a note that the vendored navigator gained `evaluateJavascript(script, href)` and `scrollToDecoration` for exact-passage navigation. Add an actual app screenshot to `docs/screenshots/` (a good candidate from the Phase 6 run is `text_pemberley_results.png`, in the session scratchpad, which is temporary: take a fresh one from the `.dbtest` app if it is gone).

### 4. Small open items

- The Settings screen status line said "Turn it on in Settings." while already inside Settings. A fix was requested in the stopped scale run; check `ui/TextSearchPresentation.kt` and fix with a test if it did not land.
- `Nq.danger` (`0xFFE08A84`) was added for the destructive "Turn off and delete index" action because the design system has none. Keep unless the user objects.
- A multi-term excerpt is cut around the first matching word, so another query term can fall outside the shown text. Polish only.
- The indexer still stores the (unreliable) `cssSelector` in the slim locator. Navigation ignores it; dropping it would shrink the mapping data. Optional, needs a rebuild of existing indexes.

### 5. Verified-nowhere list (carry into release notes or close out)

- Query SQL on SQLite 3.28 (API 30). Tests ran on 3.44.3; the SQL avoids `MATERIALIZED` and window functions, but this is untested.
- A worker stopped by the system when the device unplugs mid-run (should behave like the tested cancel path).
- `rebuild()` and `deleteIndex()` end to end through WorkManager (only the `clearIndex` core and the UI path were exercised).
- `clearAll` at 1.17M chunks: about 26 s is extrapolated from 150,000 chunks (3.4 s).
- The "at least N matching books" variant and the `PausedForReader` text on screen (state confirmed by logs and the DB, label not visible while the reader is open).
- Fixed-layout and RTL EPUBs; performance on real phone hardware; the overlay's scroll-driven load-more through the real `LazyColumn` (the paging function is tested directly).
- A Room query already running is not cancelled when a newer input arrives (the result is ignored, SQLite keeps working).
- The reactive search re-queries on each finished book while a search is open during indexing.
- `offsetTopForId` in the vendored `ChapterWebView` returns dp where the neighbouring code uses px (latent, untouched).

### 6. Housekeeping

- **Throwaway AVD:** `quire_scale` (headless, `emulator-5556`) was left running with `com.quire.reader.dbtest` installed. Stop it (`adb -s emulator-5556 emu kill`) and delete it (`avdmanager delete avd -n quire_scale`) unless resuming item 1 immediately. The user's emulator (`emulator-5554`) holds only the real `com.quire.reader`.
- **Probe and agent branches/worktrees:** `phase0-probe`, `worktree-agent-*` and their directories under `.claude/worktrees/` are throwaway. The evidence they held is now in `docs/superpowers/specs/`. Remove with `git worktree remove` and `git branch -D` once confirmed. Do not commit `.claude/`.
- **Scratch files** in the session scratchpad (about 1.4 GB, helper scripts, screenshots) are temporary.
- **Branch:** the work was done on `feature/library-text-index` and fast-forwarded into `main`.

## Rules to keep following

- Run instrumented tests only through the protective init script, never plain `connectedDebugAndroidTest`; it would replace and then uninstall the real `com.quire.reader` and wipe its data:
  `./gradlew -I tools/dbtest-suffix.init.gradle connectedDebugAndroidTest` (runs as `com.quire.reader.dbtest`). `IndexWorkerTest` needs all-files access and is skipped in that run; run it by hand after `adb shell appops set com.quire.reader.dbtest MANAGE_EXTERNAL_STORAGE allow`.
- Afterwards confirm `adb shell pm list packages | grep quire` shows only `com.quire.reader` and `adb shell run-as com.quire.reader ls databases` still lists `quire.db`.
- Instrumented test method names must not contain commas (R8/dex rejects them).
- Build with `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug test lint'`; in worktrees the sandbox refuses that form, so pass `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/home/caan9/Android/Sdk` directly.
- Readium stays at 3.3.0 and Coil at 3.5.0.
- Installing this build over the real app will migrate its `quire.db` from v1 to v2 and start background indexing of the user's library. That is the intended behaviour, but back up first if the library matters.
