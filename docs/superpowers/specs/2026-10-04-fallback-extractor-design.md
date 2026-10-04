# Design: HTML normalisation and partial-coverage reporting for the text index

Status: APPROVED DESIGN, not implemented. Supersedes `PLAN_FALLBACK_EXTRACTOR.md`; the hand-rolled fallback extractor
described there is not built (see Decisions). Follows the library text index (`2026-10-03-library-text-index-design.md`);
the 1,500-book scale run it waited for is complete (`2026-10-04-library-text-index-scale-results.md`).

## Problem

Verification found two ways a book is recorded `done` and fully searchable while most of its text is missing:

1. **`<title/>` in the head.** Readium's `HtmlResourceContentIterator` parses each resource with `Jsoup.parse` in HTML
   mode, where `<title/>` is not self-closing, so body text is swallowed into the title. jsoup 1.22.2 recovers in documents
   of about 2 KB or less; in larger ones nearly all of it is lost. One real Calibre book (*Juliet Takes a Breath*, one
   450 KB file) parses to a 436,017-character title and 184 characters of body text, and produced a single 184-character
   chunk; with the tag repaired the same file has 1,691 paragraphs and 369,321 characters of body text.
2. **Swallowed read errors.** Readium skips resources it cannot read. A book with 17 of 34 chapters corrupted was published
   `done` with 545 chunks (a clean copy gives 1,059). A book with every chapter corrupted becomes `skipped`, not `failed`.

Goal: books hit by (1) are indexed in full; books hit by (2) are reported as partly indexed (or `failed` when nothing is
readable); healthy books produce the same chunks as before and are no slower.

## Decisions

- **Normalise before Readium, no second extractor.** Readium's `ResourceContentIteratorFactory` is public and receives each
  reading-order resource, so the HTML can be repaired before Readium's own parser sees it. Text, `cssSelector`s,
  progressions and whitespace then come from Readium itself: no parity risk, no thresholds, no buffering. The trade-off is
  that only the known cause is fixed; other misparses are logged as evidence, not recovered.
- **Schema version 3** with an `ALTER TABLE` migration, not an amended `Migration(1,2)`: an existing v2 install would fail
  Room's identity check on launch and could only recover by losing its data.
- **No automatic re-extraction.** Books indexed before the fix keep their old chunks until their file changes; Settings →
  Rebuild re-extracts everything, and the docs say to run it once after upgrading.
- `truncated` keeps meaning only "hit the 6 MiB cap".

## Extraction

New file `data/index/IndexContent.kt`:

- **`normalizeHtml(html: String): String`**, pure. Rewrites the self-closing form of the elements jsoup reads as raw text or
  RCDATA (`title`, `script`, `style`, `textarea`, `xmp`, `iframe`, `noembed`, `noframes`), case-insensitively and with or
  without attributes, as an open/close pair: `<title id="x"/>` → `<title id="x"></title>`. Void elements are untouched.
  Returns the same instance when nothing matched. `ResourceOrder.parse` calls it before `Jsoup.parse`, so chapter anchors
  resolve on repaired resources.
- **`TrackingHtmlFactory : ResourceContentIteratorFactory`**. For each resource it wraps the `Resource` in a
  `TransformingResource` and delegates to `HtmlResourceContentIterator.Factory()`. When the delegate returns null (not
  HTML) nothing is recorded. Otherwise it records a `ResourceTally(index, href, bytes, readFailed, yieldedChars)`. The
  transform:
  - on a failed read sets `readFailed` and returns the failure unchanged (Readium skips the resource, as today);
  - on success decodes UTF-8 (as Readium does), normalises, and re-encodes only if the text changed; otherwise returns the
    original bytes, so a healthy resource reaches Readium byte-identical.

`LibraryIndexer.extract()`:

- Builds `PublicationContentIterator(publication.manifest, container, publication, null, listOf(factory))` instead of
  `publication.content()`. `container` is a small `Container<Resource>` delegating to the public `publication.get(url)`,
  because `Publication.container` is `@InternalReadiumApi`.
- Adds each `TextElement`'s character count to the current resource's tally. Elements still go straight to the chunker.
- At the end: `unreadable` = tallies with `readFailed`; `htmlResources` = number of tallies. Each resource of at least
  2 KB whose yield is under 2% of its bytes is logged at info level under the `LibraryIndexer` tag with the book path and
  href. The log is evidence only; it changes no outcome.
- Resources after a size-cap truncation are never opened, so they have no tally and count as neither unreadable nor sparse.

`IndexPolicy`:

- `Extracted.Text(chunks, unreadableResources = 0)`.
- New pure `extracted(chunks, htmlResources, unreadable): Extracted` returns `Unreadable` when `htmlResources > 0 &&
  unreadable == htmlResources`, else `Text(chunks, unreadable)`.
- `settle()` is unchanged: an all-unreadable book now becomes `failed`; a readable book with no text stays `skipped`.

The reader is unaffected: its WebView parses XHTML as XML and never had the `<title/>` problem.

## Schema, reporting and UI

Schema:

- `IndexStateEntity` gains `@ColumnInfo(defaultValue = "0") val unreadableResources: Int = 0` (the default must be declared
  because Room validates column defaults and the `ALTER` adds one).
- `QuireDatabase` `version = 3`; `MIGRATION_2_3` runs
  `ALTER TABLE index_state ADD COLUMN unreadableResources INTEGER NOT NULL DEFAULT 0`;
  `addMigrations(MIGRATION_1_2, MIGRATION_2_3)`. `MIGRATION_1_2` is unchanged.
- `publish()` writes `unreadableResources`. `markTerminal` is unchanged.

Cause of partial coverage, carried as one value: `enum class IndexGap { None, FirstPartOnly, PartsUnreadable, Both }`,
built from `(truncated, unreadableResources > 0)`. It replaces `truncated: Boolean` in `IndexedBook` (the
`SearchDao.indexedBooks` query also selects `unreadableResources`), `BookTextResult`, `BookTextPage` and `BookSearchUi`.

Notes, as pure functions in `ui/TextSearchPresentation.kt` (`cardNote(gap)` and `sheetNote(gap)`, null for `None`;
`TRUNCATED_BOOK_NOTE` is removed):

| Gap | Book card (`TextSearchResults`) | In-book search sheet (`ReaderSheets`) |
|---|---|---|
| FirstPartOnly | Only the first part of this book is searchable | Only the first part of this book is searchable, so later matches are not listed. |
| PartsUnreadable | Parts of this book couldn't be read, so some passages may be missing | Parts of this book couldn't be read, so some passages may be missing. |
| Both | Only the first part of this book is searchable, and some of it couldn't be read | Only the first part of this book is searchable, and some of it couldn't be read, so some matches may be missing. |

The sheet shows its note under the same condition as today (status `Results` or `NoMatch`).

Coverage: `IndexCoverage.truncated` is renamed `partial`, counting `status = 'done' AND (truncated = 1 OR
unreadableResources > 0)`. The "N partly indexed" label, `isPartial()` and the "Some books are only partly searchable…"
no-match line keep their wording, which fits both causes. Settings does not split the count by cause. All-unreadable books
appear under "failed".

Docs:

- Rewrite the "Known limits" paragraph of `2026-10-03-library-text-index-design.md`: self-closing raw-text tags are
  normalised; unreadable resources are reported as partial coverage (or `failed` when none is readable), not recovered;
  other low-yield resources are only logged.
- README limitations: replace "a fallback extractor is planned" with the above, and add "after upgrading, run Settings →
  Rebuild once to re-extract books indexed before the fix."
- Mark `PLAN_FALLBACK_EXTRACTOR.md` as superseded by this spec.

## Files

New: `data/index/IndexContent.kt`.
Modified: `data/index/LibraryIndexer.kt`, `data/index/ResourceOrder.kt`, `data/index/IndexPolicy.kt`,
`data/index/TextSearchResult.kt`, `data/index/TextSearcher.kt`, `data/db/Entities.kt`, `data/db/QuireDatabase.kt`,
`data/db/Daos.kt`, `data/db/SearchDao.kt`, `ui/BookSearch.kt`, `ui/TextSearchPresentation.kt`,
`ui/library/TextSearchResults.kt`, `ui/reader/ReaderSheets.kt`, the text-index design spec, `README.md`,
`PLAN_FALLBACK_EXTRACTOR.md`.

## Tests

JVM:

- `HtmlNormalizerTest`: `<title/>`, `<TITLE />`, `<title id="x"/>` become pairs; each other listed tag likewise; `<br/>`,
  `<img/>`, `<meta/>` and `<title>Book</title>` untouched; unchanged input returns the same instance; a normalised
  `<title/>` document parsed with `Jsoup.parse` has its paragraphs in `body`.
- `ResourceOrderTest`: anchors and element selectors resolve in a resource with `<title/>` in its head.
- `IndexPolicyTest`: `extracted(...)` with all resources unreadable → `Unreadable` → `MarkFailed`; some unreadable →
  `Text(n, k)` → `Publish`; no HTML resources and readable-but-empty books keep today's outcomes (`skipped`).
- `TextSearchPresentationTest`: `cardNote` and `sheetNote` for all four `IndexGap` values; coverage line from `partial`.

Instrumented (generated EPUBs; run only through `tools/dbtest-suffix.init.gradle`):

- `EpubFixtures` gains a raw `<head>` option per resource and a way to corrupt named entries (deflate the entry, then
  overwrite its compressed bytes, so the archive opens but that entry's read fails).
- `LibraryIndexerTest`:
  1. A `<title/>` book is indexed fully: a phrase from its last chapter is found, and its chunk count equals the same book
     written with `<title>T</title>`.
  2. A mixed book (healthy and `<title/>` chapters, anchor-based TOC entries in both) has correct chapter labels on both.
  3. One corrupted chapter → `done`, `unreadableResources = 1`, `IndexGap.PartsUnreadable`, other chapters searchable.
  4. Every chapter corrupted → `failed`.
  5. Healthy fixtures produce exactly the chunks they produced before the change.
- `TextIndexMigrationTest`: a seeded v2 file migrates to v3 keeping every row, with `unreadableResources = 0`; the existing
  1 → 2 tests now open at v3 through both migrations.

## Verification

1. `env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/home/caan9/Android/Sdk ./gradlew assembleDebug test lint`, then
   the full instrumented suite via the `.dbtest` init script (never plain `connectedDebugAndroidTest`).
2. Emulator, `.dbtest` app: index the real *Juliet Takes a Breath* and confirm it goes from 1 chunk to several hundred;
   search a phrase from deep in the book, tap the snippet, confirm the reader underlines it. With the corrupted-chapter
   fixture, screenshot the "Parts of this book couldn't be read…" card note and the Settings coverage line. Check logcat for
   the sparse-resource lines.
3. Healthy-book speed: time a few scale-fixture books before and after; the added cost is one regex scan per resource, so
   it should not change.

## Risks

- **Other misparse causes stay unrecovered.** If the sparse-resource log shows a second cause in real libraries, revisit
  a fallback then, with that evidence.
- **Readium API surface.** `PublicationContentIterator`, `HtmlResourceContentIterator.Factory` and `TransformingResource`
  are public in 3.3.0 but marked experimental in places; a Readium upgrade must re-check them (Readium is pinned).
- **Charset.** Normalisation decodes as UTF-8, exactly as Readium does, and passes bytes through unchanged when nothing was
  rewritten, so non-UTF-8 resources behave as before.
