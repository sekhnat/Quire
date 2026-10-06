# User-data backup and restore

Quire keeps positions, statuses, ratings, reader preferences, user tags, bookmarks, highlights and
notes in an exportable snapshot. Neither database travels in Android backups.

## Relationship to PR #4

This feature builds on [search-index-v2, PR #4](https://github.com/sekhnat/Quire/pull/4).
The [backup PR #5](https://github.com/sekhnat/Quire/pull/5) was originally stacked on #4, then
rebased onto `main` after #4 merged. The dependency order is search-index-v2 first, backup second.

PR #4 owns `quire-index.db`, bundled SQLite/FTS5, CJK search, relevance ordering, index clearing and
incremental vacuum, and the library v4→v5 migration with sliced legacy-index cleanup. This change
preserves those implementations. It adds a snapshot DAO without changing the library schema or
version, restore sequencing, exports and separate storage reporting. It does not introduce a
second index database, an alternative v5 migration, or the independent FTS4 split's coordinator.

The portable snapshot includes PR #4's search-order preference. Older schema-v1 snapshots omit
that optional field and keep the local/DataStore value. Index-optimization and cover-backfill flags
are not portable; a clean snapshot restore resets them because those generated files do not travel.

## What travels

`backup_rules.xml` (Android 11) and both sections of `data_extraction_rules.xml` (Android 12+
cloud backup and device transfer) use an include allowlist containing exactly:

| File | Contents |
|---|---|
| `files/backup/user-data.json` | User-authored book data and portable settings |
| `files/datastore/settings.preferences_pb` | App preferences |

Never included: `quire.db`, `quire-index.db`, either database's sidecars, WorkManager databases,
cover thumbnails, or imported EPUBs. Imported book files need separate protection; this feature
backs up reading data, not books.

`SnapshotWriter` captures library rows in a transaction, batches annotation reads and checks that
settings stayed stable. Changes are debounced for 30 seconds; app stop saves the current reading
position before flushing. A write uses a temporary file, fsync and rename, retaining the previous
snapshot on failure. Pending, malformed and unsupported restores block automatic overwrites.
Like other asynchronous stop hooks, flushing is best effort if Android kills the process abruptly.

## Restore and manual recovery

On a clean install `RestoreCoordinator` stages the snapshot in `noBackupFilesDir`, applies portable
settings and overrides backed-up onboarding completion so scanning is not skipped. The first
completed scan imports in one library transaction. Phase markers support interrupted imports;
repeating an import does not duplicate annotations.

Matching uses Calibre UUID, then file fingerprint, then an EPUB identifier with a title check—never
title alone. The strictly newer reading position wins, existing local ratings/preferences are
retained, and conflicting notes are kept as distinct variants. Unmatched entries become missing
books with their data intact, available for normal scan adoption or note export.

Settings offers **Export/Import reading data** using the same JSON. Manual import does not replace
local settings. A book's edit sheet and Missing books rows offer **Export notes** as Markdown with
chapter labels and `quire://` locator comments. New highlights stamp their chapter title; older
highlights use a best-effort TOC-per-resource label, then a resource-name fallback.

## Reproducible verification

From the project root, build the ordinary debug APK (not the `.dbtest` APK), then run:

```sh
zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug'
tools/verify-reading-data-backup.sh all emulator-5580
```

Use a **disposable**, rootable `google_apis` AVD named `quire-gate*`. Seed/reinstall phases uninstall
the gate app. The script always refuses `emulator-5554`; other AVD names need an explicit
`ALLOW_DESTRUCTIVE=1`. It generates three small EPUBs itself, so no copyrighted test books are
required. `FIXTURE_DIR=/path/to/fixtures` can instead supply `MWM.epub`, `ORV.epub`, `TASH.epub`.

Phases: `seed`, `onboard`, `verify-import`, `backup`, `inspect`, `reinstall`, `verify-restore`, `all`.
The local test transport is intentionally unencrypted for inspection. Its full-backup blob lives
under `/data/user/0/com.android.localtransport/files/1/_full/<package>`; inspection checks transported
file keys, not arbitrary strings inside notes.

## Combined-build evidence — 2026-10-06

Tested on disposable `quire-gate-a` (API 36, Google APIs, x86_64) with PR #4's FTS5 engine:

| Check | Result |
|---|---|
| Debug build, unit tests, lint | Pass; **298 unit tests**, no failures |
| Release/R8 build | Pass (build verification, not a phone smoke test) |
| Full instrumentation with all-files access granted to `.dbtest` | **141 tests**, no failures or skips; includes 23 snapshot/restore tests and all four real-worker tests |
| Snapshot import | 4 books, 7 highlights, 3 bookmarks, 7 user tag rows; 3 positions at 42%, 3 ratings of 4, Unicode notes and 1 missing-book tombstone |
| Backup transport | `bmgr backupnow com.quire.reader`: Success |
| Payload | **8,192 bytes**; exactly snapshot and settings keys; no database, cover or EPUB keys |
| Uninstall/reinstall → restore → scan | Identical reading-data counts and preserved tombstone entry key |
| Rebuilt index after restore | Three `done` states, `chunk_fts` uses FTS5, `PRAGMA integrity_check` returns `ok` |

One earlier full instrumentation run failed in the unchanged
`ReaderContinuousScrollTest.relative assets and links resolve against the original resource`
(`relative link resolved to error: no frame`); the next complete run passed all 141 tests.
The ordinary Gradle-connected run before permission setup skipped the four worker tests; the
explicit-install/grant/instrument run above exercised them.

Historical standalone snapshot/FTS4-split gates also passed. Their 28 ms delete→reopen observation
used a small index and excluded time before deletion; it is **not** a full-index reset measurement
for this combined FTS5 build. Storage/clear measurements for the retained engine belong to PR #4's
benchmark record. No new multi-GB compaction measurement is claimed by this backup PR.
