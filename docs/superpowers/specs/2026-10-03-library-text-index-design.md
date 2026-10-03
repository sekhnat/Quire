# Library-wide book text search

## Purpose and scope

Quire readers with 1,000+ local EPUBs need to find a remembered line without opening every book for each query. Build a persistent, incremental full-text index; keep metadata search; group matching passages by book; open a selected passage with its match underlined.

This design refines `PLAN_INDEXING.md` and records the decisions approved during brainstorming. The original decisions remain: background indexing, a scope switch in the existing library search field, and grouped book results with chapter-labelled snippets. This specification must be reviewed before an implementation plan is written. Product implementation requires review of that plan and selection of its execution method.

Out of scope: remote search, uploads, OCR for image-only books, DRM removal, dependency upgrades, replacing ordinary in-book search, and unrelated library refactoring. All indexed content remains local. Readium stays at 3.3.0 and Coil at 3.5.0.

### Success criteria

- Newly scanned or imported books become searchable without indexing unchanged books again.
- Queries use the index rather than opening EPUBs. Measured database query latency targets less than 100 ms on the 1,500-book fixture, excluding the input debounce. Very common queries meet this only through the counting cap (see Query semantics); cold first runs of mid-frequency queries may exceed it and are reported with cold/warm conditions identified.
- Results identify the book, chapter, and matching text. Selecting a snippet navigates to the corresponding source text and underlines it.
- Deleted and changed books do not expose obsolete results. Existing metadata, collections, settings, and saved reading positions survive the database migration.
- Indexing survives process interruption, respects its settings, and pauses while a reader session is opening or open.
- Partial coverage, failures, waiting, and disabled states are visible rather than represented as complete indexing.

## Existing integration points

The current Room database is version 1 with schema export disabled. Books already contain `mtime` and `sizeBytes`; child entities already use cascading foreign keys. `BookDao.save` updates existing books rather than replacing them, preserving children. Keep that pattern.

`LibraryScanner` already serializes scans, compares file signatures, exposes progress, and closes loaded publications. `PublicationLoader.open` returns a publication whose lifetime belongs to the caller. `LibraryRepository.rescan` delegates to the scanner; imports also invoke scanning and must participate in indexing scheduling.

The six-hour `ScanWorker` is controlled by the watch-new-books setting. It is not the indexing scheduler: text indexing must work when folder watching is disabled, and charging restrictions must not postpone metadata scans.

The library metadata search also matches series and tags. Its label may say “Titles & authors,” but its existing semantics remain unchanged. Existing author/series/tag navigation and status filters also remain available.

`QuireViewModel` currently restores a saved locator when opening a book. Ordinary reader search passes raw text into Readium, debounces by 250 ms, and receives precise hit locators. `ReaderSession.go` currently does not expose navigation success to its caller. A chunk's first locator and a formatted FTS snippet alone are insufficient to identify a later matching element or prove successful navigation.

The source plan records FTS4 with `unicode61`, and no FTS5, on the API 36 emulator. This design review did not rerun that observation. The implementation probe must verify the chosen Room FTS4 representation and queries, not attempt to introduce FTS5.

## Architecture

Use one Room database, migrated to version 2, with external-content FTS4. One application-owned `LibraryIndexer` performs extraction and per-book publication of results. A separate `IndexWorker` schedules bounded background runs through a unique sequential WorkManager chain. Foreground and background callers share the same indexer and serialization boundary.

Data flow:

1. Startup, a completed scan, or import requests indexing work.
2. The indexer selects eligible books by current signature, newest-added first.
3. It opens a publication, reads content elements, constructs mapped chunks, and closes the publication.
4. It rechecks the file and database signature before atomically replacing that book's chunks and recording its terminal state.
5. Database observations update coverage and search results. Debounced searches query FTS4 and join current book metadata.
6. Selecting a result opens the reader with a signature-checked initial target. The attached navigator navigates and applies the exact decoration, or reports the progression fallback.

### Storage

Add these logical tables:

- `text_chunk`: integer primary key compatible with the FTS rowid; cascading `bookId` foreign key; ordered chunk identity; chapter label; EPUB resource href; canonical source token range; primary-text end offset; searchable text including boundary context; source-element mapping; and nearest known publication progression.
- `text_chunk_fts`: Room `@Fts4`, `unicode61`, external content from `text_chunk`. Persist chunk text in the content table, not a second ordinary text column elsewhere. Maintain insertion, update, and deletion synchronization, including cascading book deletion.
- `index_state`: one cascading row per book with the attempted signature (`mtime`, `sizeBytes`), terminal status, completion time, canonical chunk count, and truncated-coverage flag. Successful state records the signature actually indexed, not merely the latest scan timestamp.

The source-element mapping associates UTF-8 ranges in the stored chunk with each contributing element's locator and its source-text range. It must also cover appended boundary context. Retain enough source context to construct `Locator.text.before`, `highlight`, and `after` using original text rather than normalized search terms. Convert SQLite UTF-8 match offsets to source/Compose text offsets explicitly; never treat byte offsets as Kotlin character indexes.

Chunk identities and source ranges distinguish primary content from repeated context. Result counting and pagination operate on canonical owning passages, not duplicate context copies. Snippets include the chapter of the resolved source match.

No missing, pending, or stale book is represented as successfully indexed. Progress and transient activity are observations; cancellation is not a durable failure status.

### Migration

Provide `Migration(1, 2)` with the new tables, foreign keys, indexes, FTS virtual table, and required external-content synchronization triggers. Keep schema export off. Do not use destructive migration or rebuild the existing book tables.

Validate on Android SQLite using a file-backed v1 database populated with books and representative dependent reading/library data. Open it through the v2 migration, verify all seeded data, then exercise FTS insert/update/delete and book deletion. Plain JVM in-memory Room is not proof of Android SQLite migration compatibility.

## Extraction and chunk boundaries

Use Readium's experimental `publication.content()` iterator, with the required API opt-in. Extract original text from paragraphs/headings and carry their locators into the chunker. No custom HTML stripping is the primary implementation.

Phase 0 found that Readium 3.3.0 yields every element with a body role and exactly one segment, so heading starts are detected from the element locator's `cssSelector` (`h1`–`h6`), and chapter labels come from the table of contents. `Content.Iterator` has no `close()`; the publication is what must be closed. Element text is whitespace-normalised while `locator.text.highlight` is raw DOM text, so `Locator.Text` is built from stored raw text.

`TextChunker` is a pure transformation from source elements and their mapping metadata to chunks. Target primary chunks of roughly 600–900 Unicode characters, merging adjacent small elements. Headings start a new primary chunk; a long element may span multiple chunks while preserving its source ranges. Do not split an indexed token solely to meet the character target.

A phrase must not disappear because its first token lies at the end of a primary chunk. Append up to 63 subsequent indexed tokens as searchable context, spanning as many following chunks/elements as necessary within the same EPUB resource. The query limit is 64 indexed tokens in total, so every supported consecutive phrase beginning in primary text can fit in that row. Do not manufacture a phrase across different EPUB resources. Case, punctuation, diacritics, and token boundaries follow the selected `unicode61` configuration; this is token phrase matching, not byte-for-byte punctuation matching.

Ownership follows the source position of the query's first required matched sequence: a quoted phrase contributes its first token, and an unquoted term contributes its own occurrence. Use the earliest required sequence in a complete matched passage. Discard overlap-only representations of that match; map surviving results to their canonical owning chunk. The overlap cannot inflate passage counts or repeat the same source match on later pages.

Query token accounting and overlap construction must agree with the indexed tokenization. Phase 0 must demonstrate that agreement for supported Unicode, punctuation, and phrase boundaries before this representation is adopted permanently.

Bound each book's persisted chunk text plus serialized locator-mapping payload to 6 MiB, counting UTF-8 bytes and repeated context. Stop at a complete, valid chunk/token boundary before exceeding the cap; mark the book truncated. The cap is not a promise that SQLite/FTS overhead itself fits in 6 MiB. Truncated books expose the retained searchable coverage, and the UI never claims full-book coverage.

Always close the content iterator and publication, including errors and cancellation. Cancellation must propagate rather than be swallowed by per-book error handling.

## Eligibility, transactions, and background scheduling

A book needs work when it has no terminal index state or its current `(mtime, sizeBytes)` differs from the attempted signature. Unchanged successful, failed, and no-text books are not repeatedly processed. A rebuild clears these terminal decisions and queues current books again.

Terminal outcomes:

| Outcome | State and next action |
|---|---|
| Text indexed | `done`, with signature, count, and possible truncated flag |
| Publication unreadable or DRM-protected | Books the scanner or a reader open attempt has marked unreadable (`readable=0`) are not eligible: they are never queued, have no index state, and are excluded from coverage counts. `failed` is recorded only when a book the scanner could read cannot be opened or iterated by the indexer; it is retried only after signature change or rebuild |
| No extractable text | `skipped`; retry only after signature change or rebuild |
| Reader pause, cancellation, or process interruption | Work remains eligible; no failed/skipped terminal write |
| Storage permission unavailable | Queue blocked globally; request access rather than failing every book |
| Book changed or disappeared during extraction | Discard extracted output; reconsider current book state |

Known limits of the extraction source (found in verification): Readium swallows per-resource read errors, so a book with some unreadable chapters is indexed from whatever Readium yields and is recorded `done` (a book whose every chapter is unreadable becomes `skipped`, not `failed`); and a resource whose head contains a self-closing `<title/>` is parsed by Readium's HTML-mode jsoup with the whole body inside the title, so such a book is largely missing from the index while still recorded `done`. Neither is detected or surfaced as partial coverage; both are documented limitations, and custom extraction is the alternative to discuss if they matter.

For each completed book, perform old-chunk deletion, new chunk insertion, and terminal-state update in one transaction. A terminal failed/skipped outcome also removes any obsolete chunks in its state-update transaction. Before publishing any terminal outcome, compare the signature read during extraction against both the current file and current database book record. Do not publish against an obsolete signature or recreate a removed book. Retain old rows until replacement commits, but search joins include only `done` states whose indexed signature matches the scanned book signature. A failed new attempt must never make old chunks appear current.

Use one mutex/shared serialized execution path and low-priority background work. Yield between books and check cancellation/reader pause while consuming content. Do not run competing extraction loops from startup, import, and WorkManager.

Settings defaults: indexing enabled; charging-only disabled. Enabling charging-only applies to all indexing work, including incremental updates. Indexing remains independent of watch-new-books. Startup enqueues eligible work even if folder watching is off.

WorkManager runs must be bounded rather than requiring a 1,500-book first pass to finish inside one worker lifetime: finish/checkpoint within a five-minute batch and append a continuation if eligible work remains. Appending triggers and continuations to the unique sequential chain avoids losing an import/scan request while an earlier worker is finishing. Empty batches exit without extraction. Charging constraints apply to each request. Setting changes cancel obsolete requests and schedule with the current policy.

Mark the reader busy before publication loading begins, not only after the reader screen appears. A pause abandons the unfinished book buffer without publishing it; that book remains eligible. A worker encountering an open reader exits instead of waiting indefinitely or creating a busy retry loop. Reader close or opening failure clears the busy state and schedules pending work. Process death cannot leave a durable reader-busy flag.

Disabling indexing cancels pending/in-flight work at the same serialization boundary and retains the current index. Retained indexed books remain searchable; coverage explains why new/changed books are not being added. Rebuild and “Turn off and delete index” cancel work before modifying search tables, so a finishing extraction cannot resurrect deleted rows. Rebuild preserves all non-search data and resumes under the current enabled/charging/reader policy. Delete disables indexing and clears only index tables/state.

## Query semantics and result contract

`FtsQuery` is pure: user input becomes either a safe structured query/MATCH expression, an empty/too-short query, or a clear limit outcome. Never interpolate raw user input into MATCH syntax.

- Input shorter than two Unicode characters does not search. Punctuation-only input does not search.
- Unquoted words are ANDed within a searchable passage. Only the final query segment supports prefix matching, and only when that segment is unquoted; all earlier terms are complete tokens. A query ending in a quoted phrase has no automatically prefixed term.
- **Common-prefix guard (Phase 4 finding).** FTS4 merges the doclists of every term sharing a prefix before returning any row, so the counting cap cannot bound a prefix query (`the*` measured 217 ms warm on 505k chunks, `th*` 445 ms). Before running a library query whose final segment is a prefix, check that prefix's frequency with `fts4aux`. If it is too common, search the segment as the exact word instead and tell the user (“showing exact matches — keep typing for prefix matches”). The frequency is the number of chunks holding a term with that prefix, read from `fts4aux` (a view over the index, created when the database opens) page by page in term order, stopping once the threshold is passed; the threshold is tuned during scale verification. The result records the downgraded word so the UI can say so, even when the exact search finds nothing. The guard applies to library-wide queries; single-book paging may still use the prefix.
- Closed quoted sequences are consecutive-token phrases, without automatic prefix expansion. Multiple phrases/terms are ANDed.
- During typing, an unmatched opening quote treats the remainder as an unfinished phrase, safely and without an SQL exception; it becomes an ordinary complete phrase when closed.
- Characters such as `-`, `:`, and `*` are not user-accessible FTS operators. FTS operator-looking words are treated as text. Quotes express phrases and must not all be stripped.
- At most 64 indexed tokens are supported. Over-limit input shows a clear message and is never silently truncated.
- `unicode61` does not supply CJK word segmentation. Document that limitation; do not claim language-independent word/prefix behaviour.

Use bound MATCH arguments. Debounce library text input by approximately 250 ms; cancel superseded query work and never publish an older query result over the current one. Keep library query state distinct from ordinary reader query state, reusing the existing cancellation/debounce pattern rather than coupling their values.

Apply active library collection/author/series/tag/status filters to text results, but do not additionally apply the metadata substring query to them. Metadata mode retains `visibleBooks()` semantics unchanged.

`LibraryRepository.searchText` exposes reactive grouped results for the query and active filters. Each book result has current metadata, a canonical matching-passage count, coverage information, and mapped snippets. Rank by passage count descending, then recently opened descending, then book identity for a stable tie. Limit library results to 40 books; make the cap visible when more books match. Initially show up to three snippets per book, expandable to five, and provide “Show all in this book.” Counts describe matching passages, not occurrence totals.

**Counting cap (Phase 0 decision).** Ownership-correct counting uses `offsets()`, which Phase 0 measured at about 4.7 µs per matched row; exact library-wide counts for very common queries (`the`, `th*`, `"of the"`) took 16–27 s on 1.17M chunks. The grouped query therefore examines at most a fixed number of matching passages per query (initial value in the low thousands, tuned during scale verification against the 100 ms target). When the cap is reached, counts are shown as “N+”, the result list states that the query is very common, and the ranking is explicitly approximate. Rare and mid-frequency queries stay exact. The cap biases toward whichever passages the engine visits first; this bias is documented, not hidden. “Show all in this book” pages a single book with its own bounded query and is not affected by the library-wide cap.

**Filters and common words.** Filters are applied inside the search so they never shrink the examined sample. A narrow filter (about 40 books or fewer) is searched book by book inside each book's chunk range, which is exact. A broad filter on a query with few matches is scanned in full. A broad filter on a common word cannot be scanned past the books it rejects (each rejected match costs about 6 µs), so the first few allowed books are searched individually, which fills the cap for a common word; if that does not fill it, the result is flagged incomplete (books may be missing) and an empty incomplete result is never presented as “no matches”.

Use FTS4 `snippet()`/match offsets to produce structured highlighted excerpts. Render text and spans with `AnnotatedString`; never render snippet text as executable HTML. Database grouping/counts must not require opening publications or loading all chunk text into application memory.

## Reader navigation and scoped search

A snippet target carries book identity, indexed file signature, source mapping/locator, matched original text, and progression. Validate that the selected hit still belongs to the current book signature. If stale, do not silently open an obsolete text target; explain that the book changed and queue a refresh.

Allow `read` to receive an initial target instead of the saved locator. Ordinary opening still restores the saved position. An explicit search target takes precedence only for that opening, and subsequent reading-position persistence follows the existing reader-close path.

After navigator attachment, navigate to the exact locator and apply the match decoration using the existing `applySearchHits` mechanism. Navigation must expose an observable success/failure outcome instead of treating the current Unit-returning wrapper as evidence of arrival. Phase 0 showed the vendored navigator's `go()` already returns a `Boolean` but returns `true` for absent text, so success is read from the applied decoration (`window.readium.getDecorations('search').items.length` via `evaluateJavascript`). `ReaderSession.go` becomes a suspending function that returns an outcome and runs the progression fallback. Open the book with the target as `initialLocator` (or wait for the first page load before `go()`) so the saved position and footer are not left stale.

If an otherwise current target cannot resolve, navigate to the nearest stored publication progression and show that the exact passage was unavailable. Do not underline unrelated text at the fallback position or pretend exact navigation succeeded. A known file change instead takes the stale-result path above.

“Show all in this book” opens the existing reader overlay in an explicitly labelled library-search mode. It keeps the same FTS query semantics and restricts results to the chosen book, with stable pagination beyond the library's five-snippet cap. Use the same mapped targets/highlights and show truncated-index coverage when applicable. Editing the query in that overlay retains library-search semantics until the mode is closed. Ordinary reader-opened Readium search remains unchanged and searches the publication through its existing iterator. Do not pass FTS syntax directly to Readium and claim equivalent results.

## UI and Settings

Reuse Nocturne tokens and existing `Segmented`, `ListCover`, `QText`, `Kicker`, `ProgressLine`, and search-overlay highlighting patterns. No new visual system or speculative layout work is needed.

When library search is open, show “Titles & authors” and “Inside books” scopes. Text mode uses a lazy list of grouped book cards with cover/title/author, matching-passage count, chapter-labelled highlighted snippets, expansion, and reader actions.

Coverage/progress observations include total eligible library books, searchable books, failed/skipped/truncated counts, whether work is active, and its blocked/paused reason. Distinguish initial/no-index, indexing, reader pause, charging wait, indexing disabled, permission missing, query too short/empty, over-limit query, and genuine no-match states. Progress completion is not synonymous with every book being searchable. Coverage remains visible alongside partial results.

Settings “Library search” includes:

- Indexing enabled switch, default on.
- “Index only while charging” switch, default off.
- Searchable/total books and failed/skipped/truncated coverage.
- Total database disk usage, including current WAL, labelled as database storage, not exact index-only storage.
- Persisted indexed-text bytes, labelled separately from physical database storage and locator/FTS overhead.
- Rebuild index, available while indexing is enabled and still subject to charging/reader policy.
- A separate, explicitly confirmed “Turn off and delete index” action; it does not delete book files, metadata, or saved reading state.

Sharing one SQLite database prevents using its file size as an exact index-only footprint without additional page attribution. This design deliberately reports honest total database storage and indexed-text bytes instead. Scale verification separately measures the database growth attributable to a controlled indexing run.

## Phase 0: mandatory go/no-go probe

Only after the written spec and implementation plan are approved, use throwaway probe code and one real EPUB on the API 36 emulator to establish:

1. `publication.content()` yields readable paragraphs/headings and useful locators, with measured extraction time for a typical novel targeting a couple of seconds.
2. The proposed external-content Room FTS4 entities build with the pinned AGP/KSP stack; bound prefix/phrase MATCH, snippets, offsets, and synchronization work.
3. Token accounting agrees with `unicode61` for punctuation, accents, non-ASCII text, and the 64-token limit. UTF-8 offsets map to original text and Compose spans correctly.
4. A phrase at a chunk boundary is returned once. Test a phrase spanning source elements in one resource, not just a single-paragraph phrase split artificially.
5. A derived target navigates to and underlines the exact text after navigator attachment. Verify a deliberately unresolved target and the progression fallback.

**Outcome (2026-10-03):** gates 1–5 passed with caveats; gate 6 failed for very common queries and was resolved by the counting cap above. Evidence is in `docs/superpowers/specs/2026-10-03-library-text-index-phase0-results.md`, summarised in `PLAN_LIBRARY_TEXT_INDEX.md`. Other findings folded into this spec: the Kotlin tokenizer matched `unicode61` on about 468k tokens but differs on rare characters, so the 63-token context needs a small margin and no hard-coded Unicode table; prefix queries must be written `pre*` (never `"pre"*`); only tokenised, quoted words may enter MATCH; stored data is about 3.5× source text.

Measure and record outcomes; remove throwaway scaffolding after the probe. A failed gate stops permanent index implementation and requires design revision. Custom resource/HTML extraction is a possible alternative to discuss if Readium fails, not an automatic unreviewed fallback.

## Permanent verification

### Behavioural tests

Pure JVM tests cover uncertain boundaries and consumer-visible behaviour: merge/split ownership, headings, empty input, long elements, phrase overlap/deduplication, payload cap/truncation, UTF-8/source offset mapping, safe operators/quotes, prefix semantics, Unicode and token limits, stable grouping/ranking, signature invalidation, and cancellation transitions. Follow existing test conventions; do not add source-text, forwarding, or incidental-default tests.

Android tests exercise the file-backed v1 migration, populated-data survival, FTS triggers, and cascade deletion on the actual SQLite engine. Check that aborting a per-book replacement cannot expose half an index or erase the previous committed state.

### Seven-book end-to-end smoke

Scan the fixture library and reach seven searchable books when all seven are valid textual EPUBs. Search “Pemberley,” “Transylvania,” a quoted phrase, a prefix, and no-match input. Confirm grouping, passage counts, chapter labels, structured highlighting, filter behaviour, and 250 ms input debounce. Quotes, hyphens, stars, colons, unmatched quotes, and over-limit input must not crash.

Tap a snippet and observe the actual reader passage and underline. Exercise “Show all in this book,” paging, query editing in library-search mode, and an ordinary reader search to demonstrate their separate contracts. Verify saved-position behaviour and unresolved-target fallback.

Add a new EPUB and rescan/import: only its eligible index work is performed. Change an existing file's mtime/size: stale hits disappear until replacement. Delete a book: its chunks, FTS hits, and state disappear. Exercise denied/granted all-files access, charging-only on/off, indexing disabled with retained data, rebuild, and confirmed search-index deletion. Verify that reading/history/library data remain intact.

A damaged/truncated EPUB archive is failed; an image-only EPUB is skipped. An otherwise valid book exceeding the payload cap is instead `done` with truncated coverage. Interrupt extraction by opening the reader and by killing the app; resume without duplicate/partial published rows. Observe reader pause during loading/open state and resume after close/failure. No unchanged failed/skipped book is automatically retried.

### Build and scale

Run from the project root using Java 21 through the Zsh environment:

```sh
zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug test lint'
```

Run the Android migration tests with a booted emulator using the Gradle wrapper. Install and launch the actual app and collect screenshots as smoke evidence, not only test output.

Use the 1,500-book fixture from the prior scale work, locating its generator before reuse. Record first-index duration, searchable/failed/skipped/truncated totals, controlled database growth, final database/WAL disk usage, and representative query timings with cold/warm conditions identified. The query target is less than 100 ms excluding debounce. Observe library scrolling while indexing, and opening/using the reader while extraction is paused. Confirm bounded worker continuation and process-interruption recovery on the large queue.

After successful smoke verification, update README feature/architecture/limitations documentation and include an actual app screenshot. Remove probe/benchmark scaffolding; retain only behavioural regression tests and the product implementation.

## Affected code areas

New: `data/index/LibraryIndexer.kt`, `data/index/TextChunker.kt`, `data/index/FtsQuery.kt`, `data/index/IndexWorker.kt`, and `ui/library/TextSearchResults.kt`.

Update: Room entities/DAOs/database migration; `LibraryRepository`; scan completion and import scheduling; `ScanWorker` handoff; `QuireApplication` dependency/scheduling ownership; `SettingsStore`; `QuireState`; `QuireViewModel`; `LibraryScreen`; `SettingsScreen`; reader overlay consumers in `ReaderSheets`; `ReaderSession` navigation outcome and initial target handling; README and appropriate existing/new behavioural tests.

The implementation plan must map all scan/import/open/search consumers before editing. Reuse existing patterns; migrate callers cleanly without compatibility aliases or duplicate execution paths.

## Risks and failure gates

- Experimental Readium extraction, token alignment, and navigation fidelity are blocked by Phase 0 until demonstrated. No claims of verified behaviour precede that probe.
- FTS synchronization and migration need actual Android SQLite coverage, especially cascade deletion and transactional replacement.
- Boundary context and locators increase payload; the per-book cap and visible truncation bound pathological books rather than promising exhaustive indexing of arbitrarily large input.
- A 64-token bound guarantees the selected phrase-overlap scheme only within one text resource. CJK segmentation and punctuation/case semantics must be documented precisely.
- Long initial indexing must use resumable bounded workers; battery use is a user-selected default, not hidden behind a charging-only first-pass exception.
- The shared database's physical size is not an exact index-only measurement. UI labels and scale evidence must preserve that distinction.
