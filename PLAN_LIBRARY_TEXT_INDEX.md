# Plan: Library-wide book text search (persistent FTS4 index)

Spec: `docs/superpowers/specs/2026-10-03-library-text-index-design.md` (authoritative; this plan only sequences it and grounds it in the code).

## Context

Quire users with 1,000+ EPUBs cannot find a remembered line without opening each book. The spec adds an incremental, persistent, local full-text index (Room FTS4 `unicode61`, external content), a background indexer, an "Inside books" scope in the library search field, grouped chapter-labelled snippets, and tap-to-open with the match underlined. Metadata search stays unchanged. The spec makes a **Phase 0 go/no-go probe** mandatory before any permanent index code, so this plan is gated on it.

On approval, first copy this plan to `PLAN_LIBRARY_TEXT_INDEX.md` in the repo root (per saved user preference; do not commit). Note: the spec cites `PLAN_INDEXING.md` as its source plan, but it does not exist on disk or in git.

## Findings from exploration (what the code actually looks like)

All paths under `app/src/main/java/com/quire/reader/` (`R/`).

- **Room**: `R/data/db/QuireDatabase.kt` v1, `exportSchema=false`, no migrations/converters, DB name `quire.db`. Entities in `Entities.kt` (child FKs all `onDelete=CASCADE` to `BookEntity`; `BookEntity` has `mtime`, `sizeBytes`, `readable`, `addedAt`). `BookDao.save` uses `@Update` for existing ids so cascades are not triggered — keep. Room 2.8.5, KSP 2.3.12, WorkManager 2.12.0, DataStore 1.2.1.
- **Scanner**: `R/data/scan/LibraryScanner.kt` serializes via `Mutex`, has `progress`, and opens/closes publications in `readAndStore`. **No post-scan hook exists**; `scan()` just returns `ScanResult`. `LibraryRepository.rescan()` (VM callers at `QuireViewModel` ~l.127/161/187/197) and `importFiles()` (calls `scanner.scan()` directly, bypassing `rescan`) are the two places to trigger indexing.
- **Scheduling**: `ScanWorker` (unique periodic, gated by `watchNewBooks`, no constraints). WorkManager uses default auto-init. `QuireApplication` is a hand-rolled container (`appScope`, lazy singletons); it already collects `watchNewBooks` to (un)schedule — the template for indexing settings.
- **Settings**: `SettingsStore` private `flow(key, default)` helper + `setX`; surfaced through `LibraryRepository` → VM `stateIn(Eagerly)` → `SettingsScreen` `Toggle(label, sub, on, onClick)`.
- **Reader**: `QuireViewModel.read` (~l.244-262) calls `publicationLoader.open` directly (no scanner); builds `ReaderSession(book, pub, positions, initial)` where `initial = parseLocator(saved)` unless `restart`. `closeReaderSession` (~l.302) cancels `readerJobs`. `ReaderSession.go()` returns `Unit` and no-ops if navigator is null; `applySearchHits(hits)` underlines via group `"search"`; `goToProgress(p)` exists (fallback primitive). Navigator is **vendored** (`com.quire.reader.navigator.epub.*`) and `EpubHost` creates it with `initialLocator = session.current.value`.
- **UI**: library metadata search = `UiState.query/searchOpen`, filtered synchronously in `visibleBooks` (`R/ui/QuireState.kt:105`), search field at `LibraryScreen.kt:110`. In-book search = `setTextQuery` with `delay(250)` + job cancel (`QuireViewModel` ~l.417-443), overlay `SearchOverlay` in `ReaderSheets.kt:236`, snippet highlighting via `buildAnnotatedString` + `SpanStyle(background = Nq.accentA(0.30f))`. Components: `Segmented/SegOption`, `ListCover`, `QText`, `Kicker`, `ProgressLine`.
- **Permission**: `StoragePaths.hasAllFilesAccess()`; no in-app banner after onboarding (only toast on rescan) — indexing's "permission missing" state needs its own surface.
- **Tests**: JVM JUnit4 only, backtick one-liner names, pure functions, no mocking/Robolectric. `app/src/androidTest/java` is **empty**; no Room schema export, no `androidTestImplementation(room.testing)`.
- **Fixture**: no 1,500-book generator exists in the repo, `plans/`, or git history. Spec says to locate it first; it must be found elsewhere or recreated (see Verification).

## Approach

### Phase 0 — throwaway probe (go/no-go; separate scratch branch/worktree, deleted afterward)
Per spec §Phase 0, on the API 36 emulator with one real EPUB:
1. `publication.content()` yields paragraph/heading elements with locators (inspect `TextContentElement.segments[].locator` for precision); time a typical novel.
2. Room FTS4 external-content entities compile under AGP 9.0.1/KSP; inspect generated `QuireDatabase_Impl` for the exact `CREATE VIRTUAL TABLE` + trigger SQL (needed verbatim for `Migration(1,2)`); verify bound prefix/phrase MATCH, `offsets()`, sync.
3. Token accounting vs `unicode61` (punctuation, accents, non-ASCII, 64-token limit); UTF-8 byte offset → Kotlin char index mapping.
4. Phrase at chunk boundary returned exactly once, including a phrase spanning two source elements; **ownership filter via `offsets()` vs primary-end byte offset**.
5. Derived locator navigates + underlines after navigator attach; unresolved target; confirm the vendored navigator's `go` can report success (check its return type) and that the result reflects real resolution.
6. **Extra gate I'm adding**: time grouped passage counts for a very common word ("the") over a large synthetic index. `offsets()` per matched row may blow the <100 ms target. If it does, stop and bring options to the user (e.g. cap counted passages per book, "99+" display) rather than silently changing the contract.

Record results in the plan file; any failed gate stops permanent work and returns to design review.

### Phase 1 — Storage and migration
- `R/data/index/` entities (placed with db entities or in `data/db/`): `TextChunkEntity` (`text_chunk`: id PK, `bookId` FK cascade, `seq`, `chapter`, `href`, token range, `primaryEndByte`, `text`, `mapping` JSON, `progression`; unique index `(bookId, seq)`), `TextChunkFts` (`@Fts4(contentEntity=TextChunkEntity::class, tokenizer="unicode61")`), `IndexStateEntity` (`index_state`: PK `bookId` FK cascade, `mtime`, `sizeBytes`, `status` done/failed/skipped, `completedAt`, `chunkCount`, `textBytes`, `truncated`).
- `QuireDatabase` → version 2, `exportSchema=false`, `Migration(1,2)` with SQL copied from the Phase 0 generated Impl (tables, indexes, FTS, sync triggers). No destructive fallback.
- `IndexDao`: eligible-books query (`readable=1` and no state or `mtime/sizeBytes` mismatch, newest `addedAt` first); transactional `replaceBook(bookId, signature, chunks, state)` and `markTerminal(...)` (deletes obsolete chunks); `clearAll()`; coverage counts flow (eligible, searchable = `done` with matching signature, failed, skipped, truncated); indexed-text-bytes sum.
- Decision: books with `readable=0` are excluded from "eligible" and never queued (scanner already knows they fail).

### Phase 2 — Pure logic (JVM-tested first)
- `data/index/FtsQuery.kt`: input → sealed result `{TooShort, OverLimit, Query(matchExpr, tokenCount)}`; builds a quoted-token MATCH string so no raw input reaches FTS syntax; last unquoted segment gets `*`; unmatched quote = unfinished phrase; token counting shares the Phase-0-validated tokenizer logic with the chunker.
- `data/index/TextChunker.kt`: source elements (text + per-segment locator JSON) → chunks (600–900 chars, merge small, heading starts a chunk, long element spans chunks, no mid-token splits), appends up to 63 following tokens within the same `href`, records primary end byte offset and segment mapping, enforces the 6 MiB per-book cap (stop at chunk/token boundary, `truncated=true`).
- Helpers: UTF-8 byte offset → char index; mapping lookup (chunk byte range → segment locator + source text range) building `Locator.Text(before, highlight, after)` from original text.
- Tests (`app/src/test/.../data/index/`): merge/split/heading ownership, long elements, phrase overlap dedupe, cap/truncation, byte↔char mapping with multibyte text, FtsQuery operators/quotes/prefix/Unicode/limit, grouping/ranking order (stable tie-break), signature eligibility, cancellation transitions.

### Phase 3 — Indexer, scheduling, settings
- `data/index/LibraryIndexer.kt` (owned by `QuireApplication` as a lazy singleton next to `scanner`): `runBatch(deadline)` under a single `Mutex` taken per book; opens publication via `publicationLoader.open`, iterates `publication.content()`, chunks, closes iterator + publication in `finally`; checks `ensureActive()` and reader-busy while consuming; re-reads file `(mtime,size)` and DB book row before publishing; pause/cancel discards the buffer without a terminal write. An `epoch` counter (bumped by rebuild/delete/disable before they take the lock) is compared inside the publish transaction so a finishing extraction cannot resurrect cleared rows. `CancellationException` always rethrown.
- Outcomes per spec table: unreadable/DRM → `failed`; no text → `skipped`; storage permission missing → whole queue blocked (activity state), no per-book failures.
- Activity observable: `StateFlow<IndexActivity>` (Idle, Running(progress), PausedForReader, WaitingForCharging, Disabled, PermissionMissing); WaitingForCharging derived from `WorkManager.getWorkInfosForUniqueWorkFlow` ENQUEUED + charging-only setting.
- Reader-busy: `indexer.readerBusy` in-memory only (no durable flag). VM sets it at the very start of `read()` (before `publicationLoader.open`), clears it in `closeReaderSession()` and on open failure, then requests indexing.
- `data/index/IndexWorker.kt`: the **only** execution path (startup/import/scan just enqueue). Unique chain `indexing-chain` with `ExistingWorkPolicy.APPEND_OR_REPLACE`; constraint `requiresCharging` when the setting is on; runs `runBatch` bounded to 5 minutes; if eligible work remains, appends a continuation; empty batch exits immediately; reader open → exit with success (reader close re-requests); no storage permission → exit and rely on foreground re-request.
- `SettingsStore`: `indexingEnabled` (default true), `indexChargingOnly` (default false) following the existing `flow(...)`/`setX` pattern; re-expose via `LibraryRepository` and VM `stateIn`.
- `QuireApplication.onCreate`: combine the two settings; on change cancel obsolete work and enqueue with the current policy; also enqueue once at startup (independent of `watchNewBooks`).
- Triggers: after `LibraryRepository.rescan()` and `importFiles()` complete, and in `onForeground()`/permission re-grant, call `indexer.request()`. Leave `ScanWorker` metadata-only; add the handoff after its `rescan()`.
- Rebuild: bump epoch, cancel chain, `mutex.withLock { clear chunks+state }`, re-enqueue. Delete: disable setting, same clear, no re-enqueue. Disable: cancel only, keep data.

### Phase 4 — Search data and view-model
- `LibraryRepository.searchText(query, filters)` → `Flow<TextSearchResult>`: FTS `MATCH` with bound arg, join `text_chunk` → `index_state` (`done` and signature equals current book) → `book`, apply scope/status filters (no metadata substring), keep only owned rows (earliest `offsets()` < `primaryEndByte`), group by book: count desc, `lastOpenedAt` desc, `bookId`; `LIMIT 40` plus a total-matching-books count for the visible cap notice. Snippets (≤5 per book, 3 shown initially) loaded for those 40 books only, computed in Kotlin from stored text + first match offset → structured spans (not `snippet()` HTML), resolved chapter + target locator.
- Single-book paged query for "Show all in this book" (keyset on `seq`).
- VM: new `UiState` fields `searchScope` (Metadata/Text), library text query job with 250 ms cancel-and-relaunch following `setTextQuery`'s pattern but separate state; `StateFlow<LibraryTextSearch>` (query status: Idle/TooShort/OverLimit/Searching/Results/NoMatch + coverage). Metadata path untouched.

### Phase 5 — Reader navigation
- `ReaderSession.go` returns a `Boolean`/outcome from the navigator (verify vendored `go` signature in Phase 0); `null` navigator → failure.
- `QuireViewModel.read(bookId, restart, target: IndexTarget?)`: target (book id, indexed signature, locator, matched text, progression) overrides saved locator for that opening only. Validate signature against the current book first; stale → toast "book changed", enqueue refresh, do not open at the text.
- After navigator attach (in the same place `EpubHost` calls `session.attach`), navigate to the target; on success `applySearchHits` with one hit; on failure `goToProgress(progression)` and show "exact passage unavailable" without underline.
- Library-search mode for the reader overlay (`UiState` flag + book id): `SearchOverlay` in `ReaderSheets.kt` gets a labelled mode that uses the paged single-book repository query and same targets; ordinary in-book search remains Readium-iterator based and unchanged.

### Phase 6 — UI
- `ui/library/TextSearchResults.kt`: `Segmented` ("Titles & authors" / "Inside books") in `LibraryScreen` under the search field; lazy list of book cards (`ListCover`, title/author, passage count via `QText`, snippets as `AnnotatedString`, expand 3→5, "Show all in this book"), coverage line with `ProgressLine`, and distinct empty/status states (no index yet, indexing, reader pause, charging wait, disabled, permission missing + button to `allFilesAccessIntent`, too short, over limit, no match, truncated-book note).
- `SettingsScreen`: "Library search" section with the two switches, coverage (searchable/total, failed/skipped/truncated), database size (main + `-wal`, labelled "Database storage"), indexed text bytes, Rebuild button, and a confirmed "Turn off and delete index" action.

### Phase 7 — Docs and cleanup
Remove probe/benchmark scaffolding; README: Features (library text search), Architecture (`data/index`), limitations (CJK, token-phrase semantics, truncation), plus a real screenshot in `docs/screenshots/`.

## Critical files

Modify: `data/db/{QuireDatabase,Entities,Daos}.kt`, `data/LibraryRepository.kt`, `data/SettingsStore.kt`, `data/scan/ScanWorker.kt`, `QuireApplication.kt`, `ui/QuireState.kt`, `ui/QuireViewModel.kt`, `ui/library/LibraryScreen.kt`, `ui/settings/SettingsScreen.kt`, `ui/reader/ReaderSheets.kt`, `reader/ReaderSession.kt`, `reader/EpubHost.kt`, `app/build.gradle.kts` (androidTest deps), `README.md`.
New: `data/index/{LibraryIndexer,TextChunker,FtsQuery,IndexWorker}.kt`, `ui/library/TextSearchResults.kt`, JVM tests under `app/src/test/.../data/index/`, androidTest migration/FTS tests under `app/src/androidTest/`.

Reuse: `BookDao.save` pattern, `PublicationLoader.open`, `ReaderSession.applySearchHits/goToProgress/chapterTitle`, `ScanWorker.schedule` pattern, `StoragePaths.hasAllFilesAccess/allFilesAccessIntent`, the `setTextQuery` cancel/delay pattern, existing snippet `AnnotatedString` styling, `Segmented`, `Toggle`.

## Verification

1. `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug test lint'` — pure JVM tests green.
2. `connectedDebugAndroidTest` on `Android_API_36`: file-backed v1 DB (hand-built via `SQLiteOpenHelper` since schema export is off; add `androidTestImplementation(libs.androidx.room.testing)` and test runner deps) seeded with books/state/bookmarks/highlights → open via v2 migration → data intact → FTS insert/update/delete → book delete cascades chunks/FTS/state → aborted per-book transaction leaves the previous committed index.
3. Seven-book emulator smoke from the spec (Pemberley, Transylvania, phrase, prefix, no-match; odd-character input; tap snippet → underline; Show all + paging + editing; add/change/delete book; permission denied/granted; charging-only; disable/rebuild/delete; damaged EPUB = failed, image-only = skipped, oversized = done+truncated; kill app / open reader mid-index). Capture screenshots with `adb exec-out screencap`.
4. Scale: locate the prior 1,500-book generator first (not in repo; try the user's machine/other worktrees, else write a throwaway generator and say so). Record first-index time, state totals, DB/WAL growth, cold/warm query timings (<100 ms excluding debounce), library scrolling during indexing, bounded worker continuation, kill-and-resume.

## Open items to flag at approval

- `PLAN_INDEXING.md` and the 1,500-book generator are both missing locally.
- The `offsets()`-based ownership/count query is the main performance risk (Phase 0 step 6); if it fails the <100 ms target, the contract needs a user decision.
- Per spec, implementation starts only after this plan is approved and an execution method (inline vs. subagent-driven) is chosen. (Done: subagent-driven.)

## Phase 0 outcome (2026-10-03)

Full evidence: `docs/superpowers/specs/2026-10-03-library-text-index-phase0-results.md`. Gates 1-5 PASS with caveats; gate 6 FAIL for very common terms (`the` 16.5 s, `"of the"` 26 s over 1.17M chunks).

**Decision (user, 2026-10-03): cap counted passages.** Examine at most N matching passages per query (start ~2,000-10,000; tune against the 100 ms target), show counts as "N+" when capped, and record that the cap biases toward oldest-indexed books. Rare/mid-frequency queries stay exact. This amends the spec's "total canonical matching-passage count" contract and the spec must be revised to match before Phase 1.

**Amendments to the phases above**
- Headings: Readium 3.3.0 yields only Body roles; detect heading starts from the locator `cssSelector` (`h1`..`h6`). Chapter labels come from the TOC. `Content.Iterator` has no `close()`.
- Element text is whitespace-normalised but `locator.text.highlight` is raw DOM text; build `Locator.Text` from stored raw text.
- Migration: use the verbatim generated SQL from the results file; Room drops/recreates FTS triggers around migrations itself.
- Query building: prefix must be `pre*` or `"a b pre*"` (never `"pre"*`); only tokenised, quoted words may enter MATCH (`OR`, `NEAR`, `text:`, leading `-`, unbalanced quotes are live syntax).
- Tokenisation: the 64-token limit is a product rule only. Kotlin tokenizer matched `unicode61` on ~468k tokens but differs on rare characters; allow a small margin on the 63-token context rather than hard-coding Unicode tables.
- Navigation: vendored `go()` returns `Boolean` but returns `true` for absent text; real success = `window.readium.getDecorations('search').items.length` via `evaluateJavascript`. `ReaderSession.go` becomes suspend and returns an outcome, running the progression fallback. Open at the target as `initialLocator` (or wait for first page load) so the saved position is correct.
- Storage is ~3.5x source text (mapping JSON is a large share); the Settings storage labels and the per-book cap should reflect this.
- Cold first runs of mid-frequency queries exceed 100 ms (`"in the garden"` 207 ms, `king queen` 146 ms, `wander*` 1.1 s); the scale phase must report cold vs. warm honestly.

**Cleanup owed:** the probe app `com.quire.reader.probe` and a 3.8 GB `perf.db` remain on the emulator (uninstall frees it); adbd is still in root mode; the probe worktree/branch is to be discarded after the spec is revised.
