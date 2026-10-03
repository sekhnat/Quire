# Scale results: library text index on 1,500 books

Status: COMPLETE. Clean first-index pass on `quire_scale` (2026-10-04 00:26:16 → 01:37:09), query
matrix, load behaviour, rebuild/delete timings and integrity checks all measured on the finished
index. This is the durable record for the 1,500-book verification (`balance_plan.md` item 1).

## Headline

| Metric | Value | Target | Verdict |
|---|---|---|---|
| First-index pass, 1,495 eligible books | **70 min 53 s** wall, 68 min 33 s of worker time | bounded 5-minute batches | pass |
| Books indexed at the end | 1,493 done · 1 failed · 1 skipped · 49 truncated | all readable books done | pass |
| Chunks / indexed text / database | 1,107,883 / 1.20 GiB / 3.96 GiB (+0.77 GiB peak WAL) | — | pass |
| Search queries, warm median | 3–95 ms for every single-concept query | < 100 ms | pass |
| AND of a mid word with a very common word | 126–128 ms | < 100 ms | **miss** (documented) |
| “Show all in this book” on a common typed prefix | 478 ms | < 100 ms | **miss** (by design, documented) |
| Library scrolling while indexing | 0.92% janky, 90th percentile 25 ms | usable | pass |
| Reader open mid-batch | indexing stops in < 1 s, resumes on close | steps aside | pass |
| Kill and resume mid-queue | same work retried, no finished book redone | recover | pass |
| Rebuild (clear + requeue) at full index | 21.6 s | — | pass |
| Delete index (full index, idle chain) | ≈ clearAll 21.6 s; 141 s once measured with a large book mid-extraction | — | pass |
| Peak process memory (PSS) | 278 MB (median 155 MB) | — | pass |

## Environment and fixture

- Emulator AVD `quire_scale` (headless, Pixel 7 profile, 4 cores, 4 GB RAM, KVM), `emulator-5556`.
- Android 16 (API 36), **SQLite 3.44.3**, app installed as `com.quire.reader.dbtest` through
  `tools/dbtest-suffix.init.gradle` (the user's `com.quire.reader` on `emulator-5554` was never
  touched; checked after every test run).
- Fixture: **1,500 books** in a Calibre layout under `/sdcard/Calibre Library`, 1.30 GB on the
  device — 1,054 real EPUBs copied byte-for-byte from `~/Calibre Library` (each with its
  `metadata.opf` and `cover.jpg`) plus 441 derived slice-EPUBs, 435 of them valid.
- **The generator was recreated** (`genfixture.py`, appendix): no copy existed in the repo, git
  history, or the machine. It is throwaway and is not committed. Derived books re-zip a contiguous
  spine slice as a valid EPUB3 with distinct title, id, uuid and file name, and add marker
  paragraphs for frequency buckets: `zorvak777` (1 chunk), `quillfeather` (41 chunks across 41
  books), `the crimson heron sang softly` (6 books).
- All 1,500 books were pushed before the run; the index itself was deleted through the app's own
  Settings action first, so this pass is a true from-zero index (not a resume).

## First-index pass

| Metric | Value |
|---|---|
| Start (indexing toggled on) | 00:26:16; first batch began 00:26:16.6 |
| Drained | 01:37:09.5 (`stop=Drained`, queue empty) |
| Wall clock | **70 min 53 s** |
| Sum of batch runtimes | **68 min 33 s** (4,112,521 ms over 15 batches) |
| Batches | 15: 13 × 5-minute `Deadline`, 1 × `ReaderBusy` (2 m 38 s), 1 × `Drained` (1.5 s) |
| Books per batch | 29 – 196 (small books at the end of the queue are much faster) |
| End state | 1,493 done · 1 failed · 1 skipped (of 1,495 eligible; 5 of the 1,500 files are unreadable=0) |
| Truncated (over the 6 MiB text cap) | 49 books |
| Chunks | 1,107,883 |
| Distinct indexed terms | 216,091 |
| Indexed text (`sum(textBytes)`) | 1,287,160,652 B (1.20 GiB) |
| Database file | 4,255,383,552 B (3.96 GiB); WAL 0 at rest, **789.7 MB peak during indexing** |
| Growth ratio | database ≈ **3.3 ×** indexed text (and ≈ 3.3 × the 1.30 GB of EPUBs) |
| Peak process memory | 278 MB PSS, median 155 MB (318 samples) |
| Failed book | *Harry Potter the Halfblood Auror* (639 KB) — unreadable by Readium, consistent across runs |

The gap between wall clock and worker time (≈ 2 min 21 s) is the reader-pause interval (79 s), the
kill/restart test, and inter-batch scheduling.

## Query timings

Method: `ScaleBench` (instrumented, appendix), 11 runs per invocation; `first` = first timed call
in a fresh process after `drop_caches` as root, warm = median of all runs after the first. The
production constants were used (`MAX_COUNTED_PASSAGES = 5000`, `MAX_PREFIX_DOCUMENTS = 200,000`,
`BROAD_FILTER_PROBE_BOOKS = 8`).

| Query (bucket) | Warm median | Cold first | Capped | Notes |
|---|---|---|---|---|
| `quillfeather` (rare word, 41 chunks) | 9.0 ms | 66 ms | no | 41 books |
| `zorvak777` (unique word, 1 chunk) | 3.5 ms | 9 ms | no | 1 book |
| `whale` (mid word, 1,208 chunks) | 31.8 ms | 179 ms | no | 271 matching books, 40 shown |
| `the` (common word, 1.10 M chunks) | **93.8 ms** | 206 ms | yes | typed prefix → guard downgrades to the exact word; “918+” |
| `th` (typed common prefix) | **32.1 ms** | 134 ms | no | downgraded to exact `th` (738 chunks, 235 books) |
| `quillf` (rare prefix) | 9.3 ms | 67 ms | no | prefix stays a prefix |
| `"of the"` (quoted phrase, common) | 40.3 ms | 121 ms | yes | 20 books, “666+” |
| `"van helsing"` (quoted phrase, rare) | 4.3–9.6 ms | 46 ms | no | 8 books |
| `king the` (AND of terms, common tail) | **126–128 ms** | 445 ms | yes | **target miss**, see below |
| `whale` + author `Michael Connelly` (55 books) | 7.1 ms | 107 ms | no | broad filter path, 6 books |
| `whale` + author `Stephen King` (34 books) | 30.6 ms | 73 ms | no | book-by-book path, 16 books |
| `whale` + tag `F/F` (387 books) | 20.9 ms | 108 ms | no | broad filter path, 37 books |
| `whale` + status `Reading` (2 books, 0 matches) | 3.0 ms | 12 ms | no | narrow filter |
| “Show all in this book”, book 1242, `the` | **477.8 ms** | 499 ms | — | **target miss**, see below |
| “Show all in this book”, book 1242, `"the"` | 15.7 ms | 57 ms | — | exact-word page is fast |
| `king` (exact word, reference) | 49.2 ms | 189 ms | yes | 205 matching books |
| `"the"` quoted (reference, no guard) | 42.5 ms | 111 ms | yes | isolates the guard’s cost |

### Why the two misses, and why the constants are not the lever

Diagnostics run with the same harness (cap and match variants) against the finished index:

- **`king the` (126 ms).** The query is `king` AND the exact word `the` (the guard downgraded the
  final typed word). Breakdown: ≈ 53 ms is the common-prefix guard’s `fts4aux` scan
  (measured in isolation: quoted `"the"` 42.5 ms vs unguarded `the` 93.8 ms), ≈ 50 ms is the
  per-row pipeline (≈ 10 µs per examined passage × the 5,000 cap — measured across caps 200 →
  5,000), the rest is the AND intersection. The pure SQL is fast (the Room-shaped query runs in
  20 ms; the raw FTS intersection in 13 ms).
- **Cap tuning does not reach the target.** `king the` measured 112.7 ms even at cap 2,000 and
  112.8 ms at cap 3,000 (vs 128 ms at 5,000) — the fixed guard cost keeps it above 100 ms while
  halving the sample. `the` itself stays within target at 5,000 (93.8 ms) and would only show
  fewer books at a lower cap (2 books at cap 1,000 vs 9 at cap 5,000). The cap was **left at
  5,000**.
- **`MAX_PREFIX_DOCUMENTS` (200,000)** is not the lever either: the guard already triggers for
  every measured common prefix; lowering it would turn useful mid-frequency prefix searches
  (`house*`, 75k chunks) into exact matches with no timing gain.
- **`BROAD_FILTER_PROBE_BOOKS` (8)** measured well: every filtered query is 3–31 ms because a
  common word fills the cap inside a few books.

Decision: **no constant changes.** The two misses are recorded here as known shapes with their
measured causes. Follow-up options (not done, each a product decision): cache the guard’s
per-prefix answer across queries; build snippets lazily; extend the guard to single-book paging
(below); lower the cap accepting fewer listed books for very common queries.

### The “Show all in this book” page

The page path deliberately keeps a typed prefix (design: “single-book paging may still use the
prefix”; test: *“only a final unfinished word is ever downgraded and a book page keeps using the
prefix”*). For a prefix of a very common word that means the unguarded FTS prefix merge:
`the*` = 478 ms warm, versus 15.7 ms for the exact word. At 505k chunks the same shape measured
217 ms, so it scales with the index. A library search for the same input shows the downgraded
exact matches, so the page can list different (prefix) matches than the list the user came from.
Both the cost and the inconsistency are consequences of that design choice; changing it is left
for a follow-up decision.

## Behaviour under load (measured during the first-index pass)

| Test | Result |
|---|---|
| Library scrolling while indexing (`fling`, `dumpsys gfxinfo`) | 1,092 frames, 10 janky (0.92%); 50th 16 ms, 90th 25 ms, 95th 26 ms, 99th 28 ms; 0 missed vsync / slow UI / slow bitmap |
| Reader opened mid-batch | batch ended `stop=ReaderBusy processed=30 ms=157638` within 1 s of the reader opening; in-flight book abandoned, not written |
| Indexing while a reader is open | done count frozen at 30 for the ~80 s the reader stayed open |
| Resuming after the reader closed | new batch started 1.2 s later |
| Reader while indexing is paused | page 1 of 299 rendered and page turns worked; no ANR (12 frames over 5 taps — a tiny sample, not a frame-rate benchmark) |
| Kill + resume on the large queue | `am force-stop` mid-batch at 92 done; on relaunch WorkManager retried the same work item (`attempt=1`) and progress continued (92 → 107 within 25 s) with no finished book redone |
| Integrity after the pass | chunks = FTS docsize rows = 1,107,883; done-state `chunkCount` sums to 1,107,883; 0 books with chunks but no done state; 0 duplicate `(bookId, seq)`; 0 non-contiguous books; **`fts4 integrity-check: ok`** |

## Delete and rebuild

| Action | Scale | Duration | Notes |
|---|---|---|---|
| “Turn off and delete index” | 788k chunks, large book mid-extraction | **141 s** | ≈ 120 s waiting for the in-flight book to yield, then clearAll |
| “Turn off and delete index” | 28 books / 21k chunks, batch mid-book | **1.5 s** | batch cancelled, index cleared |
| “Rebuild index” | **1,107,883 chunks**, idle chain | **21.6 s** | clear + requeue; re-indexing began 0.2 s after the clear; all books and reading state kept |

Earlier extrapolation (26 s at 1.17 M chunks) is replaced by the measured 21.6 s at 1.108 M chunks.
A delete with an idle chain on a full index is therefore clearAll (21.6 s) plus the (near-zero)
cancellation wait.

## Known-unverified (carry into release notes)

- Query SQL on SQLite 3.28 (API 30 devices). This run used SQLite 3.44.3. The query SQL was
  audited: it uses plain SELECT/COUNT/COALESCE/LIMIT and subqueries — no `MATERIALIZED`, window
  functions, `RETURNING`, upserts, `json_*` or recursive CTEs — but it has not been executed on
  3.28.
- A worker stopped by the system when the device unplugs mid-run (expected to behave like the
  cancelled path measured here).
- The “at least N matching books” result-copy variant. The `PausedForReader` label itself is only
  shown on a screen the open reader covers, so only its state transition is verified end to end
  (the copy is unit-tested).
- Fixed-layout and RTL EPUBs; performance on real phone hardware (this is an emulator); the
  library-search overlay’s scroll-driven load-more through the real `LazyColumn` (the paging
  function is tested directly).
- A Room query already running is not cancelled when newer input arrives (its result is ignored);
  the reactive search re-queries on each finished book while a search is open during indexing.
- `offsetTopForId` in the vendored `ChapterWebView` returns dp where neighbouring code returns px
  (latent, untouched).

## Appendix A: fixture generator (`genfixture.py`, throwaway, run once)

```python
#!/usr/bin/env python3
"""Throwaway scale-fixture generator (recreated; NOT committed).

Builds /tmp/.../scale/fixture/Calibre Library with:
  * every EPUB of the user's real Calibre library, copied byte-for-byte with its metadata.opf and cover.jpg
  * N derived EPUBs (default 446, total 1500): a contiguous slice of a real book's spine, rebuilt as a valid EPUB3
    (mimetype, container.xml, content.opf, nav.xhtml, toc.ncx, text-only xhtml), distinct title/id/uuid/file name,
    plus injected marker paragraphs for query tests.
"""
import glob, os, random, re, shutil, sys, uuid, zipfile, html
from multiprocessing import Pool
from xml.etree import ElementTree as ET

SRC = "/home/caan9/Calibre Library"
OUT = sys.argv[1]
TOTAL = 1500
rng = random.Random(20261003)

def local(tag): return tag.rsplit('}', 1)[-1]

def read_epub_spine(path):
    z = zipfile.ZipFile(path)
    cont = ET.fromstring(z.read('META-INF/container.xml'))
    opf_path = next(e.get('full-path') for e in cont.iter() if local(e.tag) == 'rootfile')
    base = os.path.dirname(opf_path)
    opf = ET.fromstring(z.read(opf_path))
    manifest = {}
    for e in opf.iter():
        if local(e.tag) == 'item':
            manifest[e.get('id')] = (e.get('href'), e.get('media-type'))
    spine = [e.get('idref') for e in opf.iter() if local(e.tag) == 'itemref']
    docs = []
    for idref in spine:
        if idref not in manifest: continue
        href, mt = manifest[idref]
        if mt not in ('application/xhtml+xml', 'text/html'): continue
        from urllib.parse import unquote
        name = os.path.normpath(os.path.join(base, unquote(href))).replace('\\', '/')
        try:
            docs.append(z.read(name))
        except KeyError:
            pass
    return docs

IMG = re.compile(rb'<img\b[^>]*>', re.I | re.S)
SVG = re.compile(rb'<svg\b.*?</svg>', re.I | re.S)
LINK = re.compile(rb'<link\b[^>]*>', re.I | re.S)
BODY = re.compile(rb'<body\b[^>]*>(.*)</body>', re.I | re.S)
TITLE = re.compile(rb'<h[1-3][^>]*>(.*?)</h[1-3]>', re.I | re.S)
TAGS = re.compile(rb'<[^>]+>')

def body_of(doc):
    m = BODY.search(doc)
    inner = m.group(1) if m else doc
    inner = SVG.sub(b'', IMG.sub(b'', inner))
    return inner

def heading_of(inner, n):
    m = TITLE.search(inner)
    if m:
        t = html.unescape(TAGS.sub(b'', m.group(1)).decode('utf-8', 'replace')).strip()
        t = re.sub(r'\s+', ' ', t)
        if t: return t[:80]
    return f"Part {n}"

def esc(s): return html.escape(s, quote=True)

def build_derived(args):
    idx, src_epub, src_dir, calibre_id, markers = args
    try:
        docs = read_epub_spine(src_epub)
    except Exception:
        return None
    docs = [body_of(d) for d in docs]
    docs = [d for d in docs if len(TAGS.sub(b'', d).strip()) > 200]
    if len(docs) < 3: return None
    r = random.Random(idx * 7919 + 13)
    n = len(docs)
    span = max(2, int(n * r.uniform(0.3, 0.8)))
    start = r.randint(0, n - span)
    chosen = docs[start:start + span]
    # source metadata
    opf_src = os.path.join(src_dir, 'metadata.opf')
    meta = open(opf_src, encoding='utf-8').read() if os.path.exists(opf_src) else None
    title_m = re.search(r'<dc:title>(.*?)</dc:title>', meta or '', re.S)
    src_title = html.unescape(title_m.group(1)) if title_m else os.path.basename(src_dir)
    title = f"{src_title} (Excerpt {idx})"
    author_m = re.search(r'<dc:creator[^>]*>(.*?)</dc:creator>', meta or '', re.S)
    author = html.unescape(author_m.group(1)) if author_m else 'Unknown'
    uid = str(uuid.UUID(int=r.getrandbits(128)))
    chapters = []
    for i, inner in enumerate(chosen, 1):
        h = heading_of(inner, i)
        extra = b''
        if i == 1 and markers:
            extra = ''.join(f'<p>{esc(m)}</p>' for m in markers).encode()
        chapters.append((f"c{i:03d}.xhtml", h, extra + inner))
    def xhtml(h, inner):
        return (b'<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><meta charset="utf-8"/><title>'
                + esc(h).encode() + b'</title></head><body>' + inner + b'</body></html>')
    manifest = ''.join(f'<item id="c{i:03d}" href="{fn}" media-type="application/xhtml+xml"/>' for i, (fn, _, _) in enumerate(chapters, 1))
    spine = ''.join(f'<itemref idref="c{i:03d}"/>' for i in range(1, len(chapters) + 1))
    opf = (f'<?xml version="1.0" encoding="utf-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">'
           f'<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="bid">urn:uuid:{uid}</dc:identifier>'
           f'<dc:title>{esc(title)}</dc:title><dc:creator>{esc(author)}</dc:creator><dc:language>en</dc:language>'
           f'<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest>'
           f'<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
           f'<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>{manifest}</manifest>'
           f'<spine toc="ncx">{spine}</spine></package>')
    nav = ('<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>'
           '<nav epub:type="toc"><ol>' + ''.join(f'<li><a href="{fn}">{esc(h)}</a></li>' for fn, h, _ in chapters) + '</ol></nav></body></html>')
    ncx = ('<?xml version="1.0" encoding="utf-8"?>\n<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:uuid:' + uid + '"/></head>'
           f'<docTitle><text>{esc(title)}</text></docTitle><navMap>'
           + ''.join(f'<navPoint id="n{i}" playOrder="{i}"><navLabel><text>{esc(h)}</text></navLabel><content src="{fn}"/></navPoint>' for i, (fn, h, _) in enumerate(chapters, 1))
           + '</navMap></ncx>')
    safe_title = re.sub(r'[^A-Za-z0-9 ._-]+', '_', src_title)[:40]
    safe_author = re.sub(r'[^A-Za-z0-9 ._-]+', '_', author)[:40]
    bookdir = os.path.join(OUT, 'Calibre Library', safe_author, f"{safe_title} (Excerpt {idx}) ({calibre_id})")
    os.makedirs(bookdir, exist_ok=True)
    epub_path = os.path.join(bookdir, f"{safe_title} (Excerpt {idx}) - {safe_author}.epub")
    with zipfile.ZipFile(epub_path, 'w') as z:
        z.writestr(zipfile.ZipInfo('mimetype'), b'application/epub+zip', compress_type=zipfile.ZIP_STORED)
        z.writestr('META-INF/container.xml', '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>', compress_type=zipfile.ZIP_DEFLATED)
        z.writestr('content.opf', opf, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr('nav.xhtml', nav, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr('toc.ncx', ncx, compress_type=zipfile.ZIP_DEFLATED)
        for fn, h, inner in chapters:
            z.writestr(fn, xhtml(h, inner), compress_type=zipfile.ZIP_DEFLATED)
    # metadata.opf: the source's, with new title, ids and uuid (same author, series and tags)
    if meta:
        m = meta
        m = re.sub(r'(<dc:identifier opf:scheme="calibre" id="calibre_id">)\d+(</dc:identifier>)', rf'\g<1>{calibre_id}\2', m)
        m = re.sub(r'(<dc:identifier opf:scheme="uuid" id="uuid_id">)[^<]*(</dc:identifier>)', rf'\g<1>{uid}\2', m)
        m = re.sub(r'<dc:title>.*?</dc:title>', f'<dc:title>{esc(title)}</dc:title>', m, flags=re.S)
        m = re.sub(r'(<meta name="calibre:title_sort" content=")[^"]*(")', rf'\g<1>{esc(title)}\2', m)
        open(os.path.join(bookdir, 'metadata.opf'), 'w', encoding='utf-8').write(m)
    cover = os.path.join(src_dir, 'cover.jpg')
    if os.path.exists(cover):
        shutil.copyfile(cover, os.path.join(bookdir, 'cover.jpg'))
    return epub_path

def main():
    real = sorted(glob.glob(SRC + '/**/*.epub', recursive=True))
    print("real epubs", len(real))
    root = os.path.join(OUT, 'Calibre Library')
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(root)
    # 1. copy the real library (epub + metadata.opf + cover.jpg per book directory)
    dirs = sorted({os.path.dirname(f) for f in real})
    for d in dirs:
        rel = os.path.relpath(d, SRC)
        dst = os.path.join(root, rel)
        os.makedirs(dst, exist_ok=True)
        for f in os.listdir(d):
            p = os.path.join(d, f)
            if os.path.isfile(p) and (f.endswith('.epub') or f in ('metadata.opf', 'cover.jpg')):
                shutil.copyfile(p, os.path.join(dst, f))
    # 2. derived books
    need = TOTAL - len(real)
    good = []
    for f in real:
        try:
            if os.path.getsize(f) > 0 and os.path.getsize(f) < 20_000_000: zipfile.ZipFile(f).namelist(); good.append(f)
        except Exception: pass
    rng.shuffle(good)
    jobs = []
    # marker plan: derived #0 holds the unique rare word; 41 hold "quillfeather"; 6 hold the phrase
    for i in range(need):
        src = good[i % len(good)]
        markers = []
        if i == 0: markers.append("A single zorvak777 was found carved into the lintel.")
        if 1 <= i <= 41: markers.append("The quillfeather drifted down through the lamplight and settled on the open page.")
        if 100 <= i < 106: markers.append("At dusk, the crimson heron sang over the water and nobody spoke.")
        jobs.append((i, src, os.path.dirname(src), 5000 + i, markers))
    with Pool(16) as p:
        res = p.map(build_derived, jobs, chunksize=4)
    made = [r for r in res if r]
    print("derived made", len(made), "of", need)
    # top up if some sources failed
    extra = need - len(made)
    k = need
    while extra > 0:
        src = good[k % len(good)]
        r = build_derived((k, src, os.path.dirname(src), 5000 + k, []))
        k += 1
        if r: extra -= 1
    total = len(glob.glob(root + '/**/*.epub', recursive=True))
    print("total epubs", total)

if __name__ == "__main__":
    main()
```

## Appendix B: measurement scripts

`monitor.sh` — sampled every 15 s while the pass ran:

```bash
#!/bin/bash
# samples every N seconds: time, db/wal bytes, book/index_state counts, PSS
A=/tmp/claude-1000/-home-caan9-Projects-AndroidApps-Quire/80ff28f9-bf10-48fc-8715-c3d5eb28b6a3/scratchpad/scale/a
INT=${1:-15}
while true; do
  T=$(date +%H:%M:%S)
  SZ=$($A shell "run-as com.quire.reader.dbtest sh -c 'stat -c %s databases/quire.db databases/quire.db-wal 2>/dev/null | tr \"\\n\" \" \"'")
  CT=$($A shell "run-as com.quire.reader.dbtest sqlite3 databases/quire.db \"select (select count(*) from book),(select count(*) from index_state where status='done'),(select count(*) from index_state where status='failed'),(select count(*) from index_state where status='skipped'),(select count(*) from text_chunk)\"" 2>&1 | head -1)
  PID=$($A shell pidof com.quire.reader.dbtest | tr -d '\r')
  PSS=""
  [ -n "$PID" ] && PSS=$($A shell "dumpsys meminfo $PID | grep -E 'TOTAL PSS|TOTAL:' | head -1" | awk '{print $3}')
  echo "$T sizes[$SZ] book|done|failed|skipped|chunks=$CT pss_kb=$PSS"
  sleep $INT
done
```

`fling.sh` — library scroll + gfxinfo:

```bash
#!/bin/bash
A=/tmp/claude-1000/-home-caan9-Projects-AndroidApps-Quire/80ff28f9-bf10-48fc-8715-c3d5eb28b6a3/scratchpad/scale/a
$A shell dumpsys gfxinfo com.quire.reader.dbtest reset >/dev/null
for i in 1 2 3 4 5 6; do $A shell input swipe 540 2000 540 500 180; $A shell input swipe 540 2000 540 500 180; sleep 1.2; $A shell input swipe 540 500 540 2000 180; sleep 1.2; done
sleep 1
$A shell dumpsys gfxinfo com.quire.reader.dbtest | grep -E "Total frames|Janky|50th|90th|95th|99th|Number Missed|Number Slow UI|Number Slow bitmap|Number Slow issue|Number Frame deadline"
```

`integrity.sh` — post-pass consistency:

```bash
#!/bin/bash
A=/tmp/claude-1000/-home-caan9-Projects-AndroidApps-Quire/80ff28f9-bf10-48fc-8715-c3d5eb28b6a3/scratchpad/scale/a
Q() { $A shell "run-as com.quire.reader.dbtest sqlite3 databases/quire.db \"$1\""; }
echo "chunks: $(Q 'select count(*) from text_chunk')  fts docsize rows: $(Q 'select count(*) from text_chunk_fts_docsize')"
echo "done states: $(Q "select count(*) from index_state where status='done'")  sum(chunkCount) of done: $(Q "select coalesce(sum(chunkCount),0) from index_state where status='done'")"
echo "books with chunks but no done state: $(Q "select count(distinct bookId) from text_chunk where bookId not in (select bookId from index_state where status='done')")"
echo "done states whose real chunk count differs: $(Q "select count(*) from index_state s where s.status='done' and s.chunkCount != (select count(*) from text_chunk c where c.bookId=s.bookId)")"
echo "duplicate (bookId,seq): $(Q 'select count(*) from (select bookId,seq,count(*) n from text_chunk group by 1,2 having n>1)')"
echo "non-contiguous books (max(seq)+1 != count): $(Q 'select count(*) from (select bookId from text_chunk group by bookId having max(seq)+1 != count(*) or min(seq)!=0)')"
echo "fts integrity-check: $(Q "insert into text_chunk_fts(text_chunk_fts) values('integrity-check'); select 'ok'" 2>&1 | tail -1)"
```

Query matrix runner (`run_queries.sh`), one fresh instrumentation process per query; cold runs drop
caches first as root:

```bash
#!/bin/bash
A="adb -s emulator-5556"
RUNNER=com.quire.reader.dbtest.test/androidx.test.runner.AndroidJUnitRunner
CLASS=com.quire.reader.scale.ScaleBench
RUNS=11
run() { # label cold(0/1) extra args...
  local label=$1 cold=$2; shift 2
  [ "$cold" = 1 ] && $A shell 'sync; echo 3 > /proc/sys/vm/drop_caches' >/dev/null 2>&1
  $A logcat -c >/dev/null 2>&1
  $A shell am instrument -w -e class $CLASS -e runs $RUNS -e label "$label" "$@" $RUNNER >/dev/null 2>&1
  $A logcat -d -s ScaleBench 2>/dev/null | grep "ScaleBench: \[$label\]" | sed 's/.*ScaleBench: //'
}
# multi-word arguments are passed quoted through the device shell, e.g. "'\"of the\"'" or "'king the'"
```

## Appendix C: `ScaleBench` (instrumented harness, deleted from the tree after this run)

```kotlin
package com.quire.reader.scale // SCALE-PROBE: scratch benchmark, deleted after the scale run

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.FtsQuery
import com.quire.reader.data.index.TextSearchFilters
import com.quire.reader.data.index.TextSearcher
import com.quire.reader.data.index.TextStatusFilter
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Times TextSearcher against the app's real quire.db. Args: q, runs, author, series, tag, status, page (book id), cap, probe, prefixdocs. */
class ScaleBench {
  @Test fun run() = runBlocking {
    val a = InstrumentationRegistry.getArguments()
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val db = QuireDatabase.create(target, target.getDatabasePath("quire.db").absolutePath)
    try {
      val searcher = TextSearcher(
        db,
        maxExamined = a.getString("cap")?.toInt() ?: com.quire.reader.data.index.MAX_COUNTED_PASSAGES,
        probeBooks = a.getString("probe")?.toInt() ?: TextSearcher.BROAD_FILTER_PROBE_BOOKS,
        maxPrefixDocuments = a.getString("prefixdocs")?.toInt() ?: com.quire.reader.data.index.MAX_PREFIX_DOCUMENTS,
      )
      val filters = TextSearchFilters(a.getString("author"), a.getString("series"), a.getString("tag"), a.getString("status")?.let { TextStatusFilter.valueOf(it) })
      val runs = a.getString("runs")?.toInt() ?: 11
      val page = a.getString("page")?.toLong()
      val label = a.getString("label") ?: a.getString("q")!!
      // Open the database and warm the JIT with a query that matches nothing, so the first timed call is the query's own cost.
      val warm = FtsQuery.parse("zzqxjwv") as FtsQuery.Result.Query
      runCatching { searcher.search(warm, TextSearchFilters.None) }
      val parsed = FtsQuery.parse(a.getString("q")!!)
      val q = parsed as? FtsQuery.Result.Query ?: run { Log.i("ScaleBench", "[$label] not searchable: $parsed"); return@runBlocking }
      val times = ArrayList<Double>()
      var summary = ""
      repeat(runs) {
        val t0 = System.nanoTime()
        if (page != null) {
          val p = searcher.page(q, page)
          summary = "page snippets=${p.snippets.size} next=${p.nextAfterSeq}"
        } else {
          val r = searcher.search(q, filters)
          summary = "books=${r.books.size} matchingBooks=${r.matchingBooks} capped=${r.capped} incomplete=${r.incomplete} downgraded=${r.prefixDowngraded} top=${r.books.firstOrNull()?.passages?.label}"
        }
        times += (System.nanoTime() - t0) / 1e6
      }
      val rest = times.drop(1).sorted()
      val median = if (rest.isEmpty()) Double.NaN else rest[rest.size / 2]
      Log.i("ScaleBench", "[$label] first=%.1fms warmMedian=%.1fms min=%.1f max=%.1f n=%d | %s | match=%s".format(times[0], median, rest.firstOrNull() ?: 0.0, rest.lastOrNull() ?: 0.0, times.size, summary, q.match))
    } finally {
      db.close()
    }
  }
}
```
