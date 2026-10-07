# Advanced reading controls — opt-in typography and page layout

Baseline: `3f72219` (current HEAD of `main`; the draft was written against `b4ef9e4`,
two commits earlier — see "Baseline drift" below). Ships as **one PR of six ordered
commits**, every one passing `assembleDebug test lint`, merged with a merge commit or
rebase (not squash) so the boundaries survive for review and bisect. This file is also
the change's design record (the repo's `plans/*.md` convention): the anchoring
requirement is "Atomic, position-preserving application", the acceptance scenarios live
under "UI copy", and the upgrade-gate evidence is appended under "Verification record".

## Context

Add opt-in advanced typography and layout controls to the reader, keeping the visibility
toggle independent of rendering. Readium stays pinned to 3.3.0; Coil 3.5.0; no SDK, AGP,
Readium or Coil upgrade. Quire persists semantic enums and maps them through its vendored
navigator/editor. No Readium, Coil, SDK, or AGP upgrade is part of this change.

## Current state (re-verified against the repo)

Every claim below was re-checked in the code at `3f72219` and holds:

- `reader/PrefsMapper.kt` submits `publisherStyles = false` and `hyphens = true`.
- `ReaderPrefs` has seven fields (`theme`, `font`, `fontSize`, `lineHeight`, `margin`,
  `align`, `mode`). Its JSON uses `ignoreUnknownKeys = true` and `encodeDefaults = true`;
  `fromJson` returns `null` on damaged JSON.
- Global defaults are one `ReaderPrefs` JSON under `READER_DEFAULTS` in DataStore
  (`SettingsStore.readerDefaults`). Per-book prefs are a whole-object snapshot in
  `BookStateEntity.prefsJson`; `StateDao.edit` is `@Transaction` (`Daos.kt:298`).
- `LibraryRepository.readerPrefs` resolves any decodable JSON as a full override
  (`ReaderPrefs.fromJson(state?.prefsJson) ?: defaults`); `hasBookOverride` is
  "`fromJson != null`" — this is what C1 fixes.
- `SnapshotSettings.readerDefaults` is a typed `ReaderPrefs?`; `SnapshotBookState.prefsJson`
  is raw JSON; `SnapshotCodec` uses `ignoreUnknownKeys = true` (plus `encodeDefaults`,
  `explicitNulls = false`). `SnapshotMerge.mergeSettings` merges `readerDefaults` as
  `incoming ?: local`.
- `buildConfig = false` in `app/build.gradle.kts` (so a debug-log flag must come from
  `ApplicationInfo.FLAG_DEBUGGABLE`, not BuildConfig).
- `EpubHost` deduplicates submissions with `lastPrefs` inside `AndroidView.update`
  (`EpubHost.kt:142-145`); the initial preferences go to the fragment factory.
- `ReaderScreen.kt:111` maps with `remember(prefs) { prefs.toEpubPreferences() }`.
- `QuireViewModel.updatePrefs` writes `_prefs` optimistically and also collects the Room
  echo (`QuireViewModel.kt:493`).
- Vendored 3.3 ranges/steps (`EpubPreferencesEditor.kt`): fontWeight `0..2.5 / 0.25`;
  letterSpacing and wordSpacing `0..1 / 0.1`; paragraphIndent `0..3 / 0.2`;
  paragraphSpacing `0..2 / 0.1`; lineHeight `1..2 / 0.1`; fontSize `0.1..5 / 0.1`;
  pageMargins `0..4 / 0.3`; **typeScale is non-linear**: supportedRange `1..2` with
  `StepsProgression(1.0, 1.067, 1.125, 1.2, 1.25, 1.333, 1.414, 1.5, 1.618)`.
- `readingProgression` and `language` use `getIsEffective = { true }` (C2's premise).
- The four rows gated on `preferences.X != null` are exactly `fontWeight`, `lineHeight`,
  `paragraphSpacing`, `typeScale` (C8's premise).
- Scroll mode is the bounded live-window shell from `151368f`
  (`ContinuousBookWebView`). The C3 race is real: `restoreAnchor()` launches a
  coroutine and only inside it does `awaitLayoutGeneration()` capture
  `layoutGeneration` — after `remeasureAllFrames()` has already posted; the fragment's
  `reflowContinuousSurface()` calls capture → remeasure → restore in that order
  (`EpubNavigatorFragment.kt:845-853`).
- C4's split paths are real: `submitPreferences` updates `_settings` then `css`
  (`EpubNavigatorViewModel.kt:223-249`); `initReadiumCss()` sends `setCSSProperties`
  through the events channel; the fragment's settings collector calls
  `reflowContinuousSurface()` only when font size or theme changes
  (`EpubNavigatorFragment.kt:797-816`); `invalidateResourcePager()` reads
  `currentLocator` when the event is handled (`:791-795`).
- `EpubNavigatorFactory.createPreferencesEditor(currentPreferences)` exists
  (`EpubNavigatorFactory.kt:85`).
- Design records in this repo live in `plans/*.md` (README cites
  `plans/continuous-scroll.md` as one); `docs/` holds topic docs
  (`docs/user-data-backup.md`) and screenshots. This change adds its record to `plans/`
  and touches nothing under `openspec/`.
- Existing instrumentation referenced by the apply-pipeline commit's gate exists:
  `ReaderThemeChangeTest`, `ReaderBoundedScrollTest`, `ReaderContinuousScrollTest`,
  `ReaderTargetTest` (androidTest), plus `tools/dbtest-suffix.init.gradle`.

### Baseline drift (`b4ef9e4` → `3f72219`)

Two commits landed after the draft's baseline: `47e2c82` (indexing without toggling the
setting) and `3f72219` (library sort persistence). They touch `SettingsStore.kt`,
`QuireViewModel.kt` and `QuireState.kt` — files this plan also modifies — but only in
library-sort/indexing code paths, disjoint from the reader-prefs code this plan changes.
Consequences: rebase onto `3f72219`; build the upgrade-gate APK from `3f72219`, not
`b4ef9e4`.

### Finding: the README scroll-surface claim is stale
The draft says "the README still describes the older eager whole-book surface". It does
not: `151368f` itself updated README.md (lines 42 and 132 describe the bounded live
window; the only remaining "eager" mention is the deliberate "supersedes
eager-seamless-scroll's premise" note). **Resolved: the scroll-surface correction is
dropped**; commit 6's README work is the advanced-reading feature bullet and tech note
only.

## Decisions (C1–C10, carried from the reviewed draft)

### C1. Advanced-only book edits must not pin basic fields
`readerPrefs()` and `hasBookOverride()` treat any JSON `ReaderPrefs.fromJson` can decode
as a full override, and every `ReaderPrefs` field has a default, so `{"advanced":{…}}`
decodes as factory `ReaderPrefs()`. A flat DTO carrying the seven basic fields would
either pin inherited basic values on an advanced-only edit, or be misread by current
decoders as factory settings.

**Decision:** `BookReaderPrefs` has an *optional* basic group, detected by whether the
`theme` key is present, plus `advanced: AdvancedReaderPrefs? = null`.
- `readerPrefs`, `hasBookOverride` and `SnapshotMerge` all decode through
  `BookReaderPrefs`.
- `hasBookOverride` means "the basic group is present". An advanced-only override is
  reported separately for the book restore action.
- **Downgrade behaviour (documented, accepted):** an older APK, or a restore into an
  older version, reads advanced-only JSON as factory basic settings.

### C2. The language-group visibility rule always comes out true
In the vendored editor, `readingProgression` and `language` both use
`getIsEffective = { true }`, so "any row effective OR RTL/CJK metadata" is always true.
**Decision:** the Language & writing system group is always shown, including for
fixed-layout books; individual rows still disable from their own effectiveness. Remove
the group-visibility logic and its tests.

### C3. Fix the existing scroll restore race
- `remeasureAllFrames()` returns its target generation (current + 1) *before* posting.
- `restoreAnchor` becomes `suspend` and waits until the layout generation is at or past
  that target.
- Remeasures are serialized so they cannot overlap.
- `assets/quire/continuous-scroll.js` needs no generation tags and is out of scope.

### C4. Order CSS application and reflow in one place
`submitPreferences` computes the rs/user CSS property delta and the invalidation flag
together and sends a single `Event.ApplyPreferences(script, needsInvalidation)`. The
fragment handles it in one serialized path: (1) capture the position; (2) run the
script; (3) invalidate the pager *or* reflow the surface, never both; (4) restore the
captured position once. A reflow is required whenever any CSS property changes, not
just font size or theme. `onSettingsChange` keeps only background-colour and `textZoom`
handling. The `css` collector in `initReadiumCss()` is used only for `onResourceLoaded`,
so it no longer emits its own RunScript.

### C5. Snapshot merge
Merge `advancedReadingEnabled` the same way `SnapshotMerge` merges `readerDefaults`
(`incoming ?: local`), and capture and restore it. `SnapshotSettings` gains nullable
`advancedReadingEnabled`; snapshot schema version stays 1.

### C6. Withdrawn — this change does not use OpenSpec
The draft wrote its anchoring requirement as an OpenSpec ADDED delta because
`openspec/specs/` holds only `.gitkeep`. **Decision: no OpenSpec.** This plan file is the
design record instead: the anchoring requirement is the "Atomic, position-preserving
application" section, the acceptance scenarios live under "UI copy", and the
verification record is appended to this file. The existing `openspec/changes/` are left
untouched.

### C7. Replace runtime range walking with constants
- `PrefsMapper.kt` holds a constants table of ranges and steps for the five advanced range
  rows: fontWeight `0..2.5 / 0.25`, letterSpacing and wordSpacing `0..1 / 0.1`,
  paragraphIndent `0..3 / 0.2`, paragraphSpacing `0..2 / 0.1`. Snapping to these steps
  applies only to the percentage-derived advanced levels; the basic fields pass through
  unchanged (fontSize, lineHeight and margins keep their exact pre-feature values — the
  frozen legacy golden). No typeScale entry — no row exists (C8).
- One JVM test walks the real vendored editor and checks it against the table.
- The mapper becomes the pure function `ReaderPrefs.toEpubPreferences(layout: Layout)`.
  It takes no editor or publication, creates no scratch editors, and stays cheap enough
  for `remember(prefs)`.

### C8. Candidate-editor checks stay, kept lightweight
`fontWeight`, `lineHeight`, `paragraphSpacing` and `typeScale` are the four vendored rows
effective only when `preferences.X != null`. **Decision: there is no Type scale row**
(confirmed during planning); the sheet shows `fontWeight` and `paragraphSpacing`
(advanced, null-valued at Default — these need a candidate editor with that one field
set before availability can be checked) and `lineHeight` (basic, always submitted
non-null — checked directly). Availability is computed only for rows the UI shows. These
editors are plain Kotlin objects — compute availability once per submitted preferences
in the session. No probe machinery; nothing is ever submitted from these checks.
### C9. One authoring context in Settings
`verticalText` is effective whenever `layout == REFLOWABLE`, and direction and language
are always effective, so RTL/CJK sample contexts would give the same results as a
neutral one. **Decision:** Settings uses one neutral reflowable editor. The Settings
live preview (`SettingsScreen.Preview`) is a Compose `Text`, not Readium, so advanced
options do not show in it; the UI copy says so.

### C10. Use Quire's theme names
`imageFilter` is effective only when the theme is `DARK`; the mapper sends every theme
except Sepia and Paper as `DARK`. Tests and copy refer to Sepia and Paper, not "Light".

## Approved product decisions (unchanged)

- Factory hyphenation is `TriState.On`; explicit Automatic submits `null`.
- Settings is a global authoring editor that shows every group; it notes that
  availability varies by book. Disabling or hiding for a specific book is the reader's
  job.
- Null-valued rows use isolated candidate editors (C8). Probe preferences are never
  submitted.
- Simplify typography Off maps to `textNormalization = null`, so the factory mapping
  exactly equals the pre-feature mapping.

## Approach

### 1. Semantic model and storage

- Add the serializable enums and `AdvancedReaderPrefs` inside `ReaderPrefs`.
  `AdvancedReaderPrefs()` is the only place factory defaults are defined.
- `ParagraphPreset` is derived from indent and spacing. Choosing Default, Traditional or
  Screen sets both fields in one reducer. Custom is a read-only status, not a selectable
  option.
- Global advanced settings live inside the `READER_DEFAULTS` JSON; snapshots carry them
  automatically through the typed `readerDefaults`.
- Per-book storage uses `BookReaderPrefs` with an optional basic group and nullable
  `advanced` (C1). Resolution is `override.basic ?: global.basic` and
  `override.advanced ?: global.advanced`. No Room schema change and no migration.
- **Book edits:**
  - A basic edit writes the basic group and leaves `advanced` as it is.
  - An advanced edit writes the effective advanced object and leaves the basic group as
    it is.
  - The full-book reset clears `prefsJson`.
  - "Make default" promotes the whole effective object and clears the book override.
- **Transactional reducers:** use DataStore `edit` and `StateDao.edit`, guarded by a
  `Mutex`, and always reduce against the latest stored state. Replace
  `_prefs.value = next` with "publish the accepted state once, and treat persistence
  echoes that are equal to it as acknowledgments".
- Add `advanced_reading_enabled`, default `false`, with its repository/ViewModel flow and
  setter. Add nullable `SnapshotSettings.advancedReadingEnabled`; capture, restore and
  merge it (C5). Snapshot schema version stays 1.
- **Restore actions:**
  - Global restore is one reducer: `copy(advanced = AdvancedReaderPrefs())`.
  - Book restore is one `StateDao.edit` that sets `advanced = null` and leaves the basic
    group untouched.

### 2. Mapper and availability

- `ReaderPrefs.toEpubPreferences(layout)` is pure (C7).
  - Every existing basic mapping is preserved exactly.
  - It always builds a complete replacement object and never uses `EpubPreferences.plus`.
  - Numbers are snapped to the nearest supported step; ties within floating-point
    tolerance go to the higher step, then the value is clamped to the range.
- **Range-based values:**
  - Spacing: Default → `null`; None → minimum; Small/Medium/Large → 25%/50%/75% of the
    range.
  - Widen: Default → `null`; Slight/Wider/VeryWide/Max → 25%/50%/75%/100% of the range.
  - Weight: Default → `null`; Light/Regular/Medium/Heavy → 0.75/1.0/1.25/1.75.
- **Switches and choices:**
  - TriState: Auto → `null`, On → `true`, Off → `false`.
  - Vertical text: Automatic/Horizontal/Vertical map to Auto/Off/On.
  - Simplify typography: Off → `null`, On → `true`.
  - Typography source: Quire → `publisherStyles = false`, Book → `true`.
  - Reading direction: Automatic → `null`; Left to right / Right to left → `LTR`/`RTL`.
  - Images: Original → `null`; Darken/Invert → the matching `ImageFilter`. Invert is
    never picked automatically.
- **Page layout:**
  - Auto → `null`.
  - Single/Two → `columnCount` ONE/TWO for reflowable books, `spread` NEVER/ALWAYS for
    fixed layout. The other preference stays `null`.
  - The layout is determined exactly as `EpubNavigatorFactory` determines it.
- **Reader availability:** `reader/ReaderPreferenceContext.kt` uses
  `EpubNavigatorFactory.createPreferencesEditor` and the currently submitted preferences.
  Rows with a value use `isEffective` directly; rows without a value use a candidate
  editor (C8). A paragraph preset is only available when both of its rows are available.
- **Settings availability:** one neutral reflowable context (C9).
- The Language & writing system group is always shown (C2).
- Disabled-reason text is presentation only: book typography / mode / theme / writing
  system, with "Not available for this book" as the fallback. It is never used to decide
  enablement.

### 3. Atomic, position-preserving application

- **Single submission path.** Move submissions out of `AndroidView.update`. `ReaderSession`
  owns `submit(prefs)`, deduplicates by equality, and keeps the latest value for a
  detached navigator. `EpubHost` only creates the navigator with the initial mapped
  preferences. Accepted reducer results go straight to the session, so fast changes are
  never lost to `StateFlow` conflation. Visibility, collapse state, dialog opening and
  availability checks cause **zero** submissions.
- **Navigator.** One serialized `ApplyPreferences` path (C4).
  - **Pages:** capture the locator → apply the CSS → wait for layout → `go(locator)` once.
  - **Direction, vertical text, spread or scroll changes:** reuse the same captured
    locator for the single pager invalidation. Never fall back to the chapter start.
  - **Scroll:** `captureAnchor` → apply the CSS once → `remeasureAllFrames()` returns the
    target generation → suspend `restoreAnchor` waits for that generation (C3). Chapters
    loaded later receive the latest CSS through `onResourceLoaded`. The stored
    Pages/Scroll choice is kept; whether vertical text forces scrolling is left to
    Readium.
- **While applying:** suppress transient locator persistence; ignore stale callbacks; a
  change that affects rendering produces exactly one reflow; a no-op produces zero.
- **Failures:** catch them inside the asynchronous apply operation; keep the persisted
  semantic values and the last usable surface, and release the position/update guards;
  log only when `ApplicationInfo.FLAG_DEBUGGABLE` is set. No rollback cascade and no
  automatic retry.

### 4. Shared UI and accessibility

- `ui/reader/AdvancedReadingControls.kt` uses full-width discrete picker rows (label
  plus semantic value, options in a chooser) and a Simplify typography toggle. Group
  order, labels, descriptions, dialogs and snackbar text are authored in commit 5 under
  the "UI copy" rules below.
- **Settings:** Advanced Reading sits under Reading defaults, with "Show extra typography
  and page-layout controls." When the controls are hidden but customized, show "Advanced
  reading settings are customized" — reads stored JSON only, never EPUB resources.
  Include the note about the preview (C9).
- **Reader Display:** the Advanced section is collapsible and only shown when enabled.
  Its collapse state lives in session memory and resets for a new book. Basic rows that
  Readium disables keep their values and show a reason.
- **Restore actions:** restore buttons are disabled when there is nothing to restore. A
  confirmation precedes one full replacement; messages go through `vm.toast`.
- **Components and accessibility:** reuse `QButton`, `SheetHost`, `Toggle`, `Kicker` and
  `ReadingControls`; add optional disabled support without changing default styling.
  Touch targets at least 48dp, with selected/disabled semantics and a
  `stateDescription`. Disabled reasons are announced. Mapped numbers are never shown;
  colour is never the only signal.

### UI copy (authored in commit 5)

There is no external copy deck: commit 5 authors every string, under these constraints.

- **Fixed strings (verbatim from this plan):** "Show extra typography and page-layout
  controls.", "Advanced reading settings are customized", "Not available for this book",
  the disabled-reason categories (book typography / mode / theme / writing system), and
  the existing sheet conventions — `Kicker` group titles, title-case labels,
  sentence-case descriptions, `vm.toast` snackbars.
- **Option labels come from the mapper:** paragraphs Default / Traditional / Screen (plus
  the read-only Custom status); indent and paragraph spacing Default / None / Small /
  Medium / Large; letter and word spacing Default / Slight / Wider / Very Wide / Max;
  weight Default / Light / Regular / Medium / Heavy; hyphenation and ligatures Auto /
  On / Off (factory hyphenation On); vertical text Automatic / Horizontal / Vertical;
  typography source Quire typography / Book typography; reading direction Automatic /
  Left to right / Right to left; images Original / Darken / Invert; page layout Auto /
  Single page / Two pages.
- **Theme names are Quire's** — Sepia, Paper, Night, AMOLED Black (C10), never "Light".
- **The Settings copy notes** the live preview is a Compose `Text` and does not show
  advanced options (C9).
- **The three acceptance scenarios** are part of this design record and asserted by the
  commit-5 UI instrumentation:
  1. *First use* — Advanced Reading is off; enabling it in Settings adds the Advanced
     section to the reader's Display sheet; changing the paragraph preset to Screen
     reflows the text and keeps the reading position; the value survives close/reopen and
     a second book without overrides inherits it.
  2. *Availability* — with Book typography, spacing, hyphenation, line spacing and
     alignment are disabled with reasons and keep their values; Quire typography
     re-enables them; scroll mode disables Page layout; Sepia and Paper disable image
     controls.
  3. *Restore* — the global and book restore actions each confirm, then restore exactly
     the advanced group (book restore leaves basic overrides untouched); both buttons
     are disabled when there is nothing to restore.

## Commit plan (single PR)

| # | Commit | Contents | Gate |
|---|--------|----------|------|
| 1 | Apply pipeline | `ApplyPreferences` event, CSS-delta reflow, captured-locator invalidation, `remeasureAllFrames()` target generation, suspend `restoreAnchor`, serialized remeasures, internal reflow/invalidation counters (C3, C4) | + existing `ReaderThemeChangeTest`, `ReaderBoundedScrollTest`, `ReaderContinuousScrollTest`, `ReaderTargetTest` |
| 2 | Model and storage | `AdvancedReaderPrefs`, `BookReaderPrefs` with optional basic group (C1), reducers with `Mutex`, visibility flag, snapshot capture/restore/merge (C5) | + JVM: legacy JSON golden, advanced-only edit does not pin basic, snapshot round trips |
| 3 | Mapper | Constants table, pure `toEpubPreferences(layout)`, vendored-editor walk test, frozen legacy `EpubPreferences` golden (C7) | + **upgrade release gate (first run)** |
| 4 | Availability | `ReaderPreferenceContext`, candidate editors (C8), Settings authoring context (C9) | + real-editor instrumentation |
| 5 | UI | Advanced controls in Settings and Reader, authored copy, disabled basic rows, restore dialogs, accessibility | + UI/accessibility instrumentation (the three acceptance scenarios) |
| 6 | Docs and verification | README feature bullet + tech note (scroll-surface correction dropped), verification record appended to this plan, upgrade evidence in `docs/screenshots/` | + **upgrade release gate (final run)**, full isolated suite |

Commit 1 changes existing behaviour by itself: font-size and theme reflows go through the
new path, which also fixes the scroll restore race. Note this in the PR description.
Point reviewers to commits 1 and 2 as the high-risk ones.

## Files to modify

Paths relative to `app/src/main/java/com/quire/reader/` unless stated otherwise.

- **Data:** `data/ReaderPrefs.kt`; new `data/BookReaderPrefs.kt`; `data/SettingsStore.kt`;
  `data/LibraryRepository.kt` (`readerPrefs`, `hasBookOverride`, `setBookPrefs`,
  `useForAllBooks`); `data/db/Daos.kt` (observe stored advanced customization; no schema
  change).
- **Backup:** `data/backup/UserDataSnapshot.kt`, `data/backup/SnapshotMerge.kt`,
  `data/backup/SnapshotWriter.kt`.
- **Reader:** `reader/PrefsMapper.kt`; new `reader/ReaderPreferenceContext.kt`;
  `reader/ReaderSession.kt`; `reader/EpubHost.kt`.
- **Navigator:** `navigator/epub/EpubNavigatorViewModel.kt`, `EpubNavigatorFragment.kt`,
  `ContinuousBookWebView.kt`. Touch `navigator/pager/R2EpubPageFragment.kt` only if
  paged layout completion needs a hook.
- **UI:** `ui/QuireViewModel.kt`; `ui/reader/ReaderScreen.kt`, `ReaderSheets.kt`; new
  `ui/reader/AdvancedReadingControls.kt`; `ui/settings/SettingsScreen.kt`;
  `ui/Components.kt` (disabled/semantics support only).
- **Tests:** `app/src/test/.../reader/ReaderLogicTest.kt`; new mapper, override and
  reducer tests; `app/src/test/.../data/backup/SnapshotCodecTest.kt`;
  `app/src/test/resources/reader-prefs/pre-advanced.json`;
  `app/src/androidTest/.../data/index/EpubFixtures.kt`; new real-editor, inheritance, UI
  and reflow tests.
- **Docs:** `README.md` (feature bullet + tech note); `plans/advanced-reading-controls.md`
  (verification record appended); `docs/screenshots/` (upgrade-gate evidence).

**Out of scope:** `assets/quire/continuous-scroll.js`; the vendored
`EpubPreferencesEditor.kt`; runtime range walking; RTL/CJK authoring contexts;
language-group visibility logic; the `openspec/` directory (untouched — this change does
not use OpenSpec).

## Reuse

- `EpubNavigatorFactory.createPreferencesEditor(currentPreferences)`
  (`navigator/epub/EpubNavigatorFactory.kt:85`) — availability contexts; no new editor
  machinery.
- The vendored editor's preference delegates (`navigator/epub/EpubPreferencesEditor.kt`)
  — candidate editors are plain instances of it (C8); its literal ranges back the C7
  constants table.
- `LibraryRepository` per-book flow/dao plumbing (`readerPrefs`, `setBookPrefs`,
  `clearBookPrefs`, `useForAllBooks`) — reworked, not duplicated; `StateDao.edit`
  `@Transaction` for atomic per-book writes.
- `SettingsStore` DataStore pattern (key + flow + setter + `capture`/`applySettings`)
  — the `advanced_reading_enabled` key and snapshot field follow it.
- `SnapshotCodec`/`SnapshotMerge` — add fields; `ignoreUnknownKeys` already covers old
  snapshots.
- `ContinuousBookWebView.captureAnchor` / `restoreAnchor` / `awaitLayoutGeneration` /
  `remeasureAllFrames` (C3 reworks these; the fragment's
  `reflowContinuousSurface` call site is the only caller today).
- UI building blocks: `QButton`, `SheetHost`, `Toggle`, `Kicker`, `ReadingControls`
  (`ui/Components.kt`, `ui/reader/ReaderSheets.kt:119`), `vm.toast`,
  `SettingsScreen.Preview`, the `DisplaySheet` sheet pattern.
- `ReaderTheme` names (Sepia/Paper/Night/AMOLED) for C10 copy.

## Steps

- [x] **Commit 1 — Apply pipeline.** `Event.ApplyPreferences(script, needsInvalidation)`
      in `EpubNavigatorViewModel`; single serialized handler in the fragment (capture →
      script → invalidate-or-reflow → restore once); `remeasureAllFrames()` returns its
      target generation before posting; `restoreAnchor` becomes suspend and waits for the
      target; serialized remeasures; internal counters. Gate: `assembleDebug test lint`
      + existing theme, bounded-scroll, continuous-scroll and target instrumentation.
- [x] **Commit 2 — Model and storage.** `AdvancedReaderPrefs` (+ enums,
      `ParagraphPreset`) inside `ReaderPrefs`; `BookReaderPrefs` with optional basic group
      (C1); `advanced_reading_enabled` key + flow + setter; Mutex-guarded reducers;
      snapshot capture/restore/merge (C5). Gate + legacy-JSON golden, no-basic-pinning,
      snapshot round trips.
- [x] **Commit 3 — Mapper.** Constants table; pure `toEpubPreferences(layout)`;
      vendored-editor walk test; frozen legacy `EpubPreferences` golden. Gate +
      **upgrade release gate (first run)**.
- [x] **Commit 4 — Availability.** `ReaderPreferenceContext`; candidate editors (C8);
      Settings neutral context (C9); remove group-visibility logic (C2). Gate +
      real-editor instrumentation.
- [x] **Commit 5 — UI.** `AdvancedReadingControls.kt` and the authored copy; Settings
      entry + visibility note; Reader Display advanced section; disabled basic rows with
      reasons; restore dialogs; accessibility. Gate + UI/accessibility instrumentation.
- [ ] **Commit 6 — Docs and verification.** README advanced-reading feature bullet and
      tech note (the scroll surface is already correctly described); verification record
      with upgrade evidence appended to this plan. Gate + **upgrade release gate (final
      run)**, full isolated suite.

## Verification

### Automated

- **JVM:** JSON round trips, unknown nested keys, the real pre-feature JSON
  (`pre-advanced.json`); advanced-only book JSON resolves basic settings from the
  globals; every enum/null mapping, range boundaries and tie snapping, all paragraph
  preset combinations; constants table matches the vendored editor's five advanced
  range rows; the factory mapping equals the frozen legacy golden (not a second call
  through the new mapper).
- **Reducers and atomicity:** one write and one submission per accepted change; zero
  submissions for visibility, collapse, dialog or no-op actions; rapid changes use the
  latest object; echoes are not replayed; a failure releases the reader for the next
  action.
- **Real editor on device:** book typography disables spacing, hyphenation, line
  spacing and alignment, Quire typography restores them; null-valued rows can be changed
  without any probe submission; scroll disables Page layout for reflowable books; Sepia
  and Paper disable image controls, dark themes re-enable them; fixed-layout spread
  mapping works.
- **Fixtures:** `EpubFixtures` generates LTR prose, publisher-heavy, RTL, CJK,
  fixed-layout and image-heavy EPUBs deterministically.
- **Rendering and position:** Pages — the saved locator matches the visible passage after
  a reflow; Scroll — the same viewport-top DOM anchor and offset, including deep in books
  outside the live window; counters show one reflow for an effective change and zero for
  a no-op; covers both restore actions and rapid changes.
- **Persistence and backup:** global and book values survive a restart; inheritance
  follows later global edits; global restore leaves book overrides alone; old and new
  snapshots capture, restore, import and merge correctly; toggling visibility leaves
  `ReaderPrefs`, rendering and locator unchanged.
- **UI:** the three acceptance scenarios under "UI copy"; cancel writes nothing; restore buttons
  disabled when there is nothing to restore; messages match the copy exactly; collapse
  state lasts for the session; narrow screens and large fonts lay out correctly;
  TalkBack reads values and disabled reasons; touch targets at least 48dp.
- **Regressions:** existing theme, bounded/continuous scroll, exact search, selection and
  backup suites.

### Commands

```sh
zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug test lint assembleRelease'
zsh -fc 'source ~/.config/android/env.zsh; ./gradlew --init-script tools/dbtest-suffix.init.gradle assembleDebug assembleDebugAndroidTest'
# Disposable emulator only; grant MANAGE_EXTERNAL_STORAGE to com.quire.reader.dbtest first.
zsh -fc 'source ~/.config/android/env.zsh; ./gradlew --init-script tools/dbtest-suffix.init.gradle connectedDebugAndroidTest'
```

### Upgrade release gate

1. **Before any code edits**, keep the APK built from `3f72219` (the new baseline) and
   record the evidence: actual stored defaults and book JSON, and for one book per mode
   (Pages and Scroll) the locators, page geometry and screenshots of the reading area.
   Configure several non-factory basic combinations, on books both with and without
   overrides.
2. **On the same installation**, install the new APK with the same application ID and
   signature, without clearing or uninstalling. Relaunch without opening Advanced.
3. **Require** identical mapped preferences, typography, mode, visible passage, geometry
   and locator. Advanced stays off by default.
4. Run the gate after **commit 3** and again at the **end of the PR**.
5. Append the evidence to the "Verification record" section of this plan file; put the
   screenshots in `docs/screenshots/`. Any unexplained drift blocks the merge.

## Resolved during planning

1. **No Type scale row** (C8). The vendored editor's `typeScale` is null-gated but gets
   no UI row, no mapper entry and no constants-table entry; the availability machinery
   covers only rows the sheet shows (in practice `fontWeight` and `paragraphSpacing`).
2. **The README scroll-surface correction is dropped.** README.md already describes the
   bounded live window (`151368f` updated it); commit 6's README work is the
   advanced-reading feature bullet and tech note only.
3. **UI copy is authored in commit 5.** There is no external copy deck; commit 5 writes
   group titles, labels, descriptions, dialogs and snackbar messages under the "UI copy"
   rules above.
4. **No OpenSpec.** This plan file is the design record (anchoring requirement,
   acceptance scenarios, verification record); the PR has six commits; `openspec/` is
   untouched.

## Verification record

Verified 2026-10-07. Every commit built and passed `assembleDebug test lint`; each
gate ran on the disposable Android 16 emulator (`Android_API_36`).

**Commits 1–4 (per-commit gates).** Commit 1: full gate plus the four-class
instrumented regression suite (26/26 after a first-boot warmup rerun). Commit 2:
full gate plus a sanity instrumented run. Commit 3: full gate plus
`assembleDebugAndroidTest`; the vendored-editor walk test and the frozen legacy
golden pass on the JVM (`testOptions { unitTests.isReturnDefaultValues = true }`
was added so Readium's `Theme` static initializer works off-device). Commit 4:
full gate plus the new `ReaderAvailabilityTest` scenarios and the no-probe
invariant.

**First upgrade gate (after commit 3).** Baseline APK built from `3f72219`
(pre-feature) in a worktree, installed fresh on a wiped emulator. Two fixture
books (`gate-paged.epub`, `gate-scroll.epub`) in `/sdcard/Books/quire-gate`;
globals Night / Literata / 19 / 1.7 / 26 / Justify / Scroll; the paged book
overridden Paper / 22 / Left / Paged. Navigated to 0.35 (paged) and 0.6 (scroll),
positions saved on close. Evidence stored in `/sdcard/quire-gate/evidence.json`
with PixelCopy screenshots. The new APK was then installed over the same
installation (`adb install -r`) and `verifyAfterUpgrade` passed: stored
preferences JSON byte-identical (the new serializer's factory `advanced` group
is stripped before comparing), DataStore `reader_defaults` intact,
`advancedReadingEnabled` false by default, locators equal (progression tolerance
0.002), the scroll anchor within 8 px, positions exact (2/6 and 4/6), and the
mapped `EpubPreferences` strings identical (Paper → LIGHT / 1.375 / START;
Night → DARK / 1.1875 / JUSTIFY / `scroll=true`).

**Commit 5 instrumentation.** Component suite `AdvancedControlsComponentTest`
(5 tests: rows and values render, the chooser applies and closes, exactly three
paragraph presets are offered, disabled rows show their reason and keep their
value, stateDescription semantics present) and the state-level acceptance
scenarios `AdvancedControlsUiTest` (3 tests: first use — enable, open the
section, Screen preset keeps the position within 0.02 and survives close/reopen
and is inherited by an override-free book; availability — Book typography, scroll
and Sepia each grey out their rows with the reason while values persist;
restore — confirmation dialogs, cancel writes nothing, book restore replaces
only the advanced group and keeps the basics). Full-reader Compose-rule tests
are structurally impossible here (the rule's idling stalls the reader's
readiness barrier), hence the component + state-level split.

**Final gates.** `assembleDebug test lint assembleRelease` passed on the
finished tree. Final upgrade gate run again after the tree was rebased onto
`main` (the backup feature's `68a3a77` had landed mid-development; only
`SettingsStore.kt` needed conflict resolution — the advanced key moved inside
the companion that gained the backup keys): baseline capture, in-place upgrade,
and `verifyAfterUpgrade` all passed with no drift, and the before/after
screenshots are byte-identical per pair (`docs/screenshots/upgrade-gate-{
paged,scroll}-{before,after}.png`, md5 `c7704f30…` / `f500a26c…`). Evidence:
paged book `{"theme":"Paper","font":0,"fontSize":22,"lineHeight":1.7,"margin":26,
"align":"Left","mode":"Paged"}` at `OEBPS/c1.xhtml` 2/6, anchor pager position 1;
scroll book defaults at `OEBPS/c3.xhtml` 4/6, anchor `within` 22.8574. Post-rebase
instrumented suite: `ReaderThemeChangeTest` (2), `ReaderBoundedScrollTest` (6),
`ReaderContinuousScrollTest` (7), `ReaderTargetTest` (11),
`ReaderAvailabilityTest` (3), `AdvancedControlsComponentTest` (5),
`AdvancedControlsUiTest` (3) — 37/37 OK.