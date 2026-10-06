package com.quire.reader.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quire.reader.data.index.SearchOrder
import com.quire.reader.data.backup.SnapshotSettings
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

  /** The reading settings new books start with. */
  val readerDefaults: Flow<ReaderPrefs> = store.data.map { ReaderPrefs.fromJson(it[READER_DEFAULTS]) ?: ReaderPrefs() }
  suspend fun setReaderDefaults(prefs: ReaderPrefs) = store.edit { it[READER_DEFAULTS] = prefs.toJson() }

  /** In-app dimming over the page, 30–100. */
  val brightness: Flow<Int> = flow(BRIGHTNESS, 100)
  suspend fun setBrightness(v: Int) = store.edit { it[BRIGHTNESS] = v.coerceIn(30, 100) }

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
    s.textSearchOrder?.let { setTextSearchOrder(SearchOrder.valueOf(it)) }
  }

  private companion object {
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    val USE_CALIBRE = booleanPreferencesKey("use_calibre")
    val WATCH_NEW = booleanPreferencesKey("watch_new_books")
    val COVERS_BACKFILLED = booleanPreferencesKey("covers_backfilled")
    val INDEXING_ENABLED = booleanPreferencesKey("indexing_enabled")
    val INDEX_CHARGING_ONLY = booleanPreferencesKey("index_charging_only")
    val READER_DEFAULTS = stringPreferencesKey("reader_defaults")
    val BRIGHTNESS = intPreferencesKey("brightness")
    val INDEX_OPTIMIZED = booleanPreferencesKey("index_optimized")
    val TEXT_SEARCH_ORDER = stringPreferencesKey("text_search_order")
    val LIBRARY_LAYOUT = stringPreferencesKey("library_layout")
  }
}
