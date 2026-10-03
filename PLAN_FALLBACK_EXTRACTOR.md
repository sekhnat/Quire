# Plan: fallback text extractor and partial-coverage reporting

Status: PLAN ONLY. Nothing here is implemented. Follows `PLAN_LIBRARY_TEXT_INDEX.md` (library text index, Phases 0-7). Start only after the 1,500-book scale run has finished, because that run times indexing and scrolling and edits to `LibraryIndexer` or Gradle builds would disturb it.

## Context

Verification found books that Readium's content iterator cannot read in full, yet the indexer records them as `done` and fully searchable:

1. **`<title/>` in the head.** Readium parses each resource with `Jsoup.parse` in HTML mode, where `<title/>` is not self-closing, so the whole body becomes the title's text. One real Calibre book (*Juliet Takes a Breath*, ~450 KB of text) produced a single 184-character chunk.
2. **Swallowed read errors.** Readium skips resources it cannot read. A book with 17 of 34 chapters corrupted was published `done` with 545 chunks (a clean copy gives 1,059). A book with every chapter corrupted becomes `skipped`, not `failed`.

You chose to add a fallback extractor for case 1. The spec said to discuss custom extraction first, so this plan is that discussion. Case 2 cannot be fixed (the bytes are unreadable) but must stop being silent; it is included because both are the same per-resource reconciliation.

Goal: a book whose text Readium yields (almost) nothing for is still indexed from the XHTML; a book with resources nobody can read is shown as partly indexed; healthy books are unchanged and no slower.

## Where it plugs in (from the code)

- `data/index/LibraryIndexer.kt` `extract()` (l.231-264) streams `publication.content()` element by element into `TextChunker`, tracking `currentHref`. It sees only resources that yielded elements, so a zero-yield resource is invisible, and elements reach the chunker before their resource's total yield is known.
- `data/index/ResourceOrder.kt` parses the same HTML (`Jsoup.parse(html)`) to map chapter anchors; with `<title/>` its body is also empty, so chapter labels would break for these resources too.
- `data/index/IndexPolicy.kt` `Extracted` / `settle()` decide published / failed / skipped. `IndexStateEntity.truncated` currently means only "hit the 6 MiB cap"; the UI note says "Only the first part of this book is searchable", which would be wrong for a book missing middle chapters.
- Navigation does not depend on the stored `cssSelector` (Phase 5 drops it and finds the passage by quoted text), so fallback elements only need `href`, text and progression.

## Design

### 1. Per-resource reconciliation (decision logic, pure)
Buffer each resource's Readium elements and decide at the resource boundary (next href, or end of stream) instead of feeding the chunker immediately. A resource that yields no elements is detected by comparing the reading order index of the next element's href with the previous one (skipped indexes are zero-yield resources) and at end of stream.

For each HTML reading-order resource with Readium yield `Y` characters and stored length `L` bytes:
- Trust Readium if `L` is small (under ~2 KB) or `Y >= 2%` of `L`. This is an O(1) check; healthy books pay nothing.
- Otherwise read the resource ourselves and extract with the fallback to get `T` characters.
  - If our read fails: count the resource as **unreadable** (see 3).
  - If `T > 4*Y + 200`: use the fallback elements for this resource instead of Readium's.
  - Else keep Readium's (genuinely sparse page: cover, divider, image page).
Thresholds are named constants, tuned on the real books (below). Image-only books stay `skipped`.

Pure function: `reconcile(yieldChars, byteLength, fallbackChars: Int?, readFailed): Trust | UseFallback | Unreadable`, JVM-tested.

### 2. `HtmlFallbackExtractor` (new, `data/index/`)
- Input: raw XHTML string. Normalise self-closing `title`, `script`, `style`, `textarea` tags (`<title/>` to `<title></title>`) before `Jsoup.parse`, in one shared `normalizeHtml()` used by both this extractor and `ResourceOrder.parse` so chapter anchors resolve on these resources too.
- Output: ordered text blocks. Block elements (`p`, `h1`-`h6`, `li`, `blockquote`, `pre`, `td`/`th`, `dt`/`dd`, `figcaption`, leaf `div`) close a text run; inline elements join it; `br` is a space; `script`, `style`, `head`, `title` are skipped; whitespace collapsed the way Readium's text is.
- Each block becomes a `SourceElement`: `href`, text, `headingStart` from the tag name, `locatorJson` slim locator (href + in-resource progression from character offset, plus the jsoup `cssSelector` for the chapter labeler only), `totalProgression` interpolated between the resource's start and next resource's start using `publication.positions()` as Readium does.
- The chapter labeler runs unchanged (`markFor(href, css, ResourceOrder)`).

### 3. Partial-coverage reporting
- `IndexStateEntity` gets `unreadableResources INTEGER NOT NULL DEFAULT 0`. Migration(1,2) has not shipped, so amend its SQL, the entity and the migration test fixtures together (as the prefix-guard `fts4aux` change did).
- "Partly indexed" in coverage = `truncated OR unreadableResources > 0`. `IndexCoverage`, `BookTextResult` carry the distinction so the card note matches the cause: cap -> "Only the first part of this book is searchable"; unreadable resources -> "Some of this book couldn't be read, so some passages may be missing."
- Outcome rules: all HTML resources unreadable by both Readium and us -> `Extracted.Unreadable` -> `failed` (not `skipped`). Some unreadable -> `done` with `unreadableResources = n`.
- Retry semantics unchanged: retried only after a signature change or rebuild.

### 4. Settings and docs
Coverage already lists "partly indexed"; add the count source. Update the spec's "Known limits" paragraph (currently says both cases are undetected) and README limitations (fallback exists; unreadable chapters are reported, not recovered).

## Files

New: `data/index/HtmlFallbackExtractor.kt`, `data/index/ResourceReconciliation.kt` (pure `reconcile` plus thresholds).
Modify: `LibraryIndexer.kt` (buffer per resource, reconcile, zero-yield detection, count unreadable), `ResourceOrder.kt` (shared normalisation), `IndexPolicy.kt` (`Extracted` carries unreadable count; all-unreadable -> failed), `data/db/{Entities,QuireDatabase,Daos}.kt` (column, migration SQL, coverage query), `data/index/TextSearchResult.kt` and `ui/TextSearchPresentation.kt` (cause-specific note), spec, README.

## Tests

- JVM: `HtmlFallbackExtractorTest` (`<title/>` document; headings; inline vs block; `br`; entities; script/style/head skipped; empty and image-only pages; whitespace), `ResourceReconciliationTest` (every branch and the thresholds), shared normalisation in `ResourceOrderTest`, presentation strings per cause.
- Instrumented (generated EPUBs via `EpubFixtures`): `<title/>` book indexed fully and searchable; mixed book (healthy + `<title/>` chapters) chapter labels correct on both; one corrupted resource -> `done` with `unreadableResources = 1` and the right note; all resources corrupted -> `failed`; healthy book -> no fallback invoked and identical chunks to before (guards against false positives).
- Differential check on real books (throwaway script or test, then removed): for the 8 books used in the chapter-label audit, run the fallback over every resource and compare token streams with Readium's. Expect >=98% token overlap per book; this validates that fallback output is search-equivalent and sets the thresholds. Also confirm `Y >= 2%` of `L` holds for all healthy resources in those books (no false triggers) and that *Juliet Takes a Breath* goes from 1 chunk to roughly the expected several hundred.

## Verification

1. `env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/home/caan9/Android/Sdk ./gradlew assembleDebug test lint` plus the full instrumented suite via the protective `.dbtest` init script (never plain `connectedDebugAndroidTest`; it would wipe the installed app's data).
2. Emulator: index *Juliet Takes a Breath* (real file from the Calibre library) and the corrupted-chapters fixture under the `.dbtest` app. Search a phrase from deep in Juliet, tap the snippet, confirm the reader underlines the exact text. Screenshot the partly-indexed note for the unreadable-chapters book and the Settings coverage count.
3. Indexing time on healthy books unchanged (compare against the scale run's books/min).

## Risks and open points

- **Extractor parity.** A hand-rolled block segmenter will not match Readium on every edge case (tables, nested divs, `ruby`, RTL). Mitigated by only using it where Readium got almost nothing, and by the differential check; it is not a replacement path.
- **Thresholds.** `2%` of length and `4x + 200` are starting points to be set from the 8-book measurements, not facts.
- **Memory.** Buffering one resource's elements at a time is bounded by the 6 MiB stored cap; a single resource over 8M characters keeps today's behaviour (no fallback).
- **Schema amendment** is safe only because nothing has shipped; any `quire.db` with a v2 index built during testing needs a rebuild.
- **Decided (user, 2026-10-03): option A.** Add the `unreadableResources` column by amending Migration(1,2) (not a v3), so the card note names the cause. `truncated` keeps meaning only "hit the 6 MiB cap".
