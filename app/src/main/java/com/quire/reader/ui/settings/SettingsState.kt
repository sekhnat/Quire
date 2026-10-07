package com.quire.reader.ui.settings

import android.net.Uri
import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.db.MissingBookRow
import com.quire.reader.data.index.IndexActivity
import com.quire.reader.data.index.IndexStorageBytes
import com.quire.reader.ui.AppNavigator
import com.quire.reader.ui.IndexerControl
import com.quire.reader.ui.NotesExport
import com.quire.reader.ui.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The app-wide settings and library facts Settings shows and changes (`LibraryRepository`). */
interface LibrarySettings {
  val readerDefaults: Flow<ReaderPrefs>
  suspend fun setReaderDefaults(prefs: ReaderPrefs)
  /** Whether the stored global defaults carry non-factory advanced values. */
  val advancedDefaultsCustomized: Flow<Boolean>
  val advancedReadingEnabled: Flow<Boolean>
  suspend fun setAdvancedReadingEnabled(v: Boolean)
  suspend fun clearAllBookPrefs()
  val useCalibre: Flow<Boolean>
  suspend fun setUseCalibre(v: Boolean)
  val watchNewBooks: Flow<Boolean>
  suspend fun setWatchNewBooks(v: Boolean)
  val indexingEnabled: Flow<Boolean>
  suspend fun setIndexingEnabled(v: Boolean)
  val indexChargingOnly: Flow<Boolean>
  suspend fun setIndexChargingOnly(v: Boolean)
  val indexCoverage: Flow<IndexCoverage>
  val indexedTextBytes: Flow<Long>
  suspend fun storageBytes(): IndexStorageBytes
  val missingBooks: Flow<List<MissingBookRow>>
  suspend fun forgetMissing(ids: List<Long>)
  suspend fun forgetAllMissing()
}

/**
 * The Settings screen: reading defaults, library and index options, storage, missing books, and (through [backup] and
 * [restore]) backups. Made for each visit and closed when Settings is left. Changes run on [persist], which outlives
 * the screen; rebuilding or deleting the index runs on [appScope], since it must finish even if the app's UI goes.
 */
class SettingsState(
  private val store: LibrarySettings,
  private val indexer: IndexerControl,
  private val notes: NotesExport,
  val backup: BackupState,
  val restore: RestoreState,
  private val nav: AppNavigator,
  private val toasts: Toasts,
  private val scope: CoroutineScope,
  private val persist: CoroutineScope,
  private val appScope: CoroutineScope,
) {
  /** The reading settings new books start with. */
  val defaults: StateFlow<ReaderPrefs> = store.readerDefaults.stateIn(scope, SharingStarted.Eagerly, ReaderPrefs())
  val useCalibre: StateFlow<Boolean> = store.useCalibre.stateIn(scope, SharingStarted.Eagerly, true)
  val watch: StateFlow<Boolean> = store.watchNewBooks.stateIn(scope, SharingStarted.Eagerly, true)
  val indexingEnabled: StateFlow<Boolean> = store.indexingEnabled.stateIn(scope, SharingStarted.Eagerly, true)
  val indexChargingOnly: StateFlow<Boolean> = store.indexChargingOnly.stateIn(scope, SharingStarted.Eagerly, false)
  /** Whether the global defaults carry non-factory advanced values (the global restore action). */
  val advancedDefaultsCustomized: StateFlow<Boolean> = store.advancedDefaultsCustomized.stateIn(scope, SharingStarted.Eagerly, false)
  /** Whether the advanced reading controls are shown at all; independent of their values. */
  val advancedReadingEnabled: StateFlow<Boolean> = store.advancedReadingEnabled.stateIn(scope, SharingStarted.Eagerly, false)

  /** How much of the library is searchable and what the indexer is doing. */
  val indexCoverage: StateFlow<IndexCoverage?> = store.indexCoverage.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
  val indexActivity: StateFlow<IndexActivity> = indexer.activity
  val indexedTextBytes: StateFlow<Long> = store.indexedTextBytes.stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0L)

  private val _storageBytes = MutableStateFlow<IndexStorageBytes?>(null)
  /** Disk the library and the search index each take, or null until first measured. */
  val storageBytes: StateFlow<IndexStorageBytes?> = _storageBytes

  /** Books whose file is gone but whose reading history is kept. */
  val missingBooks: StateFlow<List<MissingBookRow>> = store.missingBooks.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

  init {
    refreshDatabaseBytes()
    backup.refreshSizes()
  }

  fun leave() = nav.openLibrary()

  /** Stops what this visit was measuring and observing; changes already made carry on. */
  fun close() = scope.cancel()

  fun updateDefaults(change: (ReaderPrefs) -> ReaderPrefs) {
    val next = change(defaults.value)
    persist.launch { store.setReaderDefaults(next) }
  }

  /** Restores the global advanced controls to the factory values; book overrides stay. */
  fun restoreGlobalAdvanced() = persist.launch {
    store.setReaderDefaults(defaults.value.copy(advanced = AdvancedReaderPrefs()))
    toasts.show("Advanced reading settings restored")
  }

  fun setAdvancedReadingEnabled(v: Boolean) = persist.launch { store.setAdvancedReadingEnabled(v) }
  fun resetAllBookPrefs() = persist.launch { store.clearAllBookPrefs(); toasts.show("Every book now uses your defaults") }
  fun setUseCalibre(v: Boolean) = persist.launch { store.setUseCalibre(v); toasts.show("Takes effect on the next full rescan") }
  fun setWatch(v: Boolean) = persist.launch { store.setWatchNewBooks(v) }
  fun setIndexingEnabled(v: Boolean) = persist.launch { store.setIndexingEnabled(v) }
  fun setIndexChargingOnly(v: Boolean) = persist.launch { store.setIndexChargingOnly(v) }

  /** Measures the library and index files on disk. */
  fun refreshDatabaseBytes() = scope.launch { _storageBytes.value = store.storageBytes() }

  /** Clears the search index and indexes the library again under the current charging and reader rules. Books and reading state stay. */
  fun rebuildIndex() {
    appScope.launch {
      indexer.rebuild()
      refreshDatabaseBytes()
    }
    toasts.show("Rebuilding the search index")
  }

  /** Turns indexing off and deletes the search index. Books, metadata and reading state are untouched. */
  fun deleteSearchIndex() {
    appScope.launch {
      indexer.deleteIndex()
      refreshDatabaseBytes()
    }
    toasts.show("Search index deleted")
  }

  fun forgetMissing(id: Long) = persist.launch { store.forgetMissing(listOf(id)); toasts.show("Reading history deleted") }
  fun forgetAllMissing() = persist.launch { store.forgetAllMissing(); toasts.show("Reading history of missing books deleted") }

  fun exportNotes(bookId: Long, uri: Uri) = notes.export(bookId, uri)
}
