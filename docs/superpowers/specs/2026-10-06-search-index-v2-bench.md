# Search index v2: benchmark results

Status: measured 2026-10-05/06 on the `quire_scale` emulator (Pixel 7 profile, API 36, x86_64, 4 cores, 4 GB RAM,
KVM, SQLite 3.50.1 bundled) over the 1,518-book fixture of the earlier scale run (1,054 real EPUBs, 441 derived slices,
18 synthetic CJK EPUBs from public-domain Chinese, Japanese and Korean texts), installed under a private application id.
Design record: `2026-10-06-search-index-v2-design.md`.

## Headline

| Gate | Target | Result | Verdict |
| --- | --- | --- | --- |
| Index size | ≤ 55% of the old index (4.24 GB) | **1.57 GB, 37.0%** (variant D at 16 KB pages) | pass |
| APK growth | ≤ 4 MB | **+2.52 MB** (release, `arm64-v8a` + `x86_64`; 11.46 → 13.98 MB) | pass |
| Result sets | match A except the documented cross-chunk case | **yes**, see "Result sets" | pass |
| p95 no worse than A | every query | **no**: of 27 queries 15 are faster, 1 level, 8 slower by at most 3.8 ms, 3 are CJK queries A cannot run | **not strictly met**, see "Latency" |

The last row is the honest one. Every heavy query is 1.3–11× faster than the old index, but small queries now pay about
3 ms to read the books and index states that the old single SQL join fetched for free (the worst case is a unique word,
0.5 → 4.3 ms), and the three CJK rows compare a working search with one that refuses a single character or returns the
wrong books for a mixed query. The variants differ little on this, so the choice below does
not depend on it. The decision to ship with this is the reader's to confirm.

## Variants

| | Engine | Primary chunk | Cross-boundary | Mapping |
| --- | --- | --- | --- | --- |
| A (baseline) | FTS4 | 600–900 | 67-token overlap | JSON |
| B | FTS5 | 600–900 | 67-token overlap | JSON |
| C | FTS5 | 600–900 | seams | binary |
| D | FTS5 | 1,000–1,500 | seams | binary |
| E | FTS5 | 1,333–2,000 | seams | binary |

All five are built from one cache of extracted elements (5.4 M elements), so they differ only in index design. A is a
frozen copy of the old chunker, JSON mapping and SQL. Variant A reproduces the earlier scale run: 1.12 M chunks against
1.108 M, 4.27 GB against 4.26 GB. Chunks the old index copied: **400 MB of 1.32 GB (30%)**.

### Size

| Variant | Chunks | Chunk text | Seam text | Index at 4 KB pages | Index at 16 KB pages | % of A | Build |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 1,119,640 | 1,316 MB incl. 400 MB copied | – | 4,235 MB | – | 100% | 244 s |
| B | 1,119,640 | 1,316 MB | – | 4,224 MB | – | 99.7% | 145 s |
| C | 1,263,914 | 963 MB | 35.5 MB (3.7%) | 1,728 MB | 1,617 MB | 38.2% | 149 s |
| D | 730,679 | 966 MB | 7.3 MB (0.76%) | 1,809 MB | **1,566 MB** | **37.0%** | 142 s |
| E | 542,828 | 966 MB | 3.4 MB (0.36%) | 1,766 MB | 1,562 MB (8 KB: 1,617 MB) | 36.9% | 142 s |

- B isolates the engine: FTS5 alone changes nothing in size and builds 40% faster.
- The binary mapping and no overlap do the work: A's `text_chunk` is 3.57 GB (text 1.32 GB plus about 2.2 GB of JSON
  locators); D's `chunk` is 1.06 GB (text 0.97 GB, mapping about 30 MB, page waste the rest). The FTS data went from 633 MB
  to 460 MB.
- With 4 KB pages the 1–2 KB rows pack badly, and bigger chunks looked *larger* (D 1,809 MB against C 1,728 MB). 16 KB
  pages remove that: the three variants converge at 1.56–1.62 GB. Seam text is 0.4–3.7% of chunk text (the plan expected about 1%).
- The old per-book size cap counted overlap and JSON and truncated 48 books; it now truncates 4 (C, D) or 3 (E).

### Latency

p50 / p95 in ms over 20 runs after one warm-up (`*` = hit the examine cap, here 5,000 for every variant, as before):

| Query | A p50 / p95 | B p95 | C p95 | D p50 / p95 | E p95 | D vs A (p95) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| common typed | 91.7 / 100.0* | 101.9* | 36.4* | 21.8 / 34.0* | 35.7* | 0.34× |
| common exact | 40.3 / 41.5* | 73.7* | 6.3* | 9.0 / 9.7* | 11.8* | 0.23× |
| mid word | 30.2 / 31.7 | 52.4 | 20.0 | 21.2 / 23.9 | 29.1 | 0.75× |
| mid-common word | 45.3 / 46.2* | 87.6* | 22.7* | 26.5 / 28.3* | 31.9* | 0.61× |
| rare word | 4.1 / 4.6 | 5.2 | 7.0 | 7.2 / 7.9 | 7.9 | 1.72× |
| unique word | 0.4 / 0.5 | 0.4 | 4.9 | 3.0 / 4.3 | 4.4 | 8.60× |
| common prefix | 25.7 / 26.0 | 39.6 | 18.2 | 18.8 / 19.9 | 21.3 | 0.77× |
| rare prefix | 4.1 / 4.4 | 5.3 | 6.9 | 7.1 / 7.5 | 7.7 | 1.70× |
| mid prefix | 50.3 / 51.2* | 77.2* | 23.2* | 27.0 / 27.8* | 32.3* | 0.54× |
| 2-word phrase common | 37.5 / 38.4* | 59.2* | 14.3* | 14.3 / 15.1* | 16.4* | 0.39× |
| 2-word phrase rare | 1.5 / 2.4 | 2.6 | 5.4 | 4.7 / 5.6 | 5.7 | 2.33× |
| 5-word phrase rare | 42.0 / 43.1 | 0.5 | 3.7 | 2.4 / 3.7 | 3.5 | 0.09× |
| 5-word phrase common | 195.4 / 213.2* | 111.7* | 122.1 | 105.8 / 106.9 | 106.4 | 0.50× |
| AND common tail | 128.1 / 128.8* | 115.5* | 42.2* | 45.1 / 46.5* | 51.6* | 0.36× |
| AND mid | 3.9 / 4.7 | 4.7 | 5.9 | 6.4 / 6.8 | 8.0 | 1.45× |
| filter narrow author | 11.2 / 15.2 | 10.5 | 14.9 | 14.4 / 15.1 | 15.5 | 0.99× |
| filter broad tag | 17.4 / 18.6 | 24.7 | 14.5 | 17.0 / 17.4 | 20.7 | 0.94× |
| filter broad tag common | 58.5 / 60.2* | 65.3* | 8.9* | 11.2 / 12.1* | 16.0* | 0.20× |
| filter status reading | 0.8 / 1.2 | 0.8 | 3.6 | 2.4 / 3.7 | 3.8 | 3.08× |
| page common prefix | 482.4 / 492.8 | 332.0 | 239.6 | 216.8 / 217.8 | 208.0 | 0.44× |
| page common exact | 34.2 / 35.9 | 7.0 | 3.6 | 3.9 / 4.5 | 4.9 | 0.13× |
| CJK 1 char common | 0.0 / 0.0 | 0.0 | 19.6 | 17.3 / 18.6 | 19.4 | n/a |
| CJK 1 char | 0.0 / 0.0 | 0.0 | 4.9 | 4.0 / 4.8 | 5.0 | n/a |
| CJK 2 chars | 10.0 / 10.5 | 19.8 | 5.8 | 5.9 / 7.0 | 6.5 | 0.67× |
| CJK 3 chars | 1.9 / 2.9 | 4.1 | 4.3 | 2.9 / 4.1 | 4.3 | 1.41× |
| CJK mixed | 3.8 / 4.5 | 4.6 | 43.8 | 48.9 / 51.0 | 58.6 | 11.33× |
| Korean 2 chars | 1.6 / 2.6 | 3.1 | 3.9 | 3.0 / 4.2 | 4.3 | 1.62× |


(C, D and E at 16 KB pages, Kotlin excerpt ranges. A and B are the first full run; B has the same layout as A on FTS5.
The first call in a fresh process, which pays for cold pages, took 127 ms for `the` in the final production run, against
206 ms in the earlier scale run.)

How the first full run looked before two fixes, because it decided what shipped. With FTS5's `highlight()` building the
excerpts, five queries regressed badly against A (p95, ms): mid-common word 46 → 189, mid prefix 51 → 493, "show all" on a
common prefix 493 → 4,453, and two more. A phase profile showed the cost was `highlight()` itself, about 1 ms a chunk, and
far worse for common words, whichever way the rows were selected (`rowid IN` and `rowid = ?` both); streaming 5,000 ids
costs 3–4 ms and loading 200 chunks 1–2 ms. Computing the ranges in Kotlin from the loaded text took those five to 48, 50
and 216 ms. A second fix precompiled a regex that `foldedTerm` rebuilt on every call (CJK mixed 240 → 59 ms).

Where it is slower than A (D, p95 ms): unique word 4.3 (A 0.5), rare word 7.9 (4.6), rare prefix 7.5 (4.4), 2-word phrase
rare 5.6 (2.4), AND mid 6.8 (4.7), status filter 3.7 (1.2), CJK 3 characters 4.1 (2.9), Korean 4.2 (2.6): all within 3.8 ms,
a fixed floor, since a library query reads about 1,500 books and 1,500 index states (in parallel, 3 ms) where the old query
did that inside SQLite. A cache would remove it but needs tracker-based invalidation that can serve a stale list for a
moment, which was judged not worth a gain below the 250 ms typing debounce. The other three rows are CJK: one character
(5 and 19 ms) is refused by the old index (0 results), and `chapter 天下`, a very common Latin word intersected with a CJK
substring, takes 51 ms against an old 4.5 ms that matched the wrong books.

**Choice: D.** C, D and E are within 0.3% of each other in size at 16 KB; D is 10–25% faster than E on most queries
(excerpts are cut from smaller chunks) and C is 15–30% faster than D but 3% larger and makes 6× more seams. Each of the
three is faster than A on the heavy queries.

### Prefixes

No `prefix=` option was needed. With Kotlin ranges every prefix of the matrix stays at or under 50 ms at the shipped cap:
`hous` 40, `wh` 18, `com` 21, `gre` 47, `sta` 7, `th` 32 (the very common ones are matched exactly by the guard, as before).

### Counting cap

Streaming costs about 0.4 µs a match for a word and about 1 µs for a phrase or a filtered scan. D at 16 KB, p95 ms, `*`
= capped:

| Query | 5,000 | 20,000 | 50,000 | 100,000 | 200,000 | 400,000 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| common typed | 34* | 51* | 59* | 77* | 103* | 166* |
| common exact | 11* | 28* | 39* | 54* | 88* | 154* |
| mid-common word | 24* | 49 | 48 | 48 | 47 | 48 |
| 2-word phrase common | 15* | 43* | 67* | 129* | 211* | 354 |
| AND common tail | 47* | 75 | 76 | 75 | 76 | 75 |
| filter broad tag common | 12* | 40* | 84* | 147* | 241 | 249 |

**`MAX_COUNTED_PASSAGES = 50,000`**, the largest cap whose slowest capped query (filter broad tag common, 84 ms) stays
under 100 ms; it matches the 50,000-match limit for relevance ranking. The 5-word phrase of very common words takes 106 ms
at any cap (the old index: 213 ms); that is intersection cost, not the cap.

## Result sets (matching books, no caps, no guard, against A)

- **B** equals A on all 21 unfiltered queries.
- **C, D, E**: no unexpected differences. The books only A finds are all AND queries whose words fall in adjacent chunks
  (`king the` 1, `whale ship` 1–2): the documented cross-chunk case; each was confirmed by indexing the two adjacent
  chunks together and finding the match only there.
- Books only the new variants find: **+2 for `whale`, +1 for `king`** (and +1 for two AND queries): the books whose text the
  old size cap cut short are now indexed further (48 → 4 truncated). CJK: 14 books for one common character and 5 for
  another where A found none, and one more for a mixed query.

## Page size (the chunk table was 30% larger than its text)

| | 4 KB | 8 KB | 16 KB |
| --- | ---: | ---: | ---: |
| E | 1,766 MB | 1,617 MB | 1,562 MB |
| D | 1,809 MB | – | 1,566 MB |
| C | 1,728 MB | – | 1,617 MB |

Query timings at 16 KB are within 1 ms of 4 KB for all three variants (warm; cold reads were not separately measured).

## Housekeeping at scale (D, 16 KB, 1,512 books)

| Operation | Before fixes | Now |
| --- | ---: | ---: |
| Remove 100 books, then reclaim | 2.1 s, file unchanged (a bug, below) | 1.9 s + 0.23 s, file −69 MB |
| Replace the largest book (4,880 chunks) | 1.0 s | 0.9 s |
| Merge to fastest form | 4 steps, 0.35 s | 4 steps, 0.32 s |
| Clear everything | 1.7 s, then 1.6 GB of WAL left | 2.0 s + 4.2 s reclaim, file 0.3 MB, no WAL |
| Old index: clear + rebuild (previous FTS4) | 21.6 s | – |

Two storage bugs were found here, both invisible to the functional tests:

1. The bundled SQLite is compiled with `SECURE_DELETE`, so freeing a page overwrote it with zeros: clearing the index wrote
   1.57 GB into the WAL. `secure_delete` is now off per connection, and `journal_size_limit` caps the WAL.
2. `PRAGMA incremental_vacuum` returns one row per page it frees; the helper stepped a statement once, so it vacuumed one
   page (16 KB) and left the file at full size. Statements now run to completion and a truncating checkpoint follows.
   `IndexStoreTest` now asserts that the file shrinks.

### Removing the old index from `quire.db`

A 4.27 GB FTS4 index, one transaction: **64.6 s** of continuous write lock (it walks every overflow page). In slices:

| Slice | Slices | Longest lock | Median | Total |
| --- | ---: | ---: | ---: | ---: |
| 5,000 rows | 226 | 1.74 s | 0.14 s | 75 s |
| **2,000 rows (shipped)** | 565 | 1.04 s | 0.08 s | 116 s, half of it the pauses |

Slices run in the indexer's batches, stop at once when a reader opens or the index is cleared, and resume next time.

## Production code at scale (real indexer, `quire-index.db`)

The final end-to-end run, with the shipped code and constants: scan, the real `LibraryIndexer` into the app's own databases,
merge, integrity checks, the matrix through the real `TextSearcher` (default order Relevance), and a clear.

| | Result |
| --- | --- |
| Scan | 9 s, 1,518 added, 5 unreadable |
| Indexing, first pass | **4,569 s (76.1 min)**, 15 five-minute batches (old index: 4,253 s, 15 batches) |
| Outcome | 1,512 done, 1 failed, 0 skipped, **4 truncated** (old run: 1,493 done, 1 failed, 1 skipped, 49 truncated, on 1,500 books) |
| Chunks / seams / text | 730,679 / 7,060 / 965.6 MB (old: 1,107,883 chunks, 1.20 GiB) |
| Database file after a merge | **1,576 MB** (old: 4,255 MB, 3.3× the text); now 1.63× the text |
| Tables | chunk 1,057 MB, chunk_fts 460 MB, cjk_fts 15.7 MB, chunk id index 12.0 MB, docsize 8.0 MB, seam 7.9 MB, book_string 4.6 MB, seam_fts 4.2 MB |
| Merge to the fastest form | 1 step, under a second (automerge had done almost all of it) |
| Integrity | `integrity-check` ok on all three FTS5 tables; 0 books with non-contiguous chunk ids; 0 orphan chunks; FTS rows equal chunk rows (730,679) and seam rows (7,060) |
| Clear everything | **5.9 s**, file 0.3 MB, no WAL (old: 21.6 s) |

The same 730,679 chunks and 7,060 seams came out of the real indexer as out of benchmark variant D, so the benchmark
numbers describe the shipped layout. Wall time is dominated by Readium extraction (the benchmark builds the index itself
from cached text in 142 s, against 244 s for the old layout); the 7% longer wall time than the earlier run is not
attributable to the index and the emulator was shared with other work during both. A first run of the same procedure,
before the storage fixes above, took 4,661 s and its clear left a 1.57 GB WAL.

Queries (warm, 20 runs; the four filter rows are omitted because the app's library has no synthetic authors or tags):

| Query | p50 | p95 | books / snippets (`*` = capped at 50,000) |
| --- | ---: | ---: | --- |
| common typed | 52.9 | 62.8 | 84b/200s* |
| common exact | 40.2 | 40.9 | 84b/200s* |
| mid word | 23.0 | 24.3 | 275b/171s |
| mid-common word | 46.6 | 48.7 | 1046b/200s |
| rare word | 7.7 | 8.3 | 41b/40s |
| unique word | 3.1 | 4.5 | 1b/1s |
| common prefix | 18.3 | 19.2 | 238b/114s |
| rare prefix | 7.8 | 8.0 | 41b/40s |
| mid prefix | 41.5 | 42.8 | 982b/200s* |
| prefix wh | 19.6 | 21.0 | 259b/113s |
| prefix sta | 8.5 | 9.1 | 10b/10s |
| prefix com | 18.7 | 19.4 | 672b/108s |
| prefix gre | 45.7 | 47.2 | 473b/200s* |
| 2 words + prefix | 6.6 | 7.0 | 0b/0s |
| 2-word phrase common | 64.2 | 65.6 | 175b/200s* |
| 2-word phrase rare | 5.6 | 6.2 | 8b/13s |
| 5-word phrase rare | 2.7 | 3.8 | 0b/0s |
| 5-word phrase common | 107.4 | 108.4 | 1112b/176s |
| AND common tail | 73.2 | 74.2 | 892b/200s |
| AND mid | 7.0 | 7.5 | 16b/21s |
| page common prefix | 211.9 | 213.8 | 20 |
| page common exact | 3.1 | 4.5 | 20 |
| CJK 1 char common | 17.6 | 18.2 | 14b/65s |
| CJK 1 char | 4.8 | 5.3 | 5b/16s |
| CJK 2 chars | 6.0 | 6.3 | 11b/51s |
| CJK 3 chars | 3.5 | 4.6 | 2b/10s |
| CJK mixed | 51.1 | 54.3 | 9b/31s |
| Korean 2 chars | 3.6 | 4.7 | 2b/8s |

These agree with the matrix run to within noise, now with exact counts up to 50,000 passages instead of 5,000: `mid-common
word` (1,046 books, uncapped) takes 49 ms against the old index's 46 ms for a capped sample, and `common typed` takes 63 ms
(capped at 50,000) against 100 ms (capped at 5,000).

## Method notes and caveats

- Cold-start numbers are from the first call in a fresh process (no `drop_caches`; the emulator has no root shell here).
- Timings are warm medians/p95 of 20 runs on one emulator that shared CPU with other work at times; differences under about
  2 ms are noise. Real devices will differ (the phone has faster storage and ARM cores); only sizes and result sets transfer exactly.
- Filter rows of the production run read zero books because the app's own library has no synthetic authors or tags; the
  filtered timings come from the matrix run, which used the same synthetic metadata for every variant.
- Another session ran instrumented tests across all attached devices and uninstalled the first benchmark app mid-run,
  which cost the first production run and the extraction cache; later runs used a private application id.
- The benchmark harness and the FTS5 spike were committed on the branch, then removed from the tree; they are in the history
  of `search-index-v2` (`app/src/androidTest/.../bench`, `.../spike`).
