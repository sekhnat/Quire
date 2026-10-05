package com.quire.reader.data.index

import com.quire.reader.data.db.BookEntity
import com.quire.reader.data.db.BookStateEntity
import com.quire.reader.data.db.DbTestCase
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.IndexStateEntity
import com.quire.reader.data.db.QuireDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library text search against real FTS5 tables: counting, seams, validity, filters, caps, ranking, CJK and paging. */
class TextSearcherTest : DbTestCase() {
  private class Fixture(val db: QuireDatabase, val index: IndexDatabase, val folderId: Long) {
    val store = IndexStore(RoomIndexSql(index))
    fun searcher(maxExamined: Int = MAX_COUNTED_PASSAGES, perBookMax: Int = TextSearcher.PER_BOOK_SEARCH_MAX, maxPrefixDocuments: Int = MAX_PREFIX_DOCUMENTS) =
      TextSearcher(db, index, store, maxExamined = maxExamined, perBookMax = perBookMax, maxPrefixDocuments = maxPrefixDocuments)
  }

  private fun fixture(): Fixture = open().let { Fixture(it, openIndex(), folder(it)) }

  /** Long enough that two paragraphs never share a chunk, short enough that one is never split. */
  private val pad = "pad ".repeat(TextChunker.MAX_CHARS / 8 + 10)

  private fun query(input: String): FtsQuery.Result.Query = FtsQuery.parse(input) as FtsQuery.Result.Query

  private fun Fixture.add(
    name: String, mtime: Long = 10, size: Long = 100, addedAt: Long = 0, readable: Boolean = true,
    author: String = "Author", series: String? = null, tags: List<String> = emptyList(),
  ): BookEntity = runBlocking {
    val entity = bookEntity(folderId, name, mtime, size, addedAt, readable).copy(primaryAuthor = author, author = author, series = series)
    entity.copy(id = db.books().save(entity, tags))
  }

  /** Indexes [paragraphs] as the indexer would: through the real chunker, one source element each. */
  private fun Fixture.index(book: BookEntity, paragraphs: List<String>, chapter: String = "Chapter One", truncated: Boolean = false, unreadable: Int = 0): List<IndexChunk> {
    val elements = paragraphs.map { SourceElement("ch1.xhtml", it, false, "application/xhtml+xml", 0.25, 0.25, chapter) }
    val chunks = TextChunker.chunk(elements).chunks
    runBlocking { store.replaceBook(book.id, book.mtime, book.sizeBytes, chunks, truncated, unreadable) }
    return chunks
  }

  private fun Fixture.open(book: BookEntity, at: Long) = runBlocking {
    db.states().put(BookStateEntity(book.id, status = BookStateEntity.STATUS_READING, lastOpenedAt = at))
  }

  private fun Fixture.search(
    input: String, filters: TextSearchFilters = TextSearchFilters.None, max: Int = MAX_COUNTED_PASSAGES, perBookMax: Int = TextSearcher.PER_BOOK_SEARCH_MAX,
    now: Long = 1_000_000_000L, order: SearchOrder = SearchOrder.Library,
  ) = runBlocking { searcher(maxExamined = max, perBookMax = perBookMax).search(query(input), filters, order, now) }

  private fun Fixture.page(input: String, bookId: Long, afterSeq: Int = -1) = runBlocking { searcher().page(query(input), bookId, afterSeq) }

  private fun TextSearchResult.titles() = books.map { it.book.title }

  private fun TextSearchResult.counts() = books.associate { it.book.title to it.passages.value }

  @Test fun `books are grouped with their passage counts and ranked by count then recent opening then id`() {
    val f = fixture()
    val many = f.add("Many")
    val few = f.add("Few")
    val tieA = f.add("TieA")
    val tieB = f.add("TieB")
    val tieC = f.add("TieC")
    val none = f.add("None")
    f.index(many, List(5) { "The heron stood in the reeds number $it. " + pad })
    f.index(few, listOf("A heron flew over the reeds."))
    for (b in listOf(tieA, tieB, tieC)) f.index(b, List(2) { "Another heron waits here. " + pad })
    f.index(none, listOf("Nothing about birds appears in this book."))
    f.open(tieB, at = 900)
    f.open(tieC, at = 50)

    val result = f.search("heron")

    assertEquals(listOf("Many", "TieB", "TieC", "TieA", "Few"), result.titles())
    assertEquals(mapOf("Many" to 5, "TieA" to 2, "TieB" to 2, "TieC" to 2, "Few" to 1), result.counts())
    assertEquals(5, result.matchingBooks)
    assertFalse(result.capped)
  }

  @Test fun `a passage counts once however often the word appears in it`() {
    val f = fixture()
    val book = f.add("Echo")
    // Each paragraph is long enough to be a passage of its own.
    val long = "heron ".repeat(60) + pad
    f.index(book, listOf(long, long, "no match in this one " + pad))

    val result = f.search("heron")

    assertEquals(2, result.books.single().passages.value)
  }

  @Test fun `a phrase across the split of a long element is found once, for the chunk after the split`() {
    val f = fixture()
    val book = f.add("Boundaries")
    val words = List(1200) { "w$it" }
    val chunks = f.index(book, listOf(words.joinToString(" ")))
    assertTrue(chunks.size >= 3)
    var start = 0
    for (c in chunks.drop(1)) {
      start += Tokenizer.tokenize(chunks[c.seq - 1].text).size
      assertTrue("chunk ${c.seq} continues the split element", c.seam != null)
      for (before in listOf(1, 5, 32, 63)) {
        val phrase = "\"" + words.subList(start - before, start + 1).joinToString(" ") + "\""
        val result = f.search(phrase)
        assertEquals("$phrase is one passage", 1, result.books.single().passages.value)
        val snippet = result.books.single().snippets.single()
        assertEquals("$phrase belongs to chunk ${c.seq}", c.seq, snippet.seq)
        assertEquals(listOf(c.seq), f.page(phrase, book.id).snippets.map { it.seq })
      }
      // Wholly inside one chunk, the same words are found in that chunk alone.
      assertEquals(c.seq, f.search("\"${words[start]} ${words[start + 1]}\"").books.single().snippets.single().seq)
    }
  }

  @Test fun `a phrase or AND query across two whole elements in different chunks is not found, as documented`() {
    val f = fixture()
    val book = f.add("Two paragraphs")
    val chunks = f.index(book, listOf("First paragraph ends with alpha. " + pad + " omega", "beta starts the second paragraph. " + pad))
    assertEquals(2, chunks.size)
    assertTrue(chunks.none { it.seam != null })
    assertEquals(emptyList<String>(), f.search("\"omega beta\"").titles())
    assertEquals(emptyList<String>(), f.search("alpha second ").titles())
    assertEquals(listOf("Two paragraphs"), f.search("\"ends with alpha\"").titles())
  }

  @Test fun `a book changed since it was indexed is not searched until it is indexed again`() {
    val f = fixture()
    val book = f.add("Moved")
    f.index(book, listOf("The heron stood alone."))
    assertEquals(listOf("Moved"), f.search("heron").titles())

    runBlocking { f.db.books().update(book.copy(mtime = book.mtime + 1)) }
    assertEquals(emptyList<String>(), f.search("heron").titles())

    runBlocking { f.db.books().update(book.copy(mtime = book.mtime + 1, sizeBytes = book.sizeBytes + 7)) }
    f.index(book.copy(mtime = book.mtime + 1, sizeBytes = book.sizeBytes + 7), listOf("The heron stood alone again."))
    assertEquals(listOf("Moved"), f.search("heron").titles())
  }

  @Test fun `an unreadable book never appears even if it still has an index`() {
    val f = fixture()
    val book = f.add("Broken")
    f.index(book, listOf("The heron stood alone."))
    runBlocking { f.db.books().setReadable(book.id, false) }
    assertEquals(emptyList<String>(), f.search("heron").titles())
    runBlocking { f.db.books().setReadable(book.id, true) }
    assertEquals(listOf("Broken"), f.search("heron").titles())
  }

  @Test fun `a deleted book and its matches disappear`() {
    val f = fixture()
    val gone = f.add("Gone")
    val kept = f.add("Kept")
    f.index(gone, listOf("The heron stood alone."))
    f.index(kept, listOf("Another heron stood here."))

    runBlocking { f.db.books().delete(listOf(gone.id)) }

    assertEquals(listOf("Kept"), f.search("heron").titles())
    assertEquals(emptyList<Snippet>(), f.page("heron", gone.id).snippets)
  }

  @Test fun `a failed or skipped book has no text to match`() {
    val f = fixture()
    val book = f.add("Skipped")
    f.index(book, listOf("The heron stood alone."))
    runBlocking { f.store.markTerminal(book.id, book.mtime, book.sizeBytes, "skipped") }
    assertEquals(emptyList<String>(), f.search("heron").titles())
  }

  @Test fun `scope and status filters narrow the results and the metadata query plays no part`() {
    val f = fixture()
    val now = 1_000_000_000L
    val dayMs = 24L * 60 * 60 * 1000
    val austen = f.add("Emma", author = "Jane Austen", series = "Highbury", tags = listOf("Classic"), addedAt = now - 100 * dayMs)
    val shelley = f.add("Frankenstein", author = "Mary Shelley", tags = listOf("Gothic", "Classic"), addedAt = now - 100 * dayMs)
    val doyle = f.add("Hound", author = "Arthur Doyle", series = "Holmes", addedAt = now - 2 * dayMs)
    val finished = f.add("Finished", author = "Arthur Doyle", addedAt = now - 200 * dayMs)
    for (b in listOf(austen, shelley, doyle, finished)) f.index(b, listOf("The heron stood alone."))
    runBlocking {
      f.db.states().put(BookStateEntity(austen.id, status = BookStateEntity.STATUS_READING, lastOpenedAt = 5))
      f.db.states().put(BookStateEntity(finished.id, status = BookStateEntity.STATUS_FINISHED, lastOpenedAt = 3))
    }

    fun titles(filters: TextSearchFilters) = f.search("heron", filters, now = now).titles().sorted()

    assertEquals(listOf("Emma", "Finished", "Frankenstein", "Hound"), titles(TextSearchFilters.None))
    assertEquals(listOf("Emma"), titles(TextSearchFilters(author = "Jane Austen")))
    assertEquals(listOf("Finished", "Hound"), titles(TextSearchFilters(author = "Arthur Doyle")))
    assertEquals(listOf("Hound"), titles(TextSearchFilters(series = "Holmes")))
    assertEquals(listOf("Emma", "Frankenstein"), titles(TextSearchFilters(tag = "Classic")))
    assertEquals(listOf("Frankenstein"), titles(TextSearchFilters(tag = "Gothic")))
    assertEquals(listOf("Emma"), titles(TextSearchFilters(status = TextStatusFilter.Reading)))
    assertEquals(listOf("Finished"), titles(TextSearchFilters(status = TextStatusFilter.Finished)))
    assertEquals(listOf("Frankenstein", "Hound"), titles(TextSearchFilters(status = TextStatusFilter.Unread)))
    assertEquals(listOf("Hound"), titles(TextSearchFilters(status = TextStatusFilter.Recent)))
    assertEquals(emptyList<String>(), titles(TextSearchFilters(tag = "Classic", status = TextStatusFilter.Finished)))
  }

  @Test fun `prefix phrase accent and case queries behave as the query says`() {
    val f = fixture()
    val a = f.add("A")
    val b = f.add("B")
    f.index(a, listOf("Pemberley was a large handsome stone building. Café au lait, naïve Zürich."))
    f.index(b, listOf("Pembroke and the large stone handsome gate."))

    assertEquals(listOf("A", "B"), f.search("pem").titles().sorted())
    assertEquals(listOf("A"), f.search("pember").titles())
    assertEquals(listOf("B"), f.search("pembroke ").titles())
    assertEquals(listOf("A"), f.search("\"large handsome\"").titles())
    assertEquals(listOf("B"), f.search("\"stone handsome\"").titles())
    assertEquals(listOf("A", "B"), f.search("large stone handsome").titles().sorted())
    assertEquals(listOf("A"), f.search("CAFE naive zurich").titles())
    assertEquals(emptyList<String>(), f.search("\"handsome large\"").titles())
    assertEquals(listOf("A"), f.search("\"large handsome\" pemb").titles())
  }

  @Test fun `the examine cap bounds the sample and flags counts as lower bounds while small result sets stay exact`() {
    val f = fixture()
    val books = List(4) { f.add("Book $it") }
    books.forEach { f.index(it, List(6) { i -> "A heron here number $i. " + pad }) }
    val total = f.index.chunkCount()

    val exact = f.search("heron", max = 1000)
    assertFalse(exact.capped)
    assertEquals(total, exact.books.sumOf { it.passages.value })
    assertTrue(exact.books.none { it.passages.isCapped })

    val capped = f.search("heron", max = 10)
    assertTrue(capped.capped)
    assertEquals(10, capped.books.sumOf { it.passages.value })
    assertTrue(capped.books.all { it.passages.isCapped && it.passages.label.endsWith("+") })
    assertTrue("the sample comes from the books indexed first", capped.books.first().book.title == "Book 0")
  }

  @Test fun `filters apply before the cap so a filtered library is not starved by books outside it`() {
    val f = fixture()
    val early = f.add("Early", author = "Early Author")
    val late = f.add("Late", author = "Late Author")
    f.index(early, List(10) { "A heron here number $it. " + pad })
    f.index(late, List(3) { "A heron there number $it. " + pad })

    val result = f.search("heron", TextSearchFilters(author = "Late Author"), max = 5)

    assertEquals(listOf("Late"), result.titles())
    assertEquals(3, result.books.single().passages.value)
    assertFalse(result.capped)
  }

  @Test fun `a narrow filter is searched inside its own books so a common word elsewhere cannot hide them`() {
    val f = fixture()
    val early = f.add("Early", author = "Early Author")
    val late = f.add("Late", author = "Late Author")
    f.index(early, List(30) { "A heron here number $it. " + pad })
    f.index(late, List(3) { "A heron there number $it. " + pad })

    val result = f.search("heron", TextSearchFilters(author = "Late Author"), max = 2)

    assertEquals(listOf("Late"), result.titles())
    assertEquals(2, result.books.single().passages.value) // the cap still applies to what is examined
    assertTrue(result.capped)
    assertEquals(3, f.search("heron", TextSearchFilters(author = "Late Author"), max = 5).books.single().passages.value)
  }

  @Test fun `a broad filter is streamed across the library and is exact when the scan stays within its limit`() {
    val f = fixture()
    val other = f.add("Other", author = "Other Author")
    val late = f.add("Late", author = "Late Author")
    f.index(other, List(5) { "A heron here number $it. " + pad })
    f.index(late, List(3) { "A heron there number $it. " + pad })

    val result = f.search("heron", TextSearchFilters(author = "Late Author"), max = 5, perBookMax = 0) // 8 matches, under 5 x 8

    assertEquals(3, result.books.single().passages.value)
    assertFalse(result.incomplete)
    assertFalse(result.capped)
  }

  @Test fun `a broad filter that cannot be scanned fully says so instead of reporting no match`() {
    val f = fixture()
    val other = f.add("Other", author = "Other Author")
    val holder = f.add("Holder", author = "Group")
    f.index(other, List(30) { "A heron here number $it. " + pad })
    f.index(holder, listOf("A heron in the group's book."))
    val filter = TextSearchFilters(author = "Group")

    val partial = runBlocking { f.searcher(maxExamined = 2, perBookMax = 0).search(query("heron"), filter, now = 0L) } // stops after 16 rows
    assertEquals(emptyList<String>(), partial.titles())
    assertTrue("an empty result that may be missing books must say so", partial.incomplete)

    val narrow = runBlocking { f.searcher(maxExamined = 2).search(query("heron"), filter, now = 0L) } // one book: searched inside its own range
    assertEquals(listOf("Holder"), narrow.titles())
    assertFalse(narrow.incomplete)
  }

  private fun Fixture.prefixCorpus(): Triple<BookEntity, BookEntity, BookEntity> {
    val a = add("A"); val b = add("B"); val c = add("C")
    index(a, listOf("Pemberley was a fine house. " + pad, "The pem was exact here. " + pad))
    index(b, listOf("Pembroke stood nearby. " + pad))
    index(c, listOf("Pemmican is food. " + pad, "Nothing here. " + pad))
    return Triple(a, b, c)
  }

  /** How many chunks hold a term starting with [prefix], as the index itself counts them. */
  private fun Fixture.prefixDocuments(prefix: String): Int =
    index.count("SELECT COALESCE(SUM(doc), 0) FROM chunk_terms WHERE term >= ? AND term < ?", prefix, prefixRangeEnd(prefix))

  private fun Fixture.searchWith(input: String, maxPrefixDocuments: Int): TextSearchResult =
    runBlocking { searcher(maxPrefixDocuments = maxPrefixDocuments).search(query(input), TextSearchFilters.None, now = 0L) }

  @Test fun `a prefix that is not too common runs as a prefix`() {
    val f = fixture()
    f.prefixCorpus()
    val result = f.searchWith("pem", maxPrefixDocuments = f.prefixDocuments("pem"))

    assertEquals(null, result.prefixDowngraded)
    assertEquals(listOf("A", "B", "C"), result.titles().sorted())
  }

  @Test fun `a prefix that is too common is searched as the exact word and the result says so`() {
    val f = fixture()
    f.prefixCorpus()
    val result = f.searchWith("PEM", maxPrefixDocuments = 3)

    assertEquals("PEM", result.prefixDowngraded)
    assertEquals(listOf("A"), result.titles()) // only the chunk with the word pem itself
    assertEquals(1, result.books.single().passages.value)
    assertEquals(listOf("pem"), result.books.single().snippets.single().spans.filter { it.hit }.map { it.text.lowercase() })
  }

  @Test fun `a downgraded prefix with no exact match is an empty result that still carries the downgrade`() {
    val f = fixture()
    f.prefixCorpus()
    val result = f.searchWith("pemb", maxPrefixDocuments = 1)

    assertEquals("pemb", result.prefixDowngraded)
    assertEquals(emptyList<String>(), result.titles())
    assertFalse(result.incomplete)
  }

  @Test fun `accented and capitalised typing is measured against the folded index terms`() {
    val f = fixture()
    val a = f.add("A")
    f.index(a, listOf("Zürich lay beyond. " + pad, "Zurbaran painted. " + pad))

    val documents = f.prefixDocuments("zur")
    assertTrue(documents >= 2)
    assertEquals(null, f.searchWith("ZÜR", maxPrefixDocuments = documents).prefixDowngraded)
    assertEquals("ZÜR", f.searchWith("ZÜR", maxPrefixDocuments = documents - 1).prefixDowngraded)
  }

  @Test fun `terms are counted across pages and the limit is crossed only by exceeding it`() {
    val f = fixture()
    val a = f.add("A")
    f.index(a, List(40) { "wo$it begins here. " + pad }) // 40 terms, one chunk each, more than one page of terms

    val documents = f.prefixDocuments("wo")
    assertTrue(documents >= 40)
    assertEquals("wo", f.searchWith("wo", maxPrefixDocuments = documents - 1).prefixDowngraded)
    assertEquals(null, f.searchWith("wo", maxPrefixDocuments = documents).prefixDowngraded)
  }

  @Test fun `only a final unfinished word is ever downgraded and a book page keeps using the prefix`() {
    val f = fixture()
    val (_, b, _) = f.prefixCorpus()

    assertEquals(null, f.searchWith("pem ", maxPrefixDocuments = 1).prefixDowngraded)
    assertEquals(null, f.searchWith("\"pem\"", maxPrefixDocuments = 1).prefixDowngraded)
    val page = runBlocking { f.searcher(maxPrefixDocuments = 1).page(query("pem"), b.id) }
    assertEquals(1, page.snippets.size) // pembroke, found by the prefix
  }

  @Test fun `at most forty books are returned and the rest are counted`() {
    val f = fixture()
    repeat(45) { f.index(f.add("Book $it", addedAt = it.toLong()), listOf("The heron stood alone number $it.")) }

    val result = f.search("heron")

    assertEquals(40, result.books.size)
    assertEquals(45, result.matchingBooks)
    assertEquals(5, result.moreBooks)
  }

  @Test fun `each book shows its first five matches in reading order with excerpt chapter and a navigable target`() {
    val f = fixture()
    val book = f.add("Heron Book", mtime = 77, size = 4242)
    f.index(book, List(8) { "Number $it begins and a heron stands in the reeds. " + pad }, chapter = "The Marsh")

    val shown = f.search("heron").books.single()

    assertEquals(8, shown.passages.value)
    assertEquals(listOf(0, 1, 2, 3, 4), shown.snippets.map { it.seq })
    val snippet = shown.snippets[2]
    assertEquals("The Marsh", snippet.chapter)
    assertEquals(listOf("heron"), snippet.spans.filter { it.hit }.map { it.text })
    assertTrue(snippet.spans.joinToString("") { it.text }.startsWith("Number 2 begins and a heron"))
    assertEquals(77L, snippet.target.indexedMtime)
    assertEquals(4242L, snippet.target.indexedSizeBytes)
    assertEquals("heron", snippet.target.highlight)
    val locator = JSONObject(snippet.target.locatorJson)
    assertEquals("ch1.xhtml", locator.getString("href"))
    assertEquals("heron", locator.getJSONObject("text").getString("highlight"))
    assertEquals("Number 2 begins and a ", locator.getJSONObject("text").getString("before"))
    assertTrue(snippet.target.isCurrentFor(77, 4242))
    assertFalse(snippet.target.isCurrentFor(78, 4242))
  }

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
    assertEquals(IndexGap.PartsUnreadable, f.page("heron", damaged.id).gap)
  }

  @Test fun `show all pages a single book in reading order without gaps or repeats whatever else is indexed`() {
    val f = fixture()
    val before = f.add("Before")
    val book = f.add("Target")
    val after = f.add("After")
    f.index(before, List(30) { "A heron before number $it. " + pad })
    f.index(book, List(45) { "A heron target number $it. " + pad })
    f.index(after, List(30) { "A heron after number $it. " + pad })
    val searcher = f.searcher()

    val pages = ArrayList<BookTextPage>()
    var cursor = -1
    while (true) {
      val page = runBlocking { searcher.page(query("heron"), book.id, cursor) }
      pages += page
      cursor = page.nextAfterSeq ?: break
    }

    assertEquals(listOf(20, 20, 5), pages.map { it.snippets.size })
    assertEquals((0 until 45).toList(), pages.flatMap { it.snippets }.map { it.seq })
    assertTrue(pages.flatMap { it.snippets }.all { s -> s.spans.joinToString("") { it.text }.contains("target") })
    assertEquals(listOf(19, 39, null), pages.map { it.nextAfterSeq })

    // A page asked for again after unrelated changes is the same page.
    f.index(after, List(5) { "A heron after again number $it. " })
    assertEquals(pages[1].snippets.map { it.seq }, runBlocking { searcher.page(query("heron"), book.id, 19) }.snippets.map { it.seq })
  }

  @Test fun `show all of a common word is not limited by the library cap`() {
    val f = fixture()
    val other = f.add("Other")
    val book = f.add("Big")
    f.index(other, List(40) { "A heron elsewhere number $it. " + pad })
    f.index(book, List(30) { "A heron here number $it. " + pad })

    val page = runBlocking { f.searcher(maxExamined = 5).page(query("heron"), book.id) }

    assertEquals(20, page.snippets.size)
    assertEquals(19, page.nextAfterSeq)
  }

  @Test fun `show all skips a book whose index no longer matches the file`() {
    val f = fixture()
    val book = f.add("Moved")
    f.index(book, listOf("The heron stood alone."))
    runBlocking { f.db.books().update(book.copy(sizeBytes = book.sizeBytes + 1)) }

    val page = f.page("heron", book.id)

    assertEquals(BookTextPage(emptyList(), null, IndexGap.None), page)
  }

  @Test fun `odd input never throws whether it parses to a query or not`() {
    val f = fixture()
    val book = f.add("Odd")
    f.index(book, listOf("The heron said \"hello\" -- OR NOT near:foo * and (parentheses) with 100% of the e-mail text."))
    val inputs = listOf(
      "\"", "\"hello", "hello\"", "\"\"", "\"\" \"\"", "a-b", "-foo", "foo*", "*", "**", "title:foo", "text:heron", "NEAR/2 foo", "heron OR hello",
      "heron NOT hello", "(", ")", "'", "''", "\\", "%", "_", "\u0000heron", "heron\u0000", "🙂🙂", "日本語", "a\"b\"c\"d", "\"a\" \"b\" \"c\"", "\"heron", "e-mail", "100%",
      "ß", "ǅ", "́", "\"́\"", "heron ".repeat(60), "x ".repeat(70),
    )
    val searcher = f.searcher()
    for (input in inputs) {
      val parsed = FtsQuery.parse(input)
      if (parsed is FtsQuery.Result.Query) {
        runBlocking {
          searcher.search(parsed, TextSearchFilters.None, now = 0L)
          searcher.page(parsed, book.id)
        }
      }
    }
    assertEquals(listOf("Odd"), f.search("\"hello\" heron").titles())
  }

  @Test fun `the search follows the database as books are indexed and removed`() = runBlocking {
    val f = fixture()
    val book = f.add("Live")
    val seen = java.util.Collections.synchronizedList(ArrayList<List<String>>())
    val job = launch(Dispatchers.Default) { f.searcher().observe(query("heron"), TextSearchFilters.None).collect { seen += it.titles() } }
    suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(20) }

    until { seen.isNotEmpty() }
    assertEquals(emptyList<String>(), seen.last())
    f.index(book, listOf("The heron stood alone."))
    until { seen.last().isNotEmpty() }
    assertEquals(listOf("Live"), seen.last())
    f.db.books().delete(listOf(book.id))
    until { seen.last().isEmpty() }
    job.cancel()
  }

  @Test fun `relevance puts the book with the densest match first and library order counts passages`() {
    val f = fixture()
    val many = f.add("Many thin")
    val dense = f.add("Dense")
    f.index(many, List(6) { "A single heron flies over number $it. " + pad })
    f.index(dense, listOf("heron heron heron heron, the heron of herons, heron upon heron."))

    assertEquals(listOf("Dense", "Many thin"), f.search("heron", order = SearchOrder.Relevance).titles())
    assertEquals(listOf("Many thin", "Dense"), f.search("heron", order = SearchOrder.Library).titles())
    // Above the examine cap the count is not exact, so relevance falls back to the library order.
    assertEquals(listOf("Many thin", "Dense"), f.search("heron", order = SearchOrder.Relevance, max = 7).titles())
  }

  @Test fun `CJK text is found by any substring of one, two or three characters, alone or with Latin words`() {
    val f = fixture()
    val zh = f.add("Journey")
    val ja = f.add("Cat")
    val ko = f.add("Lucky day")
    f.index(zh, listOf("第一回 靈根育孕源流出 心性修持大道生。孫悟空在花果山。", "Chapter two 天下大勢，分久必合。"))
    f.index(ja, listOf("吾輩は猫である。名前はまだ無い。"))
    f.index(ko, listOf("새침하게 흐린 품이 눈이 올 듯하더니 아내에게 설렁탕을 사다 줄 수 있었다."))

    assertEquals(listOf("Cat"), f.search("猫").titles())
    assertEquals(listOf("Journey"), f.search("天下").titles())
    assertEquals(listOf("Journey"), f.search("孫悟空").titles())
    assertEquals(listOf("Journey"), f.search("悟空").titles()) // inside a run, which unicode61 alone could never find
    assertEquals(emptyList<String>(), f.search("空孫").titles())
    assertEquals(listOf("Journey"), f.search("chapter 天下").titles())
    assertEquals(emptyList<String>(), f.search("chapter 猫").titles())
    assertEquals(listOf("Lucky day"), f.search("아내").titles()) // a word with its particle attached
    val snippet = f.search("名前").books.single().snippets.single()
    assertEquals(listOf("名前"), snippet.spans.filter { it.hit }.map { it.text })
    assertEquals(listOf("吾輩"), f.page("吾輩", ja.id).snippets.single().spans.filter { it.hit }.map { it.text })
  }

  @Test fun `a common single CJK character is matched exactly instead of as a prefix and the result says so`() {
    val f = fixture()
    val a = f.add("A")
    f.index(a, listOf("猫が好き。" + pad, "黒猫" + pad, "猫" + pad))
    val all = runBlocking { f.searcher(maxPrefixDocuments = 10).search(query("猫"), TextSearchFilters.None, now = 0L) }
    assertEquals(null, all.prefixDowngraded)
    assertEquals(3, all.books.single().passages.value)
    val exact = runBlocking { f.searcher(maxPrefixDocuments = 1).search(query("猫"), TextSearchFilters.None, now = 0L) }
    assertEquals("猫", exact.prefixDowngraded)
    assertEquals(2, exact.books.single().passages.value) // where 猫 ends a run: 黒猫 and 猫
  }

  @Test fun `replacing a book with a changed file leaves no rows of the old text behind`() {
    val f = fixture()
    val book = f.add("Changing")
    val long = List(1200) { "w$it" }.joinToString(" ")
    f.index(book, listOf(long, "東京の夜。" + pad))
    val first = f.index.footprint()
    assertTrue(first.getValue("seam") > 0 && first.getValue("cjk_fts") > 0)

    val changed = book.copy(mtime = book.mtime + 1)
    runBlocking { f.db.books().update(changed) }
    f.index(changed, listOf("A short replacement text."))

    assertEquals(mapOf("chunk" to 1, "seam" to 0, "book_string" to 2, "index_state" to 1, "chunk_fts" to 1, "seam_fts" to 0, "cjk_fts" to 0), f.index.footprint())
    f.index.checkIntegrity()
    assertEquals(emptyList<Long>(), f.index.hits("w5"))
    assertEquals(listOf("Changing"), f.search("replacement").titles())
  }
}
