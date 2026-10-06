# Design: search index v2 (FTS5, no overlap, binary mapping, CJK bigrams)

Status: implemented on branch `search-index-v2`. Measurements are in
`2026-10-06-search-index-v2-bench.md`; this document records what was built, what was
decided and why, and where the build departed from the brief.

## What changed

The library text index moved out of `quire.db` into its own database, `quire-index.db`
(`IndexDatabase`, Room 2.8.5 over `androidx.sqlite:sqlite-bundled`), and was redesigned:

| Area | Before | Now |
| --- | --- | --- |
| Engine | FTS4 on the platform SQLite | FTS5 on the bundled SQLite 3.50.1 (`detail=full`, external content) |
| Chunk | 600–900 chars plus 67 tokens copied from the next text | 1,000–1,500 chars, no copied text |
| Phrase across a chunk boundary | found, by the copy | found only across the split of one long element (a `seam` row); otherwise not found |
| Ownership | `offsets()` per match against `primaryEndByte` | none needed: every match is owned |
| Mapping | JSON, one locator per source element | binary: varint char start and quantised progression per segment; href and media type come from `book_string` |
| Book of a match | join to `text_chunk` | a book owns a contiguous chunk-id range kept in `index_state`; `ChunkRanges` assigns by binary search |
| CJK | one useless token per run | bigrams in a contentless FTS5 table (`cjk_fts`), for any chunk containing Han, Hiragana, Katakana or Hangul |
| Ranking | passage count, then last opened | Relevance (BM25 top 300, skipped for very common queries) or Library order; a setting, default Relevance |
| Page size | 4 KB (default) | 16 KB (set before the first table exists) |

## Decisions and the measurements behind them

Chunk size and page size were chosen by benchmark (see the bench document):

- **1,000–1,500 characters.** Variants with 600–900, 1,000–1,500 and 1,333–2,000 characters came out
  within 0.3% of each other in size at 16 KB pages (1.62, 1.57, 1.56 GB); the middle one was 10–25%
  faster than the largest on most queries, so it ships. Larger chunks are not worth their slower
  excerpts.
- **16 KB pages.** With 4 KB pages the chunk table was about 30% larger than the text it holds, because
  1–2 KB rows pack badly into 4 KB pages. 16 KB pages cut the index 11% with no change in query time.
- **No `prefix=` option.** With `highlight()` replaced (below) every prefix of the benchmark stays under
  50 ms, so the extra index size is not spent.
- **`MAX_COUNTED_PASSAGES = 50,000`** (was 5,000): the largest cap that keeps every capped query under 100 ms.
- **Excerpt ranges are found in Kotlin, not with `highlight()`.** FTS5's `highlight()` costs about a
  millisecond a chunk on this index (89 ms for 113 chunks of a mid-common word; 10 s for 50 chunks of a
  very common one, because the lookup walks the doclist whichever way the rows are selected). The chunk text is
  loaded anyway, so `matchRanges` tokenizes it with the same `Tokenizer` and folding the index uses.
  `highlight()` stays as a fallback for a chunk where the two disagree, and for seam rows.

## Corrections to the brief, found while building

1. FTS5 prefix syntax is `"pre"*`, not `"pre*"`; `FtsQuery` changed.
2. `fts3tokenize` does not exist for FTS5; tokenizer parity was checked with `fts5vocab(…, 'instance')`
   over three full novels (3.2 M tokens: no token-count differences). One difference was found and fixed:
   `foldedTerm` decomposed Hangul syllables into jamo, which SQLite keeps whole.
3. `slimLocator()` also keeps the media type, so `book_string` stores it (EPUBs may use `text/html`).
4. SQLite databases from two SQLite builds cannot be `ATTACH`ed. Filters and index state are joined in
   Kotlin (`SearchDao.searchableBooks`, `IndexStateDao.searchable`, read in parallel). Foreign-key cascades
   are gone, so `IndexStore.retainOnly` sweeps removed books before every batch and after a scan.
5. Seams only help a query that is exactly one quoted phrase: that is the one shape whose match range can
   be compared with the split position. A phrase mixed with other terms, and unquoted multi-word
   (AND) queries, do not match across a split.
6. A CJK query is searched in `cjk_fts`, never `chunk_fts`; one CJK character is enough to search.
   The brief's CJK seam (grams of the first 15 characters after a split) turned out to be unnecessary: the
   chunker cuts only between tokens and `unicode61` keeps a whole run of CJK characters as one token, so a run is
   never split across chunks, and a query run (no punctuation or spaces in it) always lies inside one run. A
   long unbroken run is kept whole in one chunk even past the maximum, as any long token is.
7. `synchronous`, `busy_timeout` and the page size are per connection or per file, so a small
   `SQLiteDriver` wrapper (`IndexDriver`) sets them on every open; `PRAGMA` in a Room callback is not
   reliable for that.
8. Dex rejects test method names containing a comma or an apostrophe; none are used in new tests.
9. The old index stays in `quire.db` after the migration (dropping gigabytes of pages would hold its
   write lock inside the migration). `dropLegacyIndex` drops the tables and runs `VACUUM` once, in the
   indexer's first batch.
10. The per-book size cap counted overlap and JSON, so it truncated 48 of 1,500 books; it now counts text,
    mapping and seams and truncates 4. Books that were cut short before are now indexed further.

## Behaviour changes (README documents them)

- A phrase, or two words that must both appear, only matches inside one chunk or across the split of
  one long paragraph; it can miss when the two paragraphs fall in different chunks. A chapter always
  starts a new chunk, so a phrase across a chapter boundary is not found.
- Passage counts are roughly half what they were (chunks are larger); "N+" appears at 50,000, not 5,000.
- Chinese, Japanese and Korean text is searchable by any substring; the Latin index still treats a run
  of such text as one token.
- Release APKs drop 32-bit libraries (`arm64-v8a` and `x86_64` only) and grow by 2.5 MB.

## Known limits

- A mixed query such as `chapter 天下` intersects a very common Latin word with a CJK substring and takes
  about 50 ms.
- The first library query after an invalidation reads about 1,500 books and 1,500 index states
  (3–4 ms); tiny queries therefore cost about 3 ms more than the old single SQL join did.
