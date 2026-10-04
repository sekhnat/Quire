# HTML Normalisation and Partial-Coverage Reporting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Books whose XHTML has a self-closing `<title/>` (or another self-closing raw-text element) are indexed in full, and books with chapters that cannot be read are reported as partly indexed (or `failed` when nothing is readable), with no change to healthy books.

**Architecture:** The indexer stops calling `publication.content()` and builds Readium's own `PublicationContentIterator` with a custom `ResourceContentIteratorFactory`. That factory feeds each HTML resource to Readium's `HtmlResourceContentIterator` through a `TransformingResource` that rewrites `<title/>`-style tags, and it keeps a per-resource tally (bytes, read failure, characters yielded). Tallies decide `failed` vs `done` and fill a new `index_state.unreadableResources` column (schema v3). An `IndexGap` value carries the cause to the UI notes.

**Tech Stack:** Kotlin, Readium kotlin-toolkit 3.3.0 (pinned), jsoup, Room 2.8.5 (KSP), Jetpack Compose, JUnit 4, AndroidX instrumented tests.

**Spec:** `docs/superpowers/specs/2026-10-04-fallback-extractor-design.md`

## Global Constraints

- Readium stays pinned at **3.3.0**; do not bump Readium, Coil, AGP or compileSdk.
- Build with JDK 21 through the Android env. Every Gradle command in this plan is run as `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew …'` from `/home/caan9/Projects/Quire`.
- Inside a git-worktree session the shell refuses `zsh -fc 'source …'` and `$(…)`. Run Gradle as the plain command `env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/home/caan9/Android/Sdk ./gradlew <tasks>`, read this plan's `zsh -fc …` lines that way, and keep every shell command plain.
- **Test documents must be large.** jsoup 1.22.2 recovers from a self-closing `<title/>` in a document of about 2 KB or less and swallows most of the start of a larger one into the title (measured: 2,040 chars lose half the paragraphs; the real *Juliet Takes a Breath* file, 450 KB, keeps 184 characters of body text). Any test that must show the bug needs a document of at least 3 KB, with what it looks for near the start.
- Commit trailer: end every commit message with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` (the commit commands below show an older trailer; use this one).
- Instrumented tests run **only** with `--init-script tools/dbtest-suffix.init.gradle` (app id `com.quire.reader.dbtest`). Never run plain `connectedDebugAndroidTest`: it would wipe the user's installed `com.quire.reader`. With two emulators running, prefix the command with `ANDROID_SERIAL=<serial>`.
- `MIGRATION_1_2` is not edited. The new column arrives by `MIGRATION_2_3` (`ALTER TABLE`) at database version **3**.
- `IndexStateEntity.truncated` keeps meaning only "hit the 6 MiB cap".
- A resource with nothing to repair must reach Readium byte-identical to today (return the original `Try`, not a re-encoding).
- Sparse-resource detection only logs (`Log.i`, tag `LibraryIndexer`); it never changes an outcome. Thresholds: at least **2,048 bytes** and fewer characters than **2%** of bytes.
- No automatic re-extraction of already-indexed books; users run Settings → Rebuild index.
- Code style: 2-space indent, KDoc on every public declaration, comments in the plain declarative voice of the surrounding files.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/quire/reader/data/index/HtmlNormalizer.kt` (new) | Pure `normalizeHtml()`: rewrites self-closing raw-text elements. JVM-testable. |
| `app/src/main/java/com/quire/reader/data/index/IndexContent.kt` (new) | Readium plumbing: `IndexContent` (iterator + tallies), `ResourceTally`, the tracking factory, normalising resource, counting iterator, publication-backed container. |
| `data/index/IndexPolicy.kt` | `Extracted.Text.unreadableResources`, pure `extracted()` and `isSparse()`. |
| `data/index/ResourceOrder.kt` | Parses `normalizeHtml(html)` so anchors line up with what Readium saw. |
| `data/index/LibraryIndexer.kt` | Uses `IndexContent`, logs sparse resources, records `unreadableResources`. |
| `data/db/Entities.kt`, `data/db/QuireDatabase.kt` | Column + v3 migration. |
| `data/db/Daos.kt`, `data/db/SearchDao.kt` | Coverage `partial`, `indexedBooks` selects the new column. |
| `data/index/TextSearchResult.kt`, `data/index/TextSearcher.kt` | `IndexGap` and its propagation. |
| `ui/BookSearch.kt`, `ui/TextSearchPresentation.kt`, `ui/library/TextSearchResults.kt`, `ui/reader/ReaderSheets.kt` | Cause-specific notes. |

Paths below are relative to `/home/caan9/Projects/Quire`; `main/` means `app/src/main/java/com/quire/reader/`, `test/` means `app/src/test/java/com/quire/reader/`, `androidTest/` means `app/src/androidTest/java/com/quire/reader/`.

---

### Task 1: `normalizeHtml` and `ResourceOrder` uses it

**Files:**
- Create: `main/data/index/HtmlNormalizer.kt`
- Create: `test/data/index/HtmlNormalizerTest.kt`
- Modify: `main/data/index/ResourceOrder.kt` (doc comment line ~13-15, `parse` line ~41)
- Modify: `test/data/index/ResourceOrderTest.kt` (append a test)

**Interfaces:**
- Produces: `fun normalizeHtml(html: String): String` (package `com.quire.reader.data.index`), returning the same instance when nothing matched.

- [ ] **Step 1: Write the failing tests**

Create `test/data/index/HtmlNormalizerTest.kt`:

```kotlin
package com.quire.reader.data.index

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlNormalizerTest {
  @Test fun `a self-closing title is written as a pair however it is spelled`() {
    assertEquals("<head><title></title></head>", normalizeHtml("<head><title/></head>"))
    assertEquals("<head><TITLE ></TITLE></head>", normalizeHtml("<head><TITLE /></head>"))
    assertEquals("""<title id="x"></title>""", normalizeHtml("""<title id="x"/>"""))
  }

  @Test fun `every raw-text element is repaired, attributes and slashes in values kept`() {
    for (tag in listOf("script", "style", "textarea", "xmp", "iframe", "noembed", "noframes")) {
      assertEquals("""<$tag src="a/b.js"></$tag>""", normalizeHtml("""<$tag src="a/b.js"/>"""))
    }
  }

  @Test fun `void elements, closed titles and look-alike names are left alone`() {
    val html = """<head><title>Book</title><meta charset="utf-8"/><link href="s.css"/></head><body><p>a<br/>b</p><img src="i.png"/><titles/><subtitle/></body>"""
    assertSame(html, normalizeHtml(html))
  }

  @Test fun `a document with nothing to repair is returned as the same instance`() {
    val html = "<html><head><title>T</title></head><body><p>Text</p></body></html>"
    assertSame(html, normalizeHtml(html))
  }

  // jsoup recovers from `<title/>` in a document of about 2 KB or less and swallows most of the body in a larger one (measured
  // on jsoup 1.22.2: 2,040 chars lose half the paragraphs; the real book *Juliet Takes a Breath* keeps 184 of 369,000 characters
  // of body text). The body below is therefore far past that size.
  @Test fun `after normalising, jsoup finds the body text a self-closing title used to swallow`() {
    val body = (1..200).joinToString("") { "<p>Paragraph $it of the chapter, long enough to count.</p>" }
    val html = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title/></head><body>$body</body></html>"""
    assertTrue("jsoup's HTML mode loses paragraphs behind <title/>", Jsoup.parse(html).body().select("p").size < 200)
    assertEquals(200, Jsoup.parse(normalizeHtml(html)).body().select("p").size)
  }
}
```

Append to `test/data/index/ResourceOrderTest.kt` (inside the class; `assertNotNull` is already imported):

```kotlin
  @Test fun `a resource with a self-closing title still has its anchors and elements`() {
    // The filler makes the document large enough for jsoup to swallow its start into the title (see HtmlNormalizerTest).
    val filler = (1..200).joinToString("") { "<p>Filler paragraph $it, long enough to count.</p>" }
    val html = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title/></head><body><h2 id="ch1">One</h2><p class="first">Text.</p>$filler</body></html>"""
    val order = ResourceOrder.parse(html)!!
    assertNotNull(order.anchor("ch1"))
    // Readium reports selectors computed over the normalised document, so that is what is looked up.
    assertNotNull(order.element(css(normalizeHtml(html), ".first")))
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew testDebugUnitTest --tests com.quire.reader.data.index.HtmlNormalizerTest --tests com.quire.reader.data.index.ResourceOrderTest'`
Expected: compilation FAILS with `Unresolved reference 'normalizeHtml'`.

- [ ] **Step 3: Implement `normalizeHtml`**

Create `main/data/index/HtmlNormalizer.kt`:

```kotlin
package com.quire.reader.data.index

/**
 * Elements whose content jsoup's HTML parser reads as raw text. Written self-closing (`<title/>`, legal XHTML), they are
 * not closed in HTML mode, so everything after them, the whole body included, becomes their text. Readium's content
 * iterator parses resources that way, so a book with `<title/>` in its head yields almost no text.
 */
private val SELF_CLOSING_RAW_TEXT = Regex("""<(title|script|style|textarea|xmp|iframe|noembed|noframes)(\s[^<>]*?)?\s*/>""", RegexOption.IGNORE_CASE)

/** [html] with every self-closing raw-text element written as an open/close pair; the same instance when there is none. */
fun normalizeHtml(html: String): String {
  if (SELF_CLOSING_RAW_TEXT.find(html) == null) return html
  return SELF_CLOSING_RAW_TEXT.replace(html) { m -> "<${m.groupValues[1]}${m.groupValues[2]}></${m.groupValues[1]}>" }
}
```

- [ ] **Step 4: Make `ResourceOrder` parse the normalised HTML**

In `main/data/index/ResourceOrder.kt`, in the class KDoc replace the sentence

```
 * every element gets the same selector string, and positions are compared instead of selector text.
```

with

```
 * every element gets the same selector string, and positions are compared instead of selector text. The HTML is first
 * passed through [normalizeHtml], as the indexer does before Readium parses it, so both see the same document.
```

and in `parse` replace

```kotlin
        val body = Jsoup.parse(html).body()
```

with

```kotlin
        val body = Jsoup.parse(normalizeHtml(html)).body()
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew testDebugUnitTest --tests com.quire.reader.data.index.HtmlNormalizerTest --tests com.quire.reader.data.index.ResourceOrderTest'`
Expected: BUILD SUCCESSFUL, all tests pass. If the assertion "jsoup's HTML mode loses paragraphs behind <title/>" fails, make sure the body really is 200 paragraphs (the bug needs a document of more than about 2 KB) before concluding anything; if it still fails, stop and report BLOCKED.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/quire/reader/data/index/HtmlNormalizer.kt app/src/test/java/com/quire/reader/data/index/HtmlNormalizerTest.kt app/src/main/java/com/quire/reader/data/index/ResourceOrder.kt app/src/test/java/com/quire/reader/data/index/ResourceOrderTest.kt
git commit -m "Text index: normalise self-closing raw-text tags before parsing resources

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Outcome rules — `extracted()` and `isSparse()`

**Files:**
- Modify: `main/data/index/IndexPolicy.kt` (`Extracted.Text`, add two functions after `settle`)
- Modify: `test/data/index/IndexPolicyTest.kt` (append tests)

**Interfaces:**
- Produces:
  - `Extracted.Text(chunks: Int, unreadableResources: Int = 0)`
  - `fun extracted(chunks: Int, htmlResources: Int, unreadable: Int): Extracted`
  - `const val SPARSE_MIN_BYTES = 2_048L`, `const val SPARSE_RATIO = 0.02`, `fun isSparse(bytes: Long, yieldedChars: Long): Boolean`

- [ ] **Step 1: Write the failing tests**

Append inside `class IndexPolicyTest`:

```kotlin
  @Test fun `a book none of whose resources can be read is unreadable and fails`() {
    val outcome = extracted(chunks = 0, htmlResources = 3, unreadable = 3)
    assertEquals(Extracted.Unreadable, outcome)
    assertEquals(Settlement.MarkFailed, settle(outcome, fileUnchanged = true, epochCurrent = true))
  }

  @Test fun `a book with some unreadable resources is published and says how many`() {
    val outcome = extracted(chunks = 40, htmlResources = 34, unreadable = 17)
    assertEquals(Extracted.Text(chunks = 40, unreadableResources = 17), outcome)
    assertEquals(Settlement.Publish, settle(outcome, fileUnchanged = true, epochCurrent = true))
  }

  @Test fun `a readable book without text is still skipped and a book without HTML is not failed`() {
    assertEquals(Settlement.MarkSkipped, settle(extracted(chunks = 0, htmlResources = 2, unreadable = 0), fileUnchanged = true, epochCurrent = true))
    assertEquals(Extracted.Text(chunks = 0), extracted(chunks = 0, htmlResources = 0, unreadable = 0))
  }

  @Test fun `only a sizeable resource that yields almost nothing is sparse`() {
    assertTrue(isSparse(bytes = 450_000, yieldedChars = 184))
    assertTrue(isSparse(bytes = 450_000, yieldedChars = 8_999))
    assertFalse("exactly 2% is enough text", isSparse(bytes = 450_000, yieldedChars = 9_000))
    assertTrue(isSparse(bytes = 2_048, yieldedChars = 0))
    assertFalse("a short page is never sparse", isSparse(bytes = 2_047, yieldedChars = 0))
    assertFalse("an unread resource is never sparse", isSparse(bytes = 0, yieldedChars = 0))
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew testDebugUnitTest --tests com.quire.reader.data.index.IndexPolicyTest'`
Expected: compilation FAILS (`Unresolved reference 'extracted'`, `'isSparse'`, no parameter `unreadableResources`).

- [ ] **Step 3: Implement**

In `main/data/index/IndexPolicy.kt` replace

```kotlin
  /** The book was read to the end, or to the size cap; [chunks] is how many searchable chunks it made (possibly none). */
  data class Text(val chunks: Int) : Extracted
```

with

```kotlin
  /**
   * The book was read to the end, or to the size cap; [chunks] is how many searchable chunks it made (possibly none) and
   * [unreadableResources] how many of its HTML resources could not be read, so their text is missing.
   */
  data class Text(val chunks: Int, val unreadableResources: Int = 0) : Extracted
```

and insert directly after the `settle` function:

```kotlin
/**
 * What reading a book came to, from its chunks and its HTML resources: unreadable when it has HTML and none of it could be
 * read, otherwise its text with the count of resources that could not be read. A readable book without text is still
 * `Text(0)`, and so skipped.
 */
fun extracted(chunks: Int, htmlResources: Int, unreadable: Int): Extracted =
  if (htmlResources > 0 && unreadable == htmlResources) Extracted.Unreadable else Extracted.Text(chunks, unreadable)

/** Resources smaller than this are never sparse: a cover, divider or image page is short by nature. */
const val SPARSE_MIN_BYTES = 2_048L

/** A resource yielding fewer characters than this share of its bytes has probably lost its text to a misparse. */
const val SPARSE_RATIO = 0.02

/** Whether a resource of [bytes] that yielded [yieldedChars] characters looks misread. Only logged, never acted on. */
fun isSparse(bytes: Long, yieldedChars: Long): Boolean = bytes >= SPARSE_MIN_BYTES && yieldedChars < bytes * SPARSE_RATIO
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew testDebugUnitTest --tests com.quire.reader.data.index.IndexPolicyTest'`
Expected: PASS (the old tests using `Extracted.Text(chunks = 12)` still compile thanks to the default).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/quire/reader/data/index/IndexPolicy.kt app/src/test/java/com/quire/reader/data/index/IndexPolicyTest.kt
git commit -m "Text index: outcome rules for unreadable resources and the sparse-resource check

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Schema v3 — `unreadableResources` column

**Files:**
- Modify: `main/data/db/Entities.kt` (`IndexStateEntity`, after `truncated`)
- Modify: `main/data/db/QuireDatabase.kt` (`version = 2` → `3`, add `MIGRATION_2_3`, `addMigrations`)
- Modify: `androidTest/data/db/DbTestSupport.kt` (`stateOf`, `doneState`)
- Modify: `androidTest/data/db/TextIndexMigrationTest.kt`

**Interfaces:**
- Produces: `IndexStateEntity.unreadableResources: Int` (default 0); `QuireDatabase.MIGRATION_2_3`; test helper `doneState(book, bookId, chunks, truncated = false, textBytes = 0, unreadableResources = 0)`; `stateOf()` returns the column.

- [ ] **Step 1: Update the test helpers**

In `androidTest/data/db/DbTestSupport.kt` replace `stateOf` with:

```kotlin
  protected fun QuireDatabase.stateOf(bookId: Long): IndexStateEntity? =
    openHelper.writableDatabase.rows("SELECT mtime, sizeBytes, status, completedAt, chunkCount, textBytes, truncated, unreadableResources FROM index_state WHERE bookId = ?", bookId)
      .singleOrNull()?.let { IndexStateEntity(bookId, it[0]!!.toLong(), it[1]!!.toLong(), it[2]!!, it[3]!!.toLong(), it[4]!!.toInt(), it[5]!!.toLong(), it[6] == "1", it[7]!!.toInt()) }
```

and replace `doneState` with:

```kotlin
  protected fun doneState(book: BookEntity, bookId: Long, chunks: Int, truncated: Boolean = false, textBytes: Long = 0, unreadableResources: Int = 0) =
    IndexStateEntity(
      bookId, book.mtime, book.sizeBytes, IndexStateEntity.STATUS_DONE, completedAt = 1_000, chunkCount = chunks, textBytes = textBytes,
      truncated = truncated, unreadableResources = unreadableResources,
    )
```

- [ ] **Step 2: Write the failing migration test**

In `androidTest/data/db/TextIndexMigrationTest.kt`:

1. Change the class KDoc to `/** Runs the version 1 -> 2 -> 3 and 2 -> 3 migrations and the FTS triggers on the device's own SQLite, over a real file. */`
2. Rename the test `migration to version 2 keeps every seeded row of every v1 table unchanged` to `migration to the current version keeps every seeded row of every v1 table unchanged`, and in it change `assertEquals(2, sqlite.rows("PRAGMA user_version")…` to `assertEquals(3, …`.
3. Add imports:

```kotlin
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
```

4. Add after `createV1File()`:

```kotlin
  /** Builds a version 2 database file the way an installed v2 app left it: the v1 tables and seed, the 1 -> 2 migration, and one indexed book. */
  private fun createV2File(): File {
    val file = tempDbFile()
    val callback = object : SupportSQLiteOpenHelper.Callback(2) {
      override fun onCreate(db: SupportSQLiteDatabase) {
        v1Schema.forEach(db::execSQL)
        v1Seed.forEach(db::execSQL)
        QuireDatabase.MIGRATION_1_2.migrate(db)
        db.execSQL(
          "INSERT INTO text_chunk (bookId, seq, chapter, href, tokenStart, tokenEnd, primaryEndByte, text, mapping, progression) " +
            "VALUES (1, 0, 'Chapter 1', 'ch1.xhtml', 0, 4, 31, 'Jonathan kept a careful journal', '[]', 0.0)",
        )
        db.execSQL(
          "INSERT INTO index_state (bookId, mtime, sizeBytes, status, completedAt, chunkCount, textBytes, truncated) " +
            "VALUES (1, 1690000000001, 603000, 'done', 1700000000000, 1, 31, 0)",
        )
      }
      override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    val config = SupportSQLiteOpenHelper.Configuration.builder(target).name(file.absolutePath).callback(callback).build()
    FrameworkSQLiteOpenHelperFactory().create(config).use { it.writableDatabase }
    return file
  }
```

5. Add the test:

```kotlin
  @Test fun `migration from version 2 keeps the index and reads every state as fully readable`() = runBlocking {
    val db = open(createV2File())
    val sqlite = db.openHelper.writableDatabase
    assertEquals(3, sqlite.rows("PRAGMA user_version").single().single()!!.toInt())
    val state = db.stateOf(1)!!
    assertEquals(IndexStateEntity.STATUS_DONE, state.status)
    assertEquals(1, state.chunkCount)
    assertEquals(0, state.unreadableResources)
    assertEquals(1, db.hits("journal").size)
    assertEquals(emptyList<EligibleBook>(), db.index().eligibleBooks().filter { it.id == 1L })
    assertEquals("ok", sqlite.rows("PRAGMA integrity_check").single().single())
  }
```

- [ ] **Step 3: Run it to verify it fails**

Start the emulator if needed (`emulator -avd Android_API_36` in another terminal, then `adb wait-for-device`).
Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew --init-script tools/dbtest-suffix.init.gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.quire.reader.data.db.TextIndexMigrationTest'`
Expected: compilation FAILS (`No parameter with name 'unreadableResources'`).

- [ ] **Step 4: Add the column and the migration**

In `main/data/db/Entities.kt`, inside `IndexStateEntity`, after the `truncated` property:

```kotlin
  /** HTML resources of the book that could not be read (damaged entries); their text is missing from the index. */
  @ColumnInfo(defaultValue = "0") val unreadableResources: Int = 0,
```

In `main/data/db/QuireDatabase.kt`: change `version = 2,` to `version = 3,`; add after `MIGRATION_1_2`:

```kotlin
    /** Records how many resources of an indexed book could not be read. Books indexed before it read as fully readable (0). */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `index_state` ADD COLUMN `unreadableResources` INTEGER NOT NULL DEFAULT 0")
      }
    }
```

and in `create` change `.addMigrations(MIGRATION_1_2)` to `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`. Also update the `FTS_TERMS_TABLE` KDoc phrase "which covers new installs and the 1 -> 2 migration alike" to "which covers new installs and every migration alike".

- [ ] **Step 5: Run the migration and DAO tests to verify they pass**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew --init-script tools/dbtest-suffix.init.gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.quire.reader.data.db'`
Expected: all `TextIndexMigrationTest` and `IndexDaoTest` tests PASS. A failure saying "Migration didn't properly handle: index_state" means the entity's `defaultValue` and the `ALTER` default disagree; fix them to match.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/quire/reader/data/db/Entities.kt app/src/main/java/com/quire/reader/data/db/QuireDatabase.kt app/src/androidTest/java/com/quire/reader/data/db/DbTestSupport.kt app/src/androidTest/java/com/quire/reader/data/db/TextIndexMigrationTest.kt
git commit -m "Text index: schema v3 records unreadable resources per book

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Extraction through normalised, tallied resources

**Files:**
- Create: `main/data/index/IndexContent.kt`
- Create: `androidTest/data/index/IndexContentTest.kt`
- Modify: `androidTest/data/index/EpubFixtures.kt` (head option, corrupt entries)
- Modify: `main/data/index/LibraryIndexer.kt` (imports, `extract`, `publish`, companion)
- Modify: `androidTest/data/index/LibraryIndexerTest.kt` (three tests)
- Modify: `androidTest/data/index/ChapterLabelIndexTest.kt` (one test)

**Interfaces:**
- Consumes: `normalizeHtml` (Task 1); `extracted`, `isSparse`, `Extracted.Text.unreadableResources` (Task 2); `IndexStateEntity.unreadableResources`, `stateOf`, `doneState` (Task 3).
- Produces:
  - `internal class IndexContent(publication: Publication)` with `val iterator: Content.Iterator` and `val tallies: List<ResourceTally>`
  - `class ResourceTally(val href: String)` with `var bytes: Long`, `var readFailed: Boolean`, `var yieldedChars: Long`
  - `FixtureResource(name, body, head: String? = null)`; `EpubFixtures.write(file, resources, toc, corrupt: Set<String> = emptySet())`

- [ ] **Step 1: Extend the EPUB fixtures**

In `androidTest/data/index/EpubFixtures.kt`:

Replace the `FixtureResource` declaration with:

```kotlin
/** An XHTML file of a generated test EPUB, in reading order; [body] is the markup inside `<body>`, and [head], when given, the markup inside `<head>` (default: a `<title>`). */
class FixtureResource(val name: String, val body: String, val head: String? = null)
```

Add `import java.util.zip.ZipFile`. Change the `write(file, resources, toc)` signature and body ends as follows:

```kotlin
  /** An EPUB of [resources] in reading order whose contents are [toc] (nested as given); the resources named in [corrupt] cannot be read. */
  fun write(file: File, resources: List<FixtureResource>, toc: List<FixtureToc>, corrupt: Set<String> = emptySet()): File {
```

inside it, change the nav and resource lines to:

```kotlin
      zip.entry("OEBPS/nav.xhtml", xhtml("<title>Contents</title>", """<nav xmlns:epub="http://www.idpf.org/2007/ops" epub:type="toc">${tocList(toc)}</nav>"""))
      resources.forEach { zip.entry("OEBPS/${it.name}", xhtml(it.head ?: "<title>${it.name}</title>", it.body)) }
    }
    corrupt.forEach { corruptEntry(file, "OEBPS/$it") }
    return file
  }
```

Replace `xhtml` and add the corruption helpers:

```kotlin
  private fun xhtml(head: String, body: String) =
    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head>$head</head><body>$body</body></html>"""

  /**
   * Overwrites the compressed bytes of [entryName] in place: the archive and its directory stay valid, but reading that
   * entry fails, because 0xFF starts a deflate block of the reserved type, which inflaters reject.
   */
  private fun corruptEntry(file: File, entryName: String) {
    val size = ZipFile(file).use { zip -> requireNotNull(zip.getEntry(entryName)) { "no entry $entryName" }.compressedSize.toInt() }
    val bytes = file.readBytes()
    val name = entryName.toByteArray()
    val header = (0 until bytes.size - 30 - name.size).first { i ->
      bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0x04.toByte() &&
        u16(bytes, i + 26) == name.size && bytes.copyOfRange(i + 30, i + 30 + name.size).contentEquals(name)
    }
    val data = header + 30 + name.size + u16(bytes, header + 28)
    bytes.fill(0xFF.toByte(), data, data + size)
    file.writeBytes(bytes)
  }

  private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
```

- [ ] **Step 2: Write the failing `IndexContentTest`**

Create `androidTest/data/index/IndexContentTest.kt`:

```kotlin
package com.quire.reader.data.index

import androidx.test.platform.app.InstrumentationRegistry
import com.quire.reader.reader.PublicationLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import java.io.File

/** What the indexer reads from a publication, compared with what Readium's own content service yields. */
@OptIn(ExperimentalReadiumApi::class)
class IndexContentTest {
  private val target = InstrumentationRegistry.getInstrumentation().targetContext
  private val dir = File(target.cacheDir, "db-tests/content-epubs").apply { mkdirs() }

  @After fun cleanUp() { dir.deleteRecursively() }

  private data class Seen(val href: String, val text: String, val css: String?, val progression: Double?)

  private suspend fun Content.Iterator.drain(): List<Seen> = buildList {
    while (true) {
      val element = nextOrNull() ?: break
      val text = element as? Content.TextElement ?: continue
      add(Seen(text.locator.href.toString(), text.segments.joinToString("") { it.text }, text.locator.locations.otherLocations["cssSelector"] as? String, text.locator.locations.totalProgression))
    }
  }

  private fun <T> withPublication(file: File, block: suspend (Publication) -> T): T = runBlocking {
    val publication = PublicationLoader(target).open(file).getOrThrow()
    try { block(publication) } finally { publication.close() }
  }

  /** Three resources of sixty paragraphs (about 5 KB each): jsoup only misreads `<title/>` in documents of more than about 2 KB. */
  private fun book(name: String, head: String? = null, corrupt: Set<String> = emptySet()): File {
    val resources = (0 until 3).map { i ->
      FixtureResource("c$i.xhtml", "<h2 id=\"h$i\">Chapter $i</h2>" + (0 until 60).joinToString("") { "<p class=\"p$it\">Paragraph $it of chapter $i about the lighthouse.</p>" }, head)
    }
    return EpubFixtures.write(File(dir, "$name.epub"), resources, resources.mapIndexed { i, r -> FixtureToc(r.name, null, "Chapter $i") }, corrupt)
  }

  @Test fun `a healthy book yields exactly what Readium's own content service yields`() = withPublication(book("healthy")) { pub ->
    val expected = pub.content()!!.iterator().drain()
    val content = IndexContent(pub)
    assertEquals(expected, content.iterator.drain())
    assertEquals(3, content.tallies.size)
    assertTrue(content.tallies.none { it.readFailed })
    assertTrue(content.tallies.all { it.bytes > 0 && it.yieldedChars > 0 && !isSparse(it.bytes, it.yieldedChars) })
  }

  @Test fun `a self-closing title no longer hides the body`() {
    val closed = withPublication(book("closed", head = "<title>T</title>")) { pub -> IndexContent(pub).iterator.drain().map { it.text } }
    withPublication(book("self-closing", head = "<title/>")) { pub ->
      val readium = pub.content()!!.iterator().drain()
      assertTrue(
        "Readium alone loses most of the paragraphs",
        readium.count { it.text.contains("Paragraph") } < closed.count { it.contains("Paragraph") },
      )
      val content = IndexContent(pub)
      assertEquals(closed, content.iterator.drain().map { it.text })
      assertTrue(content.tallies.none { it.readFailed || isSparse(it.bytes, it.yieldedChars) })
    }
  }

  @Test fun `a resource that cannot be read is tallied as such and the others still yield`() = withPublication(book("corrupt", corrupt = setOf("c1.xhtml"))) { pub ->
    val content = IndexContent(pub)
    val hrefs = content.iterator.drain().map { it.href }.toSet()
    assertEquals(listOf(false, true, false), content.tallies.map { it.readFailed })
    assertTrue(hrefs.none { it.endsWith("c1.xhtml") })
    assertTrue(hrefs.any { it.endsWith("c0.xhtml") } && hrefs.any { it.endsWith("c2.xhtml") })
  }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew --init-script tools/dbtest-suffix.init.gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.quire.reader.data.index.IndexContentTest'`
Expected: compilation FAILS (`Unresolved reference 'IndexContent'`).

- [ ] **Step 4: Implement `IndexContent.kt`**

Create `main/data/index/IndexContent.kt`:

```kotlin
package com.quire.reader.data.index

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.PublicationServicesHolder
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.iterators.HtmlResourceContentIterator
import org.readium.r2.shared.publication.services.content.iterators.PublicationContentIterator
import org.readium.r2.shared.publication.services.content.iterators.ResourceContentIteratorFactory
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingResource

/**
 * The text of a publication for indexing: Readium's own content iterator, reading every HTML resource through
 * [normalizeHtml] and keeping a [ResourceTally] per resource. Readium alone parses `<title/>` so that the body disappears,
 * and skips a resource it cannot read without saying so; the tallies are how the indexer learns of both.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class IndexContent(publication: Publication) {
  private val factory = TrackingHtmlFactory()

  /** Every element of the reading order, from the start. */
  val iterator: Content.Iterator =
    PublicationContentIterator(publication.manifest, PublicationResources(publication), publication, null, listOf(factory))

  /** One per HTML resource opened so far, in reading order. */
  val tallies: List<ResourceTally> get() = factory.tallies
}

/** What one HTML resource came to: its size once read ([bytes], 0 until then), whether reading it failed, and the characters of text it yielded. */
class ResourceTally(val href: String) {
  var bytes = 0L
  var readFailed = false
  var yieldedChars = 0L
}

/** Readium's HTML iterator over normalised resources, tallying each one it accepts. */
@OptIn(ExperimentalReadiumApi::class)
private class TrackingHtmlFactory : ResourceContentIteratorFactory {
  private val html = HtmlResourceContentIterator.Factory()
  private val byIndex = sortedMapOf<Int, ResourceTally>()

  val tallies: List<ResourceTally> get() = byIndex.values.toList()

  override suspend fun create(
    manifest: Manifest,
    servicesHolder: PublicationServicesHolder,
    readingOrderIndex: Int,
    resource: Resource,
    mediaType: MediaType,
    locator: Locator,
  ): Content.Iterator? {
    val tally = ResourceTally(manifest.readingOrder[readingOrderIndex].url().toString())
    val iterator = html.create(manifest, servicesHolder, readingOrderIndex, NormalizingResource(resource, tally), mediaType, locator) ?: return null
    byIndex[readingOrderIndex] = tally
    return CountingIterator(iterator, tally)
  }
}

/** [resource] as Readium will parse it: repaired by [normalizeHtml], and the original bytes when nothing needed repair. */
private class NormalizingResource(resource: Resource, private val tally: ResourceTally) : TransformingResource(resource) {
  override suspend fun transform(data: Try<ByteArray, ReadError>): Try<ByteArray, ReadError> {
    val bytes = data.getOrElse { tally.readFailed = true; return data }
    tally.bytes = bytes.size.toLong()
    // Decoded as UTF-8 because that is how Readium decodes a resource before parsing it.
    val html = String(bytes, Charsets.UTF_8)
    val normalized = normalizeHtml(html)
    return if (normalized === html) data else Try.success(normalized.toByteArray(Charsets.UTF_8))
  }
}

/** Passes [inner]'s elements through, adding the text each one carries to [tally]. */
@OptIn(ExperimentalReadiumApi::class)
private class CountingIterator(private val inner: Content.Iterator, private val tally: ResourceTally) : Content.Iterator {
  override suspend fun hasNext(): Boolean = inner.hasNext()

  override fun next(): Content.Element = inner.next().also { element ->
    (element as? Content.TextElement)?.let { text -> tally.yieldedChars += text.segments.sumOf { it.text.length } }
  }

  override suspend fun hasPrevious(): Boolean = inner.hasPrevious()

  override fun previous(): Content.Element = inner.previous()
}

/** The publication's resources by URL, through the public [Publication.get]; `Publication.container` is internal to Readium. */
private class PublicationResources(private val publication: Publication) : Container<Resource> {
  override val entries: Set<Url> = (publication.readingOrder + publication.resources).map { it.url() }.toSet()

  override fun get(url: Url): Resource? = publication.get(url)

  override fun close() = Unit
}
```

If the compiler reports a signature mismatch on `create` (for example `locator: Locator?`), match the interface exactly as the error states; do not change behaviour.

- [ ] **Step 5: Run `IndexContentTest` to verify it passes**

Run: the Step 3 command.
Expected: 3 tests PASS. If `a healthy book yields exactly what Readium's own content service yields` fails, compare the first differing `Seen`; the cause must be found before continuing (it would mean healthy books change).

- [ ] **Step 6: Write the failing indexer tests**

Append inside `class LibraryIndexerTest` (`androidTest/data/index/LibraryIndexerTest.kt`):

```kotlin
  /** Three chapters of sixty paragraphs (about 4 KB each, past the 2 KB where jsoup starts misreading `<title/>`), every resource with [head] in its `<head>`; the last chapter mentions a quokka. */
  private fun headed(name: String, head: String, corrupt: Set<String> = emptySet()): File {
    val resources = (0 until 3).map { c ->
      val paragraphs = (0 until 60).joinToString("") { "<p>Paragraph $it of chapter $c tells how the keeper counted gulls.</p>" }
      FixtureResource("c$c.xhtml", "<h2>Chapter $c</h2>$paragraphs" + (if (c == 2) "<p>The final chapter mentions a quokka.</p>" else ""), head)
    }
    return EpubFixtures.write(File(epubDir, "$name.epub"), resources, resources.mapIndexed { c, r -> FixtureToc(r.name, null, "Chapter $c") }, corrupt)
  }

  @Test fun `a book whose chapters have a self-closing title is indexed as fully as one with a closed title`() = runBlocking {
    val f = fixture()
    val selfClosing = f.add("SelfClosing", headed("self-closing", head = "<title/>"))
    val closed = f.add("Closed", headed("closed", head = "<title>T</title>"))

    f.indexer.runBatch(deadline)

    val state = f.db.stateOf(selfClosing.id)!!
    assertEquals(IndexStateEntity.STATUS_DONE, state.status)
    assertEquals(0, state.unreadableResources)
    assertEquals(f.db.chunkTexts(closed.id), f.db.chunkTexts(selfClosing.id))
    assertTrue(f.db.chunkTexts(selfClosing.id).any { it.contains("quokka") })
  }

  @Test fun `a book with one unreadable chapter is indexed from the rest and says so`() = runBlocking {
    val f = fixture()
    val book = f.add("Damaged", headed("damaged", head = "<title>T</title>", corrupt = setOf("c1.xhtml")))

    f.indexer.runBatch(deadline)

    val state = f.db.stateOf(book.id)!!
    assertEquals(IndexStateEntity.STATUS_DONE, state.status)
    assertEquals(1, state.unreadableResources)
    assertFalse(state.truncated)
    assertTrue(f.db.chunkTexts(book.id).any { it.contains("quokka") })
    val chapters = f.db.openHelper.writableDatabase.rows("SELECT DISTINCT chapter FROM text_chunk WHERE bookId = ?", book.id).map { it[0] }.toSet()
    assertEquals(setOf("Chapter 0", "Chapter 2"), chapters)
  }

  @Test fun `a book none of whose chapters can be read fails instead of being skipped`() = runBlocking {
    val f = fixture()
    val book = f.add("Ruined", headed("ruined", head = "<title>T</title>", corrupt = setOf("c0.xhtml", "c1.xhtml", "c2.xhtml")))

    assertEquals(1, f.indexer.runBatch(deadline).processed)

    assertEquals(IndexStateEntity.STATUS_FAILED, f.db.stateOf(book.id)!!.status)
    assertEquals(0, f.db.chunkCount(book.id))
  }
```

`rows(...)` is the `DbTestCase` helper already used by the first test in this file.

Append inside `class ChapterLabelIndexTest` (`androidTest/data/index/ChapterLabelIndexTest.kt`):

```kotlin
  @Test fun `chapters in a resource with a self-closing title are labelled from their anchors like any other`() {
    // The filler takes the titled resource past the 2 KB where jsoup starts swallowing the start of a `<title/>` document into the title.
    val filler = (1..200).joinToString("") { "<p>Filler line $it keeps this file past two kilobytes.</p>" }
    val book = index(
      "mixed-title",
      listOf(
        FixtureResource("plain.xhtml", """<h2 id="one">One</h2><p>Ferns grow here.</p><h2 id="two">Two</h2><p>Mosses grow there.</p>"""),
        FixtureResource("titled.xhtml", """<h2 id="three">Three</h2><p>Lichens cling on.</p><h2 id="four">Four</h2><p>Liverworts spread out.</p>$filler""", head = "<title/>"),
      ),
      listOf(
        FixtureToc("plain.xhtml", "one", "One"), FixtureToc("plain.xhtml", "two", "Two"),
        FixtureToc("titled.xhtml", "three", "Three"), FixtureToc("titled.xhtml", "four", "Four"),
      ),
    )
    assertEquals("One", book.chapterOf("ferns"))
    assertEquals("Two", book.chapterOf("mosses"))
    assertEquals("Three", book.chapterOf("lichens"))
    assertEquals("Four", book.chapterOf("liverworts"))
  }
```

- [ ] **Step 7: Run them to verify they fail**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew --init-script tools/dbtest-suffix.init.gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.quire.reader.data.index.LibraryIndexerTest,com.quire.reader.data.index.ChapterLabelIndexTest'`
Expected: the four new tests FAIL (self-closing book has fewer chunks / no `quokka` hit; damaged book has `unreadableResources = 0`; ruined book is `skipped`; `lichens` not found). Existing tests PASS.

- [ ] **Step 8: Switch `LibraryIndexer` to `IndexContent`**

In `main/data/index/LibraryIndexer.kt`:

1. Add `import android.util.Log`; remove `import org.readium.r2.shared.publication.services.content.content` (no longer used).
2. Replace the start of `extract` up to the loop:

```kotlin
      val chunks = ArrayList<IndexChunk>()
      val chunker = TextChunker()
      val content = publication.content() ?: return Extraction(Extracted.Text(0))
      val iterator = content.iterator()
```

with

```kotlin
      val chunks = ArrayList<IndexChunk>()
      val chunker = TextChunker()
      val content = IndexContent(publication)
      val iterator = content.iterator
```

3. Replace the end of the `try` block:

```kotlin
      chunks += chunker.finish()
      return Extraction(Extracted.Text(chunks.size), chunks, chunker.truncated)
```

with

```kotlin
      chunks += chunker.finish()
      // The resource the size cap stopped in was only partly read, so it says nothing about how much text it holds.
      logSparse(file, if (chunker.truncated) content.tallies.dropLast(1) else content.tallies)
      val unreadable = content.tallies.count { it.readFailed }
      return Extraction(extracted(chunks.size, content.tallies.size, unreadable), chunks, chunker.truncated)
```

4. Add after `extract`:

```kotlin
  /** Evidence of misreads other than the ones [normalizeHtml] repairs, for a later look; nothing is decided from it. */
  private fun logSparse(file: File, tallies: List<ResourceTally>) {
    tallies.filter { isSparse(it.bytes, it.yieldedChars) }.forEach {
      Log.i(TAG, "sparse resource: ${file.path} ${it.href}: ${it.yieldedChars} chars from ${it.bytes} bytes")
    }
  }
```

5. In `publish`, add to the `IndexStateEntity(...)` arguments after `truncated = extraction.truncated,`:

```kotlin
      unreadableResources = (extraction.result as? Extracted.Text)?.unreadableResources ?: 0,
```

6. In the `companion object`, add `private const val TAG = "LibraryIndexer"`.

- [ ] **Step 9: Run the index tests to verify they pass**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew --init-script tools/dbtest-suffix.init.gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.quire.reader.data.index'`
Expected: every test in the package PASSES, including the unchanged ones (they are the guard that healthy books index as before).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/quire/reader/data/index/IndexContent.kt app/src/main/java/com/quire/reader/data/index/LibraryIndexer.kt app/src/androidTest/java/com/quire/reader/data/index/
git commit -m "Text index: read resources through normalised, tallied Readium iterators

Self-closing <title/> no longer hides a resource's body, unreadable chapters are
counted per book, and a book with nothing readable fails instead of being skipped.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `IndexGap` — cause-specific notes and coverage

**Files:**
- Modify: `main/data/index/TextSearchResult.kt` (add `IndexGap`; `BookTextResult`, `BookTextPage`)
- Modify: `main/data/db/SearchDao.kt:31` (`IndexedBook`) and `:157` (query)
- Modify: `main/data/index/TextSearcher.kt:65,145,149`
- Modify: `main/data/db/Daos.kt:141` (`IndexCoverage`) and `:162` (query)
- Modify: `main/ui/BookSearch.kt`, `main/ui/TextSearchPresentation.kt`, `main/ui/library/TextSearchResults.kt:51,153-155`, `main/ui/reader/ReaderSheets.kt:308-310`
- Test: `test/ui/TextSearchPresentationTest.kt`, `test/ui/BookSearchTest.kt`, `test/ui/TextSearchStatusTest.kt`, `androidTest/data/index/TextSearcherTest.kt`, `androidTest/data/db/IndexDaoTest.kt`

**Interfaces:**
- Consumes: `IndexStateEntity.unreadableResources`, `doneState(..., unreadableResources)` (Task 3).
- Produces:
  - `enum class IndexGap { None, FirstPartOnly, PartsUnreadable, Both }` with `IndexGap.of(truncated: Boolean, partsUnreadable: Boolean)`
  - `BookTextResult(book, passages, gap: IndexGap, snippets)`, `BookTextPage(snippets, nextAfterSeq, gap: IndexGap)`, `BookSearchUi.gap: IndexGap`
  - `IndexCoverage(eligible, searchable, failed, skipped, partial)`
  - `fun cardNote(gap: IndexGap): String?`, `fun sheetNote(gap: IndexGap): String?` (`TRUNCATED_BOOK_NOTE` removed)

- [ ] **Step 1: Write the failing JVM tests**

`test/ui/TextSearchPresentationTest.kt`:
- Add `import com.quire.reader.data.index.IndexGap`.
- Replace the `coverage` helper with:

```kotlin
  private fun coverage(eligible: Int = 10, searchable: Int = 10, failed: Int = 0, skipped: Int = 0, partial: Int = 0) =
    IndexCoverage(eligible, searchable, failed, skipped, partial)
```

- In `book(...)` replace the `false` third argument with `IndexGap.None`.
- Replace every `truncated = ` argument of `coverage(...)` with `partial = ` (three call sites: lines ~30, ~46, ~139).
- Append:

```kotlin
  // ── per-book notes ──

  @Test fun `a book card names why the book is only partly searchable`() {
    assertNull(cardNote(IndexGap.None))
    assertEquals("Only the first part of this book is searchable", cardNote(IndexGap.FirstPartOnly))
    assertEquals("Parts of this book couldn’t be read, so some passages may be missing", cardNote(IndexGap.PartsUnreadable))
    assertEquals("Only the first part of this book is searchable, and some of it couldn’t be read", cardNote(IndexGap.Both))
  }

  @Test fun `the in-book search sheet says what the gap means for its matches`() {
    assertNull(sheetNote(IndexGap.None))
    assertEquals("Only the first part of this book is searchable, so later matches are not listed.", sheetNote(IndexGap.FirstPartOnly))
    assertEquals("Parts of this book couldn’t be read, so some passages may be missing.", sheetNote(IndexGap.PartsUnreadable))
    assertEquals("Only the first part of this book is searchable, and some of it couldn’t be read, so some matches may be missing.", sheetNote(IndexGap.Both))
  }

  @Test fun `a gap comes from the cap, the unreadable parts, or both`() {
    assertEquals(IndexGap.None, IndexGap.of(truncated = false, partsUnreadable = false))
    assertEquals(IndexGap.FirstPartOnly, IndexGap.of(truncated = true, partsUnreadable = false))
    assertEquals(IndexGap.PartsUnreadable, IndexGap.of(truncated = false, partsUnreadable = true))
    assertEquals(IndexGap.Both, IndexGap.of(truncated = true, partsUnreadable = true))
  }
```

`test/ui/BookSearchTest.kt`:
- Add `import com.quire.reader.data.index.IndexGap`.
- Replace the `page` helper with `private fun page(vararg seqs: Int, next: Int? = null, gap: IndexGap = IndexGap.None) = BookTextPage(seqs.map(::snippet), next, gap)`.
- Replace the test `an empty last page is no match and keeps the truncation note` with:

```kotlin
  @Test fun `an empty last page is no match and keeps the book's gap`() {
    val ui = bookSearchFirstPage(page(gap = IndexGap.FirstPartOnly))
    assertEquals(BookSearchStatus.NoMatch, ui.status)
    assertEquals(IndexGap.FirstPartOnly, ui.gap)
  }
```

- Replace the test `later pages keep reporting truncation` with:

```kotlin
  @Test fun `later pages keep reporting the book's gap`() {
    val ui = bookSearchFirstPage(page(1, next = 1)).withPage(1, page(2, gap = IndexGap.PartsUnreadable))
    assertEquals(IndexGap.PartsUnreadable, ui.gap)
  }
```

`test/ui/TextSearchStatusTest.kt` line ~26: replace `PassageCount(1, false), false, emptyList()` with `PassageCount(1, false), IndexGap.None, emptyList()` and add `import com.quire.reader.data.index.IndexGap`.

- [ ] **Step 2: Run them to verify they fail**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew testDebugUnitTest'`
Expected: compilation FAILS (`Unresolved reference 'IndexGap'`, `'cardNote'`, `'sheetNote'`).

- [ ] **Step 3: Add `IndexGap` and carry it through the search results**

In `main/data/index/TextSearchResult.kt`, add before `BookTextResult`:

```kotlin
/** Why a book's index may not hold all of its text: the size cap stopped it, some of its resources could not be read, or both. */
enum class IndexGap {
  None, FirstPartOnly, PartsUnreadable, Both;

  companion object {
    fun of(truncated: Boolean, partsUnreadable: Boolean): IndexGap = when {
      truncated && partsUnreadable -> Both
      truncated -> FirstPartOnly
      partsUnreadable -> PartsUnreadable
      else -> None
    }
  }
}
```

Replace the `BookTextResult` KDoc sentence "[truncated] says the book's index stops before its end, so absence of matches proves nothing." with "[gap] says why the book's index may be missing text, so absence of matches there proves nothing.", and the declarations with:

```kotlin
data class BookTextResult(val book: Book, val passages: PassageCount, val gap: IndexGap, val snippets: List<Snippet>)
```

```kotlin
/** One page of a single book's matches. [nextAfterSeq] is the cursor for the next page, null on the last; [gap] as in [BookTextResult]. */
data class BookTextPage(val snippets: List<Snippet>, val nextAfterSeq: Int?, val gap: IndexGap)
```

In `main/data/db/SearchDao.kt`:

```kotlin
/** A book's index as search sees it: the signature the text was indexed from, whether the index stops early, and how many resources could not be read. */
data class IndexedBook(val bookId: Long, val mtime: Long, val sizeBytes: Long, val truncated: Boolean, val unreadableResources: Int)
```

```kotlin
  @Query("SELECT bookId, mtime, sizeBytes, truncated, unreadableResources FROM index_state WHERE status = 'done' AND bookId IN (:bookIds)")
```

In `main/data/index/TextSearcher.kt`:
- Add `import com.quire.reader.data.db.IndexedBook` if not already imported, and at file bottom:

```kotlin
private val IndexedBook.gap: IndexGap get() = IndexGap.of(truncated, unreadableResources > 0)
```

- Line ~65: `BookTextResult(book.toBook(now), PassageCount(rank.passages, capped), state.gap, snippets)`
- Line ~145: `?: return BookTextPage(emptyList(), null, IndexGap.None)`
- Line ~149: `return BookTextPage(snippets, if (rows.size > pageSize) page.last().seq else null, state.gap)`

- [ ] **Step 4: Coverage counts both causes**

In `main/data/db/Daos.kt`:

```kotlin
/**
 * How much of the library is searchable. Every count covers readable books only, and `searchable`, `failed`
 * and `skipped` count a book only while its index state still matches the book's current signature. `partial`
 * counts searchable books whose index is missing text (size cap or unreadable resources).
 */
data class IndexCoverage(val eligible: Int, val searchable: Int, val failed: Int, val skipped: Int, val partial: Int)
```

and in `observeCoverage` replace `COALESCE(SUM(s.status = 'done' AND s.truncated = 1), 0) AS truncated` with

```
           COALESCE(SUM(s.status = 'done' AND (s.truncated = 1 OR s.unreadableResources > 0)), 0) AS partial
```

- [ ] **Step 5: Notes in the UI**

`main/ui/BookSearch.kt`: add `import com.quire.reader.data.index.IndexGap`; in the `BookSearchUi` KDoc replace "[truncated] says the book's index stops before its end, so matches past that point cannot be listed." with "[gap] says why the book's index may be missing text, so some matches cannot be listed."; replace `val truncated: Boolean = false,` with `val gap: IndexGap = IndexGap.None,`; then:

```kotlin
fun bookSearchFirstPage(page: BookTextPage): BookSearchUi =
  if (page.snippets.isEmpty() && page.nextAfterSeq == null) BookSearchUi(BookSearchStatus.NoMatch, gap = page.gap)
  else BookSearchUi(BookSearchStatus.Results, page.snippets, page.nextAfterSeq, page.gap)
```

and in `withPage` replace `truncated = page.truncated` with `gap = page.gap`.

`main/ui/TextSearchPresentation.kt`: add `import com.quire.reader.data.index.IndexGap`; replace `c.truncated` with `c.partial` in `coverageIssues`, `isPartial` and the no-match `when` (three places); replace `const val TRUNCATED_BOOK_NOTE = "Only the first part of this book is searchable"` with:

```kotlin
/** The note under a book card whose index is missing text; null when it is complete. */
fun cardNote(gap: IndexGap): String? = when (gap) {
  IndexGap.None -> null
  IndexGap.FirstPartOnly -> "Only the first part of this book is searchable"
  IndexGap.PartsUnreadable -> "Parts of this book couldn’t be read, so some passages may be missing"
  IndexGap.Both -> "Only the first part of this book is searchable, and some of it couldn’t be read"
}

/** The note under the in-book search status ("Show all in this book") when the book's index is missing text. */
fun sheetNote(gap: IndexGap): String? = when (gap) {
  IndexGap.None -> null
  IndexGap.FirstPartOnly -> "Only the first part of this book is searchable, so later matches are not listed."
  IndexGap.PartsUnreadable -> "Parts of this book couldn’t be read, so some passages may be missing."
  IndexGap.Both -> "Only the first part of this book is searchable, and some of it couldn’t be read, so some matches may be missing."
}
```

`main/ui/library/TextSearchResults.kt`: replace `import com.quire.reader.ui.TRUNCATED_BOOK_NOTE` with `import com.quire.reader.ui.cardNote` (keep imports sorted), and replace

```kotlin
    if (result.truncated) {
      QText(TRUNCATED_BOOK_NOTE, 11.5f, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), color = Nq.neutral500)
    }
```

with

```kotlin
    cardNote(result.gap)?.let { note ->
      QText(note, 11.5f, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), color = Nq.neutral500)
    }
```

`main/ui/reader/ReaderSheets.kt`: add `import com.quire.reader.ui.sheetNote` (sorted with the other `com.quire.reader.ui.*` imports), and replace

```kotlin
  if (ui.truncated && (ui.status == BookSearchStatus.Results || ui.status == BookSearchStatus.NoMatch)) {
    QText("Only the first part of this book is searchable, so later matches are not listed.", 11.5f, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), color = Nq.neutral500)
  }
```

with

```kotlin
  val gapNote = sheetNote(ui.gap)
  if (gapNote != null && (ui.status == BookSearchStatus.Results || ui.status == BookSearchStatus.NoMatch)) {
    QText(gapNote, 11.5f, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), color = Nq.neutral500)
  }
```

- [ ] **Step 6: Run the JVM tests to verify they pass**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew testDebugUnitTest'`
Expected: BUILD SUCCESSFUL, all tests pass. `grep -rn "truncated" app/src/main/java/com/quire/reader/ui` prints nothing.

- [ ] **Step 7: Update the instrumented tests**

`androidTest/data/index/TextSearcherTest.kt`:
- Change the `index` helper signature to `private fun Fixture.index(book: BookEntity, paragraphs: List<String>, chapter: String = "Chapter One", truncated: Boolean = false, unreadable: Int = 0): List<IndexChunk>` and its `doneState(...)` call to `doneState(book, book.id, rows.size, truncated, rows.sumOf { it.text.toByteArray().size }.toLong(), unreadable)`.
- Replace the test `a truncated index is reported with its book` with:

```kotlin
  @Test fun `each book reports why its index may be missing text`() {
    val f = fixture()
    val capped = f.add("Capped")
    val damaged = f.add("Damaged")
    val whole = f.add("Whole")
    f.index(capped, listOf("The heron stood alone."), truncated = true)
    f.index(damaged, listOf("The heron stood alone."), unreadable = 2)
    f.index(whole, listOf("The heron stood alone."))

    val result = f.search("heron")

    assertEquals(
      mapOf("Capped" to IndexGap.FirstPartOnly, "Damaged" to IndexGap.PartsUnreadable, "Whole" to IndexGap.None),
      result.books.associate { it.book.title to it.gap },
    )
    assertEquals(IndexGap.PartsUnreadable, runBlocking { TextSearcher(f.db).page(query("heron"), damaged.id) }.gap)
  }
```

- Line ~483: `assertEquals(BookTextPage(emptyList(), null, IndexGap.None), page)`.

`androidTest/data/db/IndexDaoTest.kt`:
- Change the `index` helper signature to add `unreadable: Int = 0` after `truncated`, and its `doneState(...)` call to pass `unreadableResources = unreadable`.
- In `coverage counts only states that match the current file signature of readable books`, add `val damaged = f.add("Damaged")` after `val unreadable = f.add("Unreadable")`, `f.index(damaged, "Echo", unreadable = 1)` after `f.index(unreadable, "Delta")`, and replace the assertion and its comment with:

```kotlin
    // Of 7 readable books: done, truncated and damaged are searchable, and the last two are partial; the stale one waits like the pending one.
    assertEquals(IndexCoverage(eligible = 7, searchable = 3, failed = 1, skipped = 1, partial = 2), coverage)
```

- [ ] **Step 8: Run the full instrumented suite**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew --init-script tools/dbtest-suffix.init.gradle connectedDebugAndroidTest'`
Expected: all tests PASS. Afterwards confirm the user's app is untouched: `adb shell pm list packages | grep com.quire.reader` still lists `com.quire.reader`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/quire/reader app/src/test/java/com/quire/reader app/src/androidTest/java/com/quire/reader
git commit -m "Text search: say why a book is only partly searchable

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Documentation

**Files:**
- Modify: `docs/superpowers/specs/2026-10-03-library-text-index-design.md:100`
- Modify: `README.md:32`
- Modify: `PLAN_FALLBACK_EXTRACTOR.md:3`
- Modify: `docs/superpowers/specs/2026-10-04-fallback-extractor-design.md` (Status, Extraction, Files, Tests)

- [ ] **Step 1: Replace the "Known limits" paragraph** (line 100 of the text-index design) with:

```markdown
Known limits of the extraction source (found in verification, since addressed): Readium parses each resource with HTML-mode jsoup, where a self-closing `<title/>` (or `script`, `style`, `textarea` and other raw-text elements) swallows the whole body. The indexer now feeds Readium normalised HTML in which those elements are written as open/close pairs, so such books are indexed in full (`2026-10-04-fallback-extractor-design.md`). Readium also skips resources it cannot read; the indexer counts them per book in `index_state.unreadableResources`, reports such a book as partly indexed, and marks a book `failed` when none of its resources can be read. Their text is not recovered. Resources that are read but yield almost no text for their size are logged under `LibraryIndexer` as evidence of other misreads; nothing is decided from that log.
```

- [ ] **Step 2: README.** In `README.md` line 32 replace `and a few books whose markup Readium misreads today (a fallback extractor is planned) are only partly searchable;` with `and a book with chapters that cannot be read (a damaged file) is indexed without them and shown as partly indexed;`. Directly after that paragraph add a new paragraph:

```markdown
If you used text search before books with self-closing `<title/>` tags were handled, run Settings → Rebuild index once: books indexed earlier are not read again on their own.
```

- [ ] **Step 3: Old plan.** In `PLAN_FALLBACK_EXTRACTOR.md` replace the line starting `Status: PLAN ONLY.` with:

```markdown
Status: SUPERSEDED by `docs/superpowers/specs/2026-10-04-fallback-extractor-design.md` (HTML normalisation before Readium instead of a hand-rolled extractor; schema v3 instead of amending Migration(1,2)). Kept for the discussion that led there.
```

- [ ] **Step 4: Design spec.** In `docs/superpowers/specs/2026-10-04-fallback-extractor-design.md`:
- Status line: replace `Status: APPROVED DESIGN, not implemented.` with `Status: IMPLEMENTED (plan: docs/superpowers/plans/2026-10-04-html-normalisation-partial-coverage.md).`
- Extraction section: replace `New file \`data/index/IndexContent.kt\`:` with `New files \`data/index/HtmlNormalizer.kt\` (\`normalizeHtml\`) and \`data/index/IndexContent.kt\` (the Readium plumbing):`.
- Files section: replace `New: \`data/index/IndexContent.kt\`.` with `New: \`data/index/HtmlNormalizer.kt\`, \`data/index/IndexContent.kt\`.`
- Tests section, Instrumented list: add the bullet `- \`IndexContentTest\`: a healthy book yields exactly the elements Readium's own content service yields (href, text, cssSelector, progression); a \`<title/>\` book yields the same texts as its closed-title twin; a corrupted resource is tallied \`readFailed\` while the others yield.`
- `IndexPolicy` bullet list: add `- Pure \`isSparse(bytes, yieldedChars)\` with \`SPARSE_MIN_BYTES = 2_048\` and \`SPARSE_RATIO = 0.02\`.`

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-10-03-library-text-index-design.md docs/superpowers/specs/2026-10-04-fallback-extractor-design.md README.md PLAN_FALLBACK_EXTRACTOR.md
git commit -m "docs: normalised extraction and partial-coverage reporting

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Verification on the emulator

No code. Record results in `docs/superpowers/specs/2026-10-04-fallback-extractor-design.md` under a new `## Verification results` section, then commit.

- [ ] **Step 1: Full build, unit tests, lint**

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug test lint'`
Expected: BUILD SUCCESSFUL, no new lint errors.

- [ ] **Step 2: Full instrumented suite** (protective init script only)

Run: `zsh -fc 'source ~/.config/android/env.zsh; ./gradlew --init-script tools/dbtest-suffix.init.gradle connectedDebugAndroidTest'`
Expected: all PASS.

- [ ] **Step 3: Install the `.dbtest` app with Juliet and a damaged fixture**

```zsh
zsh -fc 'source ~/.config/android/env.zsh; ./gradlew --init-script tools/dbtest-suffix.init.gradle installDebug'
adb shell appops set com.quire.reader.dbtest MANAGE_EXTERNAL_STORAGE allow
adb shell mkdir -p "/sdcard/Calibre Library/Gabby Rivera"
adb push "/home/caan9/Calibre Library/Gabby Rivera/Juliet Takes a Breath (649)" "/sdcard/Calibre Library/Gabby Rivera/"
```

For the damaged fixture, copy any real EPUB from `~/Calibre Library` to the scratchpad, corrupt one chapter entry with the same method as `EpubFixtures.corruptEntry` (overwrite the entry's compressed bytes with 0xFF; a short Python `zipfile`/`struct` script is fine, kept in the scratchpad, not the repo), and `adb push` it to `/sdcard/Books/damaged.epub`.

Launch: `adb shell am start -n com.quire.reader.dbtest/com.quire.reader.MainActivity`, enable indexing if prompted, and wait for indexing to finish.

- [ ] **Step 4: Check the outcomes in the database**

```zsh
adb shell "run-as com.quire.reader.dbtest sqlite3 databases/quire.db \"SELECT b.title, s.status, s.chunkCount, s.truncated, s.unreadableResources FROM book b JOIN index_state s ON s.bookId = b.id\""
```

Expected: *Juliet Takes a Breath* `done` with several hundred chunks (previously 1); the damaged book `done` with `unreadableResources = 1`.

- [ ] **Step 5: Navigate from a deep match**

In the app, search "Inside books" for a distinctive phrase from late in Juliet (pick one from `adb shell run-as … sqlite3 … "SELECT text FROM text_chunk WHERE bookId = <id> ORDER BY seq DESC LIMIT 1"`), tap the snippet, and confirm the reader opens there with the phrase underlined. Screenshot: `adb exec-out screencap -p > docs/screenshots/juliet-deep-match.png`.

- [ ] **Step 6: Notes and coverage**

Search a word present in the damaged book; screenshot the card showing "Parts of this book couldn’t be read, so some passages may be missing" (`docs/screenshots/partly-unreadable-card.png`), and Settings' coverage line counting it as partly indexed (`docs/screenshots/coverage-partly-indexed.png`).

- [ ] **Step 7: Sparse log and speed**

Run `adb logcat -d -s LibraryIndexer` and record any `sparse resource` lines. For speed, time indexing of the same 5 healthy books from the scale fixture before (checkout `9a4539e`, build, install, Rebuild, time) and after (this branch); report books/min for both. Expected: no meaningful difference.

- [ ] **Step 8: Record and commit**

Add `## Verification results` to the design spec with: test counts, Juliet chunk count before/after, damaged-book state, screenshot paths, sparse log lines (or "none"), and the timing comparison.

```bash
git add docs/superpowers/specs/2026-10-04-fallback-extractor-design.md docs/screenshots/
git commit -m "docs: verification results for normalised extraction

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
