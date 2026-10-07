package com.quire.reader.ui

import com.quire.reader.data.index.IndexActivity
import kotlinx.coroutines.flow.StateFlow

/** What feature state holders may ask of the background indexer (`LibraryIndexer`). */
interface IndexerControl {
  val activity: StateFlow<IndexActivity>
  /** Asks for an indexing run when the rules allow one. */
  fun request()
  /** Background indexing steps aside while a reader is opening or open. */
  fun setReaderBusy(busy: Boolean)
  /** Clears the index and indexes the library again. */
  suspend fun rebuild()
  /** Turns indexing off and deletes the index. */
  suspend fun deleteIndex()
}
