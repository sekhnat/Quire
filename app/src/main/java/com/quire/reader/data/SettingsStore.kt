package com.quire.reader.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.backup.BackupContents
import com.quire.reader.data.backup.BackupInterval
import com.quire.reader.data.backup.SnapshotSettings
import com.quire.reader.data.scan.StoragePaths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** App-wide settings that survive restarts. */
class SettingsStore(context: Context) {
  private val store = context.applicationContext.dataStore

  private fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> = store.data.map { it[key] ?: default }

  val onboardingDone: Flow<Boolean> = flow(ONBOARDING_DONE, false)
  val useCalibre: Flow<Boolean> = flow(USE_CALIBRE, true)
  val watchNewBooks: Flow<Boolean> = flow(WATCH_NEW, true)
  /** Whether the library text index is kept up to date in the background. */
  val indexingEnabled: Flow<Boolean> = flow(INDEXING_ENABLED, true)
  /** Index only while the device is charging. */
  val indexChargingOnly: Flow<Boolean> = flow(INDEX_CHARGING_ONLY, false)

  /** False until the scan has re-read the books a cover-broken build stored without one (see [LibraryScanner]). */
  val coversBackfilled: Flow<Boolean> = flow(COVERS_BACKFILLED, false)
  suspend fun setOnboardingDone(v: Boolean) = store.edit { it[ONBOARDING_DONE] = v }
  suspend fun setUseCalibre(v: Boolean) = store.edit { it[USE_CALIBRE] = v }
  suspend fun setWatchNewBooks(v: Boolean) = store.edit { it[WATCH_NEW] = v }
  suspend fun setIndexingEnabled(v: Boolean) = store.edit { it[INDEXING_ENABLED] = v }
  suspend fun setIndexChargingOnly(v: Boolean) = store.edit { it[INDEX_CHARGING_ONLY] = v }
  suspend fun setCoversBackfilled(v: Boolean) = store.edit { it[COVERS_BACKFILLED] = v }

  /** Whether the full-text index was merged into its fastest form after the first complete build; cleared when the index is. */
  val indexOptimized: Flow<Boolean> = flow(INDEX_OPTIMIZED, false)
  suspend fun setIndexOptimized(v: Boolean) = store.edit { it[INDEX_OPTIMIZED] = v }

  /** How library text search orders the books it finds. */
  val textSearchOrder: Flow<SearchOrder> = store.data.map { p -> SearchOrder.entries.firstOrNull { it.name == p[TEXT_SEARCH_ORDER] } ?: SearchOrder.Relevance }
  suspend fun setTextSearchOrder(v: SearchOrder) = store.edit { it[TEXT_SEARCH_ORDER] = v.name }

  /** The library layout last picked, by name; null until one is. */
  val libraryLayout: Flow<String?> = store.data.map { it[LIBRARY_LAYOUT] }
  suspend fun setLibraryLayout(name: String) = store.edit { it[LIBRARY_LAYOUT] = name }

  /** The library sort last picked, by name, and its direction; null until one is. */
  val librarySort: Flow<String?> = store.data.map { it[LIBRARY_SORT] }
  val librarySortAscending: Flow<Boolean?> = store.data.map { it[LIBRARY_SORT_ASCENDING] }
  suspend fun setLibrarySort(name: String, ascending: Boolean) = store.edit { it[LIBRARY_SORT] = name; it[LIBRARY_SORT_ASCENDING] = ascending }

  /** The reading settings new books start with. */
  val readerDefaults: Flow<ReaderPrefs> = store.data.map { ReaderPrefs.fromJson(it[READER_DEFAULTS]) ?: ReaderPrefs() }
  suspend fun setReaderDefaults(prefs: ReaderPrefs) = store.edit { it[READER_DEFAULTS] = prefs.toJson() }

  /** Whether the advanced reading controls are shown at all; independent of their values. */
  val advancedReadingEnabled: Flow<Boolean> = flow(ADVANCED_READING_ENABLED, false)
  suspend fun setAdvancedReadingEnabled(v: Boolean) = store.edit { it[ADVANCED_READING_ENABLED] = v }

  /** In-app dimming over the page, 30–100. */
  val brightness: Flow<Int> = flow(BRIGHTNESS, 100)
  suspend fun setBrightness(v: Int) = store.edit { it[BRIGHTNESS] = v.coerceIn(30, 100) }

  // ── full backups ─────────────────────────────────────────────────────────
  // Device-specific, so never part of the portable snapshot; they travel inside a full backup's copy of this file.

  /** What a full backup includes beyond the library database and settings; also used by scheduled backups. */
  val backupContents: Flow<BackupContents> = store.data.map {
    BackupContents(index = it[BACKUP_INDEX] ?: true, covers = it[BACKUP_COVERS] ?: true, imported = it[BACKUP_IMPORTED] ?: true)
  }
  suspend fun setBackupContents(c: BackupContents) = store.edit { it[BACKUP_INDEX] = c.index; it[BACKUP_COVERS] = c.covers; it[BACKUP_IMPORTED] = c.imported }

  val autoBackupEnabled: Flow<Boolean> = flow(AUTO_BACKUP, false)
  suspend fun setAutoBackupEnabled(v: Boolean) = store.edit { it[AUTO_BACKUP] = v }

  val autoBackupInterval: Flow<BackupInterval> = store.data.map { p -> BackupInterval.entries.firstOrNull { it.name == p[AUTO_BACKUP_INTERVAL] } ?: BackupInterval.Weekly }
  suspend fun setAutoBackupInterval(v: BackupInterval) = store.edit { it[AUTO_BACKUP_INTERVAL] = v.name }

  /** The folder scheduled backups are written to, on shared storage. */
  val autoBackupFolder: Flow<String> = flow(AUTO_BACKUP_FOLDER, DEFAULT_BACKUP_FOLDER)
  suspend fun setAutoBackupFolder(v: String) = store.edit { it[AUTO_BACKUP_FOLDER] = v }

  /** How many scheduled backups are kept in the folder; older ones are deleted after a new one is written. */
  val autoBackupKeep: Flow<Int> = flow(AUTO_BACKUP_KEEP, 5)
  suspend fun setAutoBackupKeep(v: Int) = store.edit { it[AUTO_BACKUP_KEEP] = v.coerceIn(1, 30) }

  /** When the last full backup finished, epoch millis; 0 = never. */
  val lastBackupAt: Flow<Long> = flow(LAST_BACKUP_AT, 0L)
  /** Why the last full backup failed, or null when it succeeded. */
  val lastBackupError: Flow<String?> = store.data.map { it[LAST_BACKUP_ERROR] }
  suspend fun recordBackup(at: Long, error: String?) = store.edit {
    if (error == null) { it[LAST_BACKUP_AT] = at; it.remove(LAST_BACKUP_ERROR) } else it[LAST_BACKUP_ERROR] = error
  }

  /** Emits whenever any setting changes; the snapshot writer re-captures on it. */
  val changes: Flow<Unit> = store.data.map { }

  /**
   * The settings a snapshot carries, with their current values (defaults included). Setup and
   * maintenance flags are deliberately absent: they describe this device's progress, not preferences.
   */
  suspend fun capture(): SnapshotSettings {
    val prefs = store.data.first()
    return SnapshotSettings(
      useCalibre = prefs[USE_CALIBRE] ?: true,
      watchNewBooks = prefs[WATCH_NEW] ?: true,
      indexingEnabled = prefs[INDEXING_ENABLED] ?: true,
      indexChargingOnly = prefs[INDEX_CHARGING_ONLY] ?: false,
      brightness = prefs[BRIGHTNESS] ?: 100,
      readerDefaults = prefs[READER_DEFAULTS]?.let(ReaderPrefs::fromJson),
      advancedReadingEnabled = prefs[ADVANCED_READING_ENABLED] ?: false,
      textSearchOrder = SearchOrder.entries.firstOrNull { it.name == prefs[TEXT_SEARCH_ORDER] }?.name ?: SearchOrder.Relevance.name,
    )
  }

  /** Restores the settings a snapshot carries; fields the snapshot omits keep their local values. */
  suspend fun applySettings(s: SnapshotSettings) {
    s.useCalibre?.let { setUseCalibre(it) }
    s.watchNewBooks?.let { setWatchNewBooks(it) }
    s.indexingEnabled?.let { setIndexingEnabled(it) }
    s.indexChargingOnly?.let { setIndexChargingOnly(it) }
    s.brightness?.let { setBrightness(it) }
    s.readerDefaults?.let { setReaderDefaults(it) }
    s.advancedReadingEnabled?.let { setAdvancedReadingEnabled(it) }
    s.textSearchOrder?.let { setTextSearchOrder(SearchOrder.valueOf(it)) }
  }

  companion object {
    val DEFAULT_BACKUP_FOLDER = "${StoragePaths.PRIMARY_ROOT}/Documents/Quire Backups"

    private val BACKUP_INDEX = booleanPreferencesKey("backup_include_index")
    private val BACKUP_COVERS = booleanPreferencesKey("backup_include_covers")
    private val BACKUP_IMPORTED = booleanPreferencesKey("backup_include_imported")
    private val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
    private val AUTO_BACKUP_INTERVAL = stringPreferencesKey("auto_backup_interval")
    private val AUTO_BACKUP_FOLDER = stringPreferencesKey("auto_backup_folder")
    private val AUTO_BACKUP_KEEP = intPreferencesKey("auto_backup_keep")
    private val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
    private val LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")

    private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    private val USE_CALIBRE = booleanPreferencesKey("use_calibre")
    private val WATCH_NEW = booleanPreferencesKey("watch_new_books")
    private val COVERS_BACKFILLED = booleanPreferencesKey("covers_backfilled")
    private val INDEXING_ENABLED = booleanPreferencesKey("indexing_enabled")
    private val INDEX_CHARGING_ONLY = booleanPreferencesKey("index_charging_only")
    private val READER_DEFAULTS = stringPreferencesKey("reader_defaults")
    private val ADVANCED_READING_ENABLED = booleanPreferencesKey("advanced_reading_enabled")
    private val BRIGHTNESS = intPreferencesKey("brightness")
    private val INDEX_OPTIMIZED = booleanPreferencesKey("index_optimized")
    private val TEXT_SEARCH_ORDER = stringPreferencesKey("text_search_order")
    private val LIBRARY_LAYOUT = stringPreferencesKey("library_layout")
    private val LIBRARY_SORT = stringPreferencesKey("library_sort")
    private val LIBRARY_SORT_ASCENDING = booleanPreferencesKey("library_sort_ascending")
  }
}
