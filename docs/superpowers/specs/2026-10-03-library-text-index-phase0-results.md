# Phase 0 results: library text index go/no-go probe

Branch `phase0-probe` (worktree `.claude/worktrees/agent-a0e5c309dd3b7916f`). All code is throwaway: `app/src/androidTest/java/com/quire/reader/probe/*`, `app/src/main/java/com/quire/reader/probe/ProbeBus.kt`, the probe hooks in `ReaderSession`/`QuireViewModel`/`QuireApplication`, and the probe entities in `data/index/IndexEntities.kt` (plus `applicationIdSuffix = ".probe"` in `app/build.gradle.kts`, so the user's installed `com.quire.reader` was never touched; the probe runs as `com.quire.reader.probe`).

Environment: emulator `Android_API_36`, Android 16, **SQLite 3.44.3**, Room 2.8.5 + KSP 2.3.12, AGP 9.0.1, Readium 3.3.0. Books: Dracula (603 KB), Frankenstein (475 KB), Moby-Dick (814 KB), Pride and Prejudice (24.8 MB, 164 images) from the emulator's `/sdcard`.

## Summary

| Gate | Result |
|---|---|
| 1. `publication.content()` | **PASS**, with two corrections to the plan (no Heading roles; element text is whitespace-normalised) |
| 2. Room external-content FTS4, SQL, MATCH/offsets/sync | **PASS** (FTS4 + unicode61 yes, FTS5 absent; verbatim SQL captured; Migration(1,2) verified through Room validation) |
| 3. Token accounting / byte->char mapping | **PASS** with documented rare-char caveats; the 64-token cap is purely a product rule (SQLite has no such cap) |
| 4. Chunk-boundary phrase returned exactly once | **PASS** (20,772 straddle queries, 0 mismatches) |
| 5. Navigation, underline, unresolved target, fallback | **PASS**, but `go()`'s Boolean does not reflect resolution and a `go()` issued immediately after attach leaves `currentLocator` stale; both need the amendments below |
| 6. Performance (<100 ms grouped passage count) | **FAIL for common terms** ("the" 16.6 s, `th*` 18.6 s, `"of the"` 26.7 s warm). Rare/mid terms pass. **User decision needed** |

## Gate 1: `publication.content()`

- Works with `@OptIn(ExperimentalReadiumApi::class)`; `publication.content()` returns `Content?`; `content.iterator()` yields `Content.Element`s (`TextElement`, `ImageElement`, ...).
- `Content.Iterator` in 3.3.0 has **no `close()`**; there is nothing to close except the publication. (The spec's "close the content iterator" does not apply.)
- Paragraphs are yielded as `TextElement`s. **Every element had `segments.size == 1`** (inline markup is not split), so segment-level precision equals element-level precision.
- **No `Role.Heading` elements were ever produced** (Dracula/Frankenstein/Moby/P&P: roles all `Body`, even `<h2>CHAPTER I`). The heading is only detectable from `locator.locations.otherLocations["cssSelector"]` ending in `h1..h6` (probe did that: `Chunker.isHeading`). The plan's "heading starts a chunk" needs this CSS-selector heuristic. Chapter labels must come from the TOC (as `ReaderSession.chapterTitle` does), not from content roles.
- Locator precision per element/segment (100% of 2157/906/2878/2517 segments): `href`, `locations.otherLocations["cssSelector"]` (e.g. `#pgepubid00007 > a > p:nth-child(4)`), `locations.progression` (position inside the resource), `locations.totalProgression`, `text.highlight` (whole element, **raw DOM text with `\n` and indentation**), `text.before` (up to ~50 chars of preceding text, can include odd whitespace). `text.after` is absent.
- `TextElement.text` is **whitespace-normalised** (runs collapsed, trimmed); `locator.text.highlight` is the raw text. They differ for 1549 of 2157 Dracula elements. `norm(highlight) == element.text` held for 2155/2157 (the two exceptions are unexplained; probably non-breaking spaces). Readium's JS matching is whitespace-insensitive (see Gate 5), so indexing the normalised text is fine.
- Extraction time (emulator, warm JIT after first run), 3 runs each:

| Book | text elements | chars | extract |
|---|---|---|---|
| Frankenstein | 906 | 436 k | 294 ms |
| Dracula | 2157 | 852 k | 1258 ms first, ~940-980 ms after |
| P&P (164 images) | 2517 | 730 k | 2741 ms first, ~2610 after |
| Moby-Dick | 2878 | 1,230 k | 2183 ms first, ~2060 after |

Typical novel: about 1-2.5 s. Publication open: 23-66 ms. Meets "a couple of seconds". Corpus context: across the user's 1,045 real Calibre EPUBs the mean text is 724 k chars (median 510 k, p90 1.57 M, max 7.2 M).

## Gate 2: Room FTS4 and SQL

Entities: `TextChunkEntity` (`text_chunk`), `@Fts4(contentEntity=TextChunkEntity::class, tokenizer="unicode61") TextChunkFts` (`text_chunk_fts`, columns `rowid` + `text`), `IndexStateEntity` (`index_state`). KSP built them with no changes to the stack. Room 2.8.5 generates **Kotlin** (`build/generated/ksp/debug/kotlin/.../QuireDatabase_Impl.kt`), using the androidx.sqlite driver API.

**Exact generated SQL** (copy verbatim into `Migration(1, 2)`; verified: the migration run over a hand-built file-backed v1 DB with seeded folder/book/book_state/bookmark data passed Room's `onValidateSchema`, kept all v1 rows, and FTS insert + book-delete cascade worked on the migrated DB):

```sql
CREATE TABLE IF NOT EXISTS `text_chunk` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `chapter` TEXT NOT NULL, `href` TEXT NOT NULL, `tokenStart` INTEGER NOT NULL, `tokenEnd` INTEGER NOT NULL, `primaryEndByte` INTEGER NOT NULL, `text` TEXT NOT NULL, `mapping` TEXT NOT NULL, `progression` REAL NOT NULL, FOREIGN KEY(`bookId`) REFERENCES `book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
CREATE UNIQUE INDEX IF NOT EXISTS `index_text_chunk_bookId_seq` ON `text_chunk` (`bookId`, `seq`)
CREATE VIRTUAL TABLE IF NOT EXISTS `text_chunk_fts` USING FTS4(`text` TEXT NOT NULL, tokenize=unicode61, content=`text_chunk`)
CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_text_chunk_fts_BEFORE_UPDATE BEFORE UPDATE ON `text_chunk` BEGIN DELETE FROM `text_chunk_fts` WHERE `docid`=OLD.`rowid`; END
CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_text_chunk_fts_BEFORE_DELETE BEFORE DELETE ON `text_chunk` BEGIN DELETE FROM `text_chunk_fts` WHERE `docid`=OLD.`rowid`; END
CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_text_chunk_fts_AFTER_UPDATE AFTER UPDATE ON `text_chunk` BEGIN INSERT INTO `text_chunk_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END
CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_text_chunk_fts_AFTER_INSERT AFTER INSERT ON `text_chunk` BEGIN INSERT INTO `text_chunk_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END
CREATE TABLE IF NOT EXISTS `index_state` (`bookId` INTEGER NOT NULL, `mtime` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, `chunkCount` INTEGER NOT NULL, `textBytes` INTEGER NOT NULL, `truncated` INTEGER NOT NULL, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
```

Notes:
- Room itself drops the FTS sync triggers in `onPreMigrate` and recreates them in `onPostMigrate` (for any migration), so the triggers in the migration are redundant but harmless (`IF NOT EXISTS`). Room validates the FTS table by column set and options (`tokenize=unicode61`, `content=text_chunk`); the migration text must keep these exactly.
- Shadow tables created: `text_chunk_fts_segments/_segdir/_docsize/_stat` (no `_content`, it is external).
- The exact probe-captured statements, plus the v1 statements, are in `app/src/androidTest/java/com/quire/reader/probe/MigrationSql.kt` (generated by script from the Impl file).

Engine facts (device, not JVM):
- `sqlite_version() = 3.44.3`. **FTS4 + `tokenize=unicode61` available; `remove_diacritics=2` accepted; FTS3 porter exists; FTS5 absent** (`no such module: fts5`), confirming the source plan. `fts3tokenize` is available (used as a tokenizer oracle).
- Bound `MATCH ?` works for: `pemberley`; unquoted prefix `pember*`; phrase `"large handsome"` (matches across a comma; reversed phrase does not match); `"large, handsome stone"`; implicit AND; accent folding (`cafe`, `naive`, `zurich`, `CAFE`->café all match).
- **Prefix syntax:** `"pember"*` does NOT act as a prefix (returns nothing). `pember*` and **`"pember*"` (star inside quotes) both work**; `"count of pember*"` works as phrase-with-final-prefix. Use `"tok1 tok2 pre*"` as the only quoted form so no bare word reaches the parser.
- Operator hazard confirmed: unquoted `pemberley OR dracula` ORs, `text:pemberley` is a column filter, `NEAR/2` works, `-pemberley` and an unbalanced `"abc` throw `SQLiteException: malformed MATCH expression`. Quoted `"or"`, `"text:pemberley"` are plain text. Tokens with `or*`, `near*`, `not*`, `and*` unquoted were handled as prefixes (no error). Conclusion: build the MATCH string only from tokenised alphanumeric runs, each phrase quoted, final unquoted word as `"w*"`-style quoted-prefix.
- `offsets(text_chunk_fts)` returns space-separated quadruples `column term byteOffset byteSize`: `[0 0 0 9 0 0 66 9]` for two hits. For phrases each token gets its own quadruple (`0 0 16 5 0 1 23 8`). `snippet()` works on the external-content table.
- Sync: UPDATE of `text_chunk.text` removes old terms and adds new ones; DELETE removes FTS rows; **deleting a `book` row cascades through `text_chunk` and the delete trigger fires** (docsize 0, no hits, `index_state` gone); `INSERT INTO fts(fts) VALUES('integrity-check')` ok. `PRAGMA foreign_keys` is 1 under Room.

## Gate 3: tokens, accents, byte offsets

- A Kotlin tokenizer using Java `Character.getType` (L*, N*, Co as token chars; plus U+0300..U+036F combining marks) matched `fts3tokenize(unicode61)` with **0 mismatching token boundaries over all of Dracula (166,785 tokens), Frankenstein (78,538), Moby-Dick (222,567)**, including curly quotes and em dashes. Speed: Kotlin 10-33 ms per novel; `fts3tokenize` via SQL 41-156 ms per novel (so using SQLite itself as the tokenizer is also affordable).
- Exhaustive code-point comparison (all 1,112,063 scalar values): SQLite 3.44 treats **1,104,067** as token characters, i.e. almost everything including unassigned and newer symbol code points. Differences from Java categories are rare scripts/marks (e.g. Mn marks outside U+0300-036F such as Arabic harakat U+064E, Hebrew points, many emoji modifiers like U+1F3FD, bidi isolates, unassigned code points). Two of 19 torture strings differed (an emoji skin-tone modifier is a token in SQLite; a leading lone combining mark is skipped by SQLite).
- Behaviour confirmed with the oracle: apostrophes and hyphens split (`don't` -> `don`,`t`; `can’t` -> `can`,`t`; `e-mail` -> `e`,`mail`); `foo_bar` splits; digits with `.`/`,` split (`3.14` -> `3`,`14`); `ß`, `œ`, `æ` are NOT folded to `ss`/`oe`/`ae`; Latin accents and decomposed forms fold (`café` == `café` -> `cafe`); CJK/Hangul runs are single tokens; vocalised Arabic/Hebrew shatters into letters (the harakat are separators), so a query with unpointed letters will not match pointed text. These are the documented limitations to carry (CJK, pointed Arabic/Hebrew, ligature/ß folding).
- **No 64-token limit exists in SQLite**: 100 ANDed quoted tokens, a 100-token phrase and a 64-token phrase with a final prefix all matched. The 64-token cap is a pure product rule; the overlap of 63 following tokens makes the rule sufficient. (Tokens counted by the Kotlin tokenizer could differ from SQLite's by an occasional rare character; a safety margin of a few extra context tokens, or tokenising with `fts3tokenize`, removes that risk. Not measured further.)
- **Byte offset -> Kotlin char index** (worked for surrogate pairs, CJK, curly quotes, decomposed marks; 17 checked matches asserted equal to `String(bytes, off, size, UTF_8)`; the naive byte-as-char slice gives wrong text, e.g. `'キスト an'` instead of `target`):

```kotlin
/** Maps UTF-8 byte offsets (as reported by FTS offsets()) to Kotlin char indexes. */
class ByteCharMap(s: String) {
  private val startByteOfChar = IntArray(s.length + 1)
  init {
    var b = 0; var i = 0
    while (i < s.length) {
      val cp = s.codePointAt(i); val n = Character.charCount(cp)
      for (k in 0 until n) startByteOfChar[i + k] = b
      b += when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
      i += n
    }
    startByteOfChar[s.length] = b
  }
  fun byteOf(charIndex: Int) = startByteOfChar[charIndex]
  /** First char whose UTF-8 encoding starts at [byteOffset]; offsets must be on a code point boundary. */
  fun charIndex(byteOffset: Int): Int {
    var lo = 0; var hi = startByteOfChar.size - 1
    while (lo < hi) { val mid = (lo + hi) ushr 1; if (startByteOfChar[mid] < byteOffset) lo = mid + 1 else hi = mid }
    check(startByteOfChar[lo] == byteOffset) { "not on a code point boundary" }
    return lo
  }
}
```

## Gate 4: overlap, ownership, exactly-once

Probe chunker (`Chunker.kt`): per resource (href) the text elements are joined with a single space into resource text R, tokenised, then packed at element boundaries to 600-900 chars (elements >900 split at token boundaries, heading selector starts a chunk). Each chunk's stored `text` is one contiguous slice of R: the primary tokens plus up to 63 following tokens (crossing elements, not crossing hrefs). `primaryEndByte` = UTF-8 byte length of the primary part. Mapping JSON per overlapped element: `[byteStart, byteEnd, elementIdx, charOffsetInElement, cssSelector, progression]`.

Ownership rule used (works): a matched row is owned iff **the first quadruple's byte offset (third integer of `offsets()`) < `primaryEndByte`**. Verified: over 1,193 matched rows of 400 random AND-2 queries and the rows of 200 random AND-3 queries, the first quadruple was always the minimum offset (0 exceptions), so the SQL does not need to scan all quadruples.

Result on Dracula + Frankenstein + Moby-Dick (3,074 chunks in one DB):
- **Straddle test**: for every chunk boundary, phrases of length 2/12/64 placed with 1, len/2 and len-1 tokens before the boundary: **20,772 queries, 0 mismatches** against an independent token-sequence oracle (expected set of passages = chunks whose primary range contains the start of at least one occurrence, with the earliest start). Without the ownership filter, 1,192 phrases returned duplicate rows (129,344 raw rows vs 104,330 owned), so the filter is needed and sufficient.
- **Phrase spanning two source elements**: 538 queries (last 2 tokens of element i + first 2 of element i+1, same resource), all found, 0 mismatches.
- **Cross-resource**: 72 phrases built from the tail of resource A + head of resource B (absent elsewhere): 0 leaks (no phrase manufactured across resources).
- **Single-token** queries (150): owned set == oracle, 0 mismatches. Note: the count is **passages (chunks) not occurrences**, e.g. a chunk with the word three times counts once (my first oracle mistakenly counted occurrences, which the spec already says not to).
- Sizes measured: Dracula 1,059 chunks, text 1.20 MB, mapping 0.28 MB; Frankenstein 538 chunks; Moby 1,477 chunks. Primary size: median ~890 chars, p10 ~650-675, max 900, only 0.5-4.6% under 600 chars (trailing/heading chunks). Average stored chunk is ~1,115-1,180 chars (about 1.4x the source text because of the 63-token context). Real-book DB file with those 3 books: **8.86 MB for 2.5 M source chars (about 3.5x)**. The mapping (with CSS selectors per element) was 23% of chunk text on these books. Insert incl. FTS trigger: 120-620 ms per book.

Ownership query that worked on-device (single-row fetch form used in the test):

```sql
SELECT c.id, c.primaryEndByte, offsets(text_chunk_fts)
FROM text_chunk_fts JOIN text_chunk c ON c.id = text_chunk_fts.docid
WHERE text_chunk_fts MATCH ?          -- e.g. "to turn for help"
```
then in Kotlin: `owned = offsets[2] < primaryEndByte` and `charInChunk = ByteCharMap(chunk.text).charIndex(offsets[2])`. The pure-SQL grouped form (Gate 6) is:

```sql
WITH m AS MATERIALIZED (SELECT docid AS id, offsets(text_chunk_fts) AS o FROM text_chunk_fts WHERE text_chunk_fts MATCH ?),
g AS (
  SELECT c.bookId AS bookId, COUNT(*) AS n, COALESCE(st.lastOpenedAt,0) AS lo
  FROM m JOIN text_chunk c ON c.id = m.id
  JOIN index_state s ON s.bookId = c.bookId AND s.status = 'done'
  JOIN book b ON b.id = c.bookId AND b.mtime = s.mtime AND b.sizeBytes = s.sizeBytes
  LEFT JOIN book_state st ON st.bookId = c.bookId
  WHERE CAST(substr(substr(m.o, instr(m.o,' ')+1), instr(substr(m.o, instr(m.o,' ')+1),' ')+1) AS INTEGER) < c.primaryEndByte
  GROUP BY c.bookId)
SELECT bookId, n, lo, COUNT(*) OVER () AS totalBooks FROM g ORDER BY n DESC, lo DESC, bookId LIMIT 40
```
(`offsets()` works inside the materialized CTE joined to `text_chunk`; `CAST('66 9 0 0' AS INTEGER)` yields 66 so no custom function is needed.)

## Gate 5: navigation

Probe harness: a runtime-registered broadcast receiver (`ProbeBus`) reads a request JSON, scans the book's folder if needed, opens it with `read(id, restart=true, probe=request)`, and `ReaderSession.attach()` runs the navigation, then reads Readium's own decoration state from the page via `EpubNavigatorFragment.evaluateJavascript("window.readium.getDecorations('search').items...range.toString()")`. Locators were derived **only from stored chunk data** (`href`, `text`, `mapping`, FTS offsets), never from the source elements.

- Vendored navigator: `EpubNavigatorFragment.go(locator, animated): Boolean` already returns `Boolean`, but it reports only *"I started a jump"*, not resolution. Measured:
  - target text/selector resolves: `true`, decoration items non-empty.
  - **text absent from an existing resource: `go()` returns `true`**, 0 decoration items.
  - **unknown resource href: paged mode `go()` returns `true`** (setCurrent silently returns), **scroll mode returns `false`** (`goInStack` index < 0).
  - So the Boolean cannot be used as the success signal. Real resolution = the decoration range count from the page (`items.length > 0`) after the resource has loaded.
- `ReaderSession.go(Locator)` today returns `Unit` (and `navigator == null` is a silent no-op). For Phase 5 it would need to be `suspend fun go(...): Outcome` that (1) returns Failed if navigator is null, (2) calls `navigator.go`, (3) applies the decoration via `applySearchHits`, (4) after load, reads the decoration count via `evaluateJavascript`, and (5) falls back to `goToProgress(progression)` when the count is 0.
- Resolved cases (both paged and continuous-scroll; screenshots confirmed the underline on the right words):
  - Plain word `Transylvania` mid-book: underlined text `Transylvania`, in the 4th paragraph of the right chapter.
  - Highlight spanning a raw `\n` using the **normalised** highlight (`and most other`): resolved to the raw text `and\nmost other` (Readium's quote matching ignores whitespace differences). Raw-text variant also resolved. So deriving highlight/before/after from normalised stored text is fine.
  - Repeated word (`the` x4 in one paragraph): `before`/`after` of 40 chars disambiguated to the 3rd occurrence (checked via the range's preceding text).
  - Phrase spanning two elements: two locators (one per element) each underlined (`dark openings.` / `I stood`); a **single locator** with the joined highlight (`dark openings. I stood`) also resolved, with a range across both paragraphs (`sameNode=false`). Either works.
  - Stale/wrong CSS selector with correct text: still resolved (text fallback).
- Unresolved cases: absent text -> 0 items, so the fallback `goToProgress(0.4)` ran and the view moved to the middle of the book with **no underline**; unknown href -> fallback to 0.6 worked (position 233 / 216 of 388). No unrelated text was underlined.
- **Timing hazard found:** calling `go()` immediately in `attach()` (the same place `EpubHost` attaches) produced the right on-screen text and underline, but `navigator.currentLocator` and the app's footer ("Page 1 of 388") stayed stale at the first resource for the whole observation (about 10 s), so the saved reading position would not reflect the hit. With `go()` delayed 1.5 s (after the first resource has loaded) `currentLocator` updated correctly (position 60, resource `h-7`). Opening the navigator directly at the target as `initialLocator` (mode "initial") also updated correctly and is the simplest: decorations still applied via `applySearchHits` after attach. The plan's "go after attach" must wait for the navigator's first page-loaded signal (or use the initial locator).

## Gate 6: performance (grouped passage counts)

Setup: synthetic **1,500-book** index built on the host with the verbatim Room v2 schema (real text from the user's 1,045 Calibre EPUBs: 1,045 real books + 455 random slices of them; per-book cap of 6 MiB of text+mapping, 33 books truncated; injected markers `zorvakN` (1 book), `quillfeather` (~41 books), phrase "the crimson heron sang" (6 books)). One transaction per book through the sync triggers (so the FTS segment structure is what incremental indexing produces: 50 segdir rows across 6 levels). **1,171,145 chunks, 1.30 GB chunk text, final file 3.85 GB.** Pushed to the probe app's `databases/perf.db` and queried with `SQLiteDatabase` on the emulator (4 cores, KVM). **Cold = first run after `echo 3 > /proc/sys/vm/drop_caches` (adbd as root) in a fresh process; warm = median of repeated runs in the same process.** Query = the grouped ownership SQL above (group by book, `LIMIT 40`, plus total-books window count).

| Query | match | cold (first) | warm | rows owned/books |
|---|---|---|---|---|
| (a) rare unique word | `zorvak777` | 15.6 ms | 0.3 ms | 1 book |
| (a) rare mid word (41 books) | `quillfeather` | 27.5 ms | 0.6 ms | 40 of 41 |
| (a) natural rare word | `gossamer` | 31.2 ms | 0.7 ms | 78 books |
| (b) **common word** | `the` | **16,518 ms** | **16,574 ms** | 1,498 books |
| (c) prefix (mid, 14k docs) | `wander*` | 1,114 ms | 53 ms | 1,261 books |
| (c) **prefix (very common)** | `th*` | **18,019 ms** | **18,554 ms** | 1,498 books |
| (d) phrase rare | `"the crimson heron sang"` | 73.9 ms | 23.8 ms | 6 books |
| (d) phrase mid | `"in the garden"` | 206.6 ms | 48.4 ms | 335 books |
| (d) **phrase common** | `"of the"` | **26,298 ms** | **26,728 ms** | 1,495 books |
| two common AND | `king queen` | 146 ms | 14.2 ms | 275 books |

**The <100 ms target is met for rare/moderate terms warm, but fails by two orders of magnitude for very common words, very short prefixes and common phrases, and cold first runs of mid-frequency prefixes/phrases miss it too (wander* 1.1 s, "in the garden" 207 ms, king queen 146 ms).** The cost scales with the number of matching chunks (`the` matches 1,155,912 of 1,171,145 chunks).

Cost breakdown (warm, measured):
- `offsets()` costs about **4.7 µs per matched row** (20,000 rows: 93.6 ms; docid scan alone for the same rows: 3.9 ms). So the offsets-based ownership filter limits any query to roughly 20,000 examined rows per 100 ms.
- The FTS doclist scan itself is cheap (`SELECT count(*) ... MATCH 'the'` = 35 ms for 1.16 M docids).
- Joining each FTS hit to `text_chunk` (wide rows) for `bookId` is also costly: raw grouped count (no offsets) for `the` = **5.8 s**; `"of the"` 7.9 s; `th*` 6.8 s.

Alternatives measured (not chosen; for a user decision):

| Alternative | `the` | `"of the"` | `th*` | `wander*` | Comment |
|---|---|---|---|---|---|
| A1: raw (unowned, overcounts overlap) grouped count via a narrow `chunk_book(id, bookId)` table, no offsets | 217 ms | 295 ms | 1,175 ms | 4.6 ms | still >100 ms; adds a table (343 ms to build; ~1.17 M tiny rows); counts include context-copy duplicates |
| M3: ownership-correct grouped counts over only the first N matches in docid order (global cap, NOT per book) | N=2000: 13.9 ms; N=10,000: 54 ms | 15.4 / 56.4 ms | not run | not run | bounded, but counts become "of the first N matching passages" and favour the oldest-indexed books |
| M4: `fts4aux` doc-frequency guard to detect "too common" before running (decide to cap/refuse) | exact term 54 ms | n/a | prefix range 195 ms | 0.5 ms | exact term cheap-ish; short-prefix estimate itself too slow |
| M5: two-phase (rank 40 books by raw count, then exact owned count per book via `docid BETWEEN`), optional cap 99 rows/book | 4.6 s (1.9 s with cap) | 5.5 s (4.5 s) | not run | 936 ms | much too slow: each range query rescans the doclist |
| Per-book counting cap by `LIMIT` inside per-book range queries | same as M5 | | | | not viable as such |

Not measured (idea only): a second, primary-text-only FTS column/table for single-token and AND queries to avoid `offsets()`; it doubles part of the index and phrases still need `offsets()`.

Storage observed: **DB file 3.85 GB for 1,500 books** (1.08 G source chars): `text_chunk` 2.74 GB (text 1.30 GB + mapping 0.72 GB in the *synthetic* data, whose per-element CSS selector strings made mapping ~55% of text vs 23% in the real 3-book probe, plus overflow-page slack), FTS segments 0.63 GB, docsize 13 MB, index on (bookId, seq) 18 MB. Real-book probe ratio was 3.5x source text; applying that to 1,500 average novels gives roughly 3-4 GB. Insert speed on host (Python, per-book transactions with triggers): 1.17 M chunks in 102 s; on-device insert for the three real books took 120-620 ms per book.

## Surprises and recommended plan amendments

1. **Gate 6 failure for common words (decision needed, see below).** The plan's offsets()-ownership grouped count cannot be fast for words matching a large share of chunks; no tested alternative reaches 100 ms for `the` over the full corpus unless the examined passages are capped.
2. **`Role.Heading` never appears** in Readium 3.3.0 `content()`; derive heading starts from the `cssSelector` (`h1..h6`) or drop that chunking rule. Chapter label must come from the TOC.
3. **Locator highlight is raw DOM text, element text is normalised.** Index normalised text (fine, Readium matches whitespace-insensitively); build `Locator.Text` from stored text. Keep CSS selector + progression per element in the mapping (needed; stale selector still resolves by text but it speeds and disambiguates).
4. **`go()` Boolean is not a resolution signal** (true for absent text; true for unknown href in paged mode). Success must be measured through the page's decoration count (`window.readium.getDecorations('search').items.length`) after load; `ReaderSession.go` must become a suspend function returning an outcome and own the fallback.
5. **Navigation timing:** `go()` right at `attach()` leaves `currentLocator`/saved position stale; prefer `initialLocator = target` at navigator creation (plus `applySearchHits` after attach), or wait for the first page-loaded locator before `go()`.
6. **Prefix syntax:** emit `"tok1 tok2 pre*"` for a final prefix; `"pre"*` silently matches nothing.
7. **Room generates the FTS triggers in `onPostMigrate` and drops them in `onPreMigrate`**; Migration(1,2) can include them but need not rely on them.
8. **Tokenizer fidelity:** the Kotlin tokenizer matched SQLite exactly on all three novels but not for rare marks/emoji modifiers/pointed Arabic-Hebrew; use a few tokens of context margin or tokenise with `fts3tokenize` (about 3-4x slower, still ~100-150 ms per novel). The SQLite Unicode tables vary by OS version (minSdk 30 ships an older SQLite), so a generated Kotlin table would drift; do not hard-code one.
9. **Counts are passages (chunks)**, not occurrences; multiple hits in one chunk count once. UI wording must say so (spec already does).
10. **Storage:** about 3.5x the source text (roughly 3.8 GB for 1,500 average novels in my synthetic build; the spec's honest "database storage" label is needed). The mapping JSON is a large part; the 63-token context adds about 40% to text. Possible reductions (unmeasured): store only element index + offset in mapping and keep CSS selectors in a per-book table.
11. Extraction speed is fine; a 1,500-book first pass at ~1-2.5 s/book is roughly 25-60 minutes of extraction CPU on the emulator, which supports the bounded 5-minute worker design.
12. The vendored `EpubNavigatorFragment.evaluateJavascript` is public and enough to implement a real resolution check; no navigator changes are needed.

## Needs a user decision

How should the grouped count behave for very common queries (`the`, `th*`, common phrases), given that exact full-library ownership-correct counts take 16-27 s here? Options measured: (a) cap examined/counted passages (M3: 10,000 examined rows = 54 ms, counts shown as "N+" and biased to oldest-indexed books), (b) refuse/limit queries whose estimated frequency is too high (fts4aux guard: exact term 54 ms; short-prefix estimate 195 ms, so needs a minimum prefix length rule instead), (c) accept approximate unowned counts via a narrow docid->book table (217 ms for `the`, still above target), (d) a different index layout (primary-only column) which is unmeasured. Not chosen here.

## Not verified / caveats

- Performance numbers are from a synthetic corpus built from real text (mixed fiction/fanfiction), duplicated slices, host-built via Python SQLite 3.53 (file format identical, but not built by the on-device FTS writer); query times were measured on the emulator's SQLite 3.44.3. Real phone hardware will differ (typically slower CPU, different storage).
- "Cold" means page cache dropped via root `drop_caches` on the emulator, not a physical device cold start.
- Only EPUB reflowable text was tested; fixed-layout, RTL, and image-only EPUBs were not.
- Android versions below 16 (minSdk 30) ship older SQLite; unicode61 tables there were not tested.
- The emulator's adbd was left in root mode (`adb root`) and `/data/data/com.quire.reader.probe/databases/perf.db` (3.8 GB) remains on the emulator in the probe app only; uninstall `com.quire.reader.probe` to free it. The user's `com.quire.reader` app and data were not touched.
