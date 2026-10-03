package com.quire.reader

import android.app.Application
import com.quire.reader.data.LibraryRepository
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.LibraryIndexer
import com.quire.reader.data.scan.CoverStore
import com.quire.reader.data.scan.LibraryScanner
import com.quire.reader.data.scan.ScanWorker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.quire.reader.reader.PublicationLoader

/** Process-wide singletons; small enough that a hand-rolled container beats a DI framework. */
class QuireApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    // Keep the background scan in step with the "Watch for new books" setting.
    appScope.launch { settings.watchNewBooks.distinctUntilChanged().collect { ScanWorker.schedule(this@QuireApplication, it) } }
    // Text indexing is independent of that: a changed indexing setting replaces the queued work, and startup asks for a run.
    appScope.launch {
      combine(settings.indexingEnabled, settings.indexChargingOnly) { enabled, chargingOnly -> enabled to chargingOnly }
        .distinctUntilChanged().drop(1).collect { indexer.applyPolicy() }
    }
    indexer.request()
  }

  /** For work that must outlive a screen, such as saving the reading position as the reader closes. */
  val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
  val database by lazy { QuireDatabase.create(this) }
  val settings by lazy { SettingsStore(this) }
  val covers by lazy { CoverStore(this) }
  val publicationLoader by lazy { PublicationLoader(this) }
  val scanner by lazy { LibraryScanner(database, publicationLoader, covers, settings) }
  val indexer by lazy { LibraryIndexer(this, database, publicationLoader, settings, appScope) }
  val library by lazy { LibraryRepository(this, database, scanner, covers, settings, indexer) }
}
