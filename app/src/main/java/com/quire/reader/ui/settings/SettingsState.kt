package com.quire.reader.ui.settings

import android.net.Uri
import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.ReaderControls
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.db.MissingBookRow
import com.quire.reader.data.index.IndexActivity
import com.quire.reader.data.index.IndexStorageBytes
import com.quire.reader.ui.AppNavigator
import com.quire.reader.ui.IndexerControl
import com.quire.reader.ui.NotesExport
import com.quire.reader.ui.Toasts
import com.quire.reader.ui.books
import com.quire.reader.ui.reader.FontChoices
import com.quire.reader.ui.reader.NoUserFonts
import com.quire.reader.ui.reader.UserFontsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
  val readerControls: Flow<ReaderControls>
  suspend fun setReaderControls(controls: ReaderControls)
  suspend fun clearAllBookPrefs()
  val useCalibre: Flow<Boolean>
  suspend fun setUseCalibre(v: Boolean)
  val watchNewBooks: Flow<Boolean>
  suspend fun setWatchNewBooks(v: Boolean)
  /** Whether books show their word count. */
  val showWordCount: Flow<Boolean>
  suspend fun setShowWordCount(v: Boolean)
  /** Every tag in the library, each once, in name order. */
  val tagNames: Flow<List<String>>
  /** Tags whose books the library leaves out. */
  val hiddenTags: Flow<Set<String>>
  suspend fun setTagHidden(tag: String, hidden: Boolean)
  val indexingEnabled: Flow<Boolean>
  suspend fun setIndexingEnabled(v: Boolean)
  val indexChargingOnly: Flow<Boolean>
  suspend fun setIndexChargingOnly(v: Boolean)
  val indexCoverage: Flow<IndexCoverage>
  val indexedTextBytes: Flow<Long>
  suspend fun storageBytes(): IndexStorageBytes
  val missingBooks: Flow<List<MissingBookRow>>
  suspend fun forgetMissing(ids: List<Long>)
  /** Paths of the book files removed from the library, which scans skip. */
  val removedBooks: Flow<Set<String>>
  /** Lets scans find the removed files [paths] again, and scans. */
  suspend fun restoreRemoved(paths: List<String>)
}

/** A book file removed from the library; [title] is known while the book is kept for its reading history. */
data class RemovedBook(val path: String, val title: String?) {
  val fileName: String get() = path.substringAfterLast('/')
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
  userFonts: UserFontsPort = NoUserFonts,
) {
  /** The user's fonts, for the font picker. */
  val fonts = FontChoices(userFonts, scope, persist, toasts)
  /** The reading settings new books start with. */
  val defaults: StateFlow<ReaderPrefs> = store.readerDefaults.stateIn(scope, SharingStarted.Eagerly, ReaderPrefs())
  val useCalibre: StateFlow<Boolean> = store.useCalibre.stateIn(scope, SharingStarted.Eagerly, true)
  val watch: StateFlow<Boolean> = store.watchNewBooks.stateIn(scope, SharingStarted.Eagerly, true)
  val showWordCount: StateFlow<Boolean> = store.showWordCount.stateIn(scope, SharingStarted.Eagerly, false)
  /** Tags whose books the library hides, and every tag the picker can offer. */
  val hiddenTags: StateFlow<Set<String>> = store.hiddenTags.stateIn(scope, SharingStarted.Eagerly, emptySet())
  val tagNames: StateFlow<List<String>> = store.tagNames.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
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

  private val missingAndRemoved = combine(store.missingBooks, store.removedBooks) { rows, removed -> rows to removed }

  /** Books whose file is gone but whose reading history is kept; books removed from the library are listed apart. */
  val missingBooks: StateFlow<List<MissingBookRow>> = missingAndRemoved.map { (rows, removed) -> rows.filter { it.path !in removed } }
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

  /** Book files removed from the library, by title (or file name). */
  val removedBooks: StateFlow<List<RemovedBook>> = missingAndRemoved.map { (rows, removed) ->
    val titles = rows.associate { it.path to it.title }
    removed.map { RemovedBook(it, titles[it]) }.sortedBy { (it.title ?: it.fileName).lowercase() }
  }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

  val readerControls: StateFlow<ReaderControls> = store.readerControls.stateIn(scope, SharingStarted.Eagerly, ReaderControls())
  fun updateControls(change: (ReaderControls) -> ReaderControls) {
    val next = change(readerControls.value)
    persist.launch { store.setReaderControls(next) }
  }
  fun resetAllBookPrefs() = persist.launch { store.clearAllBookPrefs(); toasts.show("Every book now uses your defaults") }
  fun setShowWordCount(v: Boolean) = persist.launch { store.setShowWordCount(v) }
  fun setUseCalibre(v: Boolean) = persist.launch { store.setUseCalibre(v); toasts.show("Takes effect on the next full rescan") }
  fun setWatch(v: Boolean) = persist.launch { store.setWatchNewBooks(v) }
  fun setTagHidden(tag: String, hidden: Boolean) = persist.launch { store.setTagHidden(tag, hidden) }
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
  /** Forgets the missing books listed; books removed from the library keep their history until brought back. */
  fun forgetAllMissing() = persist.launch {
    store.forgetMissing(missingBooks.value.map { it.id })
    toasts.show("Reading history of missing books deleted")
  }

  /** Brings removed book files back into the library: scans stop skipping them, and a scan finds them. */
  fun restoreRemoved(paths: List<String>) {
    if (paths.isEmpty()) return
    persist.launch {
      toasts.show("Scanning…")
      store.restoreRemoved(paths)
      toasts.show(if (paths.size == 1) "Book back in the library" else "${books(paths.size)} back in the library")
    }
  }

  fun exportNotes(bookId: Long, uri: Uri) = notes.export(bookId, uri)
}
