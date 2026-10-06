package com.quire.reader.data.index

import com.quire.reader.data.db.EligibleBook
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.IndexStateEntity
import com.quire.reader.data.db.QuireDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * What the index holds for the library, joined in Kotlin because books and index live in different databases: which
 * books still need indexing and how much of the library is searchable.
 */
class IndexCatalog(private val db: QuireDatabase, private val indexDb: IndexDatabase) {
  /** Readable books with no index state, or whose file changed since it was indexed; newest first. */
  suspend fun eligibleBooks(): List<EligibleBook> {
    val states = indexDb.states().all().associateBy { it.bookId }
    return db.books().indexable().filter { b -> needsIndexing(b.mtime, b.sizeBytes, states[b.id]?.let { IndexSignature(it.mtime, it.sizeBytes) }) }
  }

  fun observeCoverage(): Flow<IndexCoverage> = combine(db.books().observeIndexable(), indexDb.states().observeAll(), ::coverageOf)

  /** Chunk text bytes persisted across all books, including state kept for books whose file has since changed. */
  fun observeTextBytes(): Flow<Long> = indexDb.states().observeTextBytes()
}

/**
 * How much of the library is searchable. Every count covers [books] (the readable ones), and `searchable`, `failed` and
 * `skipped` count a book only while its index state still matches the book's current signature. `partial` counts
 * searchable books whose index is missing text (size cap or unreadable resources).
 */
fun coverageOf(books: List<EligibleBook>, states: List<IndexStateEntity>): IndexCoverage {
  val byBook = states.associateBy { it.bookId }
  var searchable = 0
  var failed = 0
  var skipped = 0
  var partial = 0
  for (b in books) {
    val s = byBook[b.id]?.takeIf { it.mtime == b.mtime && it.sizeBytes == b.sizeBytes } ?: continue
    when (s.status) {
      IndexStateEntity.STATUS_DONE -> {
        searchable++
        if (s.truncated || s.unreadableResources > 0) partial++
      }
      IndexStateEntity.STATUS_FAILED -> failed++
      IndexStateEntity.STATUS_SKIPPED -> skipped++
    }
  }
  return IndexCoverage(books.size, searchable, failed, skipped, partial)
}
