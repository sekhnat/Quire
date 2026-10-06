package com.quire.reader

import android.app.Application
import com.quire.reader.data.LibraryRepository
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.backup.RestoreCoordinator
import com.quire.reader.data.backup.SnapshotImporter
import com.quire.reader.data.backup.SnapshotWriter
import com.quire.reader.data.backup.backupDirectory
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.index.LibraryIndexer
import com.quire.reader.data.scan.CoverStore
import com.quire.reader.data.scan.LibraryScanner
import com.quire.reader.data.scan.ScanPhase
import com.quire.reader.data.scan.ScanWorker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import com.quire.reader.reader.PublicationLoader

/** Process-wide singletons; small enough that a hand-rolled container beats a DI framework. */
class QuireApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    appScope.launch {
      // Restored settings and onboarding routing must settle before scanning or indexing is scheduled.
      restore.prepare()
      snapshotWriter.start()
      launch {
        scanner.progress.filter { it.phase == ScanPhase.Done }.collect {
          if (restore.onScanCompleted() != null) snapshotWriter.flush()
        }
      }
      launch { settings.watchNewBooks.distinctUntilChanged().collect { ScanWorker.schedule(this@QuireApplication, it) } }
      launch {
        combine(settings.indexingEnabled, settings.indexChargingOnly) { enabled, chargingOnly -> enabled to chargingOnly }
          .distinctUntilChanged().drop(1).collect { indexer.applyPolicy() }
      }
      indexer.request()
    }
  }

  /** For work that must outlive a screen, such as saving the reading position as the reader closes. */
  val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
  val database by lazy { QuireDatabase.create(this) }
  val indexDatabase by lazy { IndexDatabase.create(this) }
  val settings by lazy { SettingsStore(this) }
  val covers by lazy { CoverStore(this) }
  val publicationLoader by lazy { PublicationLoader(this) }
  val scanner by lazy { LibraryScanner(database, publicationLoader, covers, settings) }
  val indexer by lazy { LibraryIndexer(this, database, indexDatabase, publicationLoader, settings, appScope) }
  val library by lazy { LibraryRepository(this, database, indexDatabase, scanner, covers, settings, indexer) }

  val restore by lazy { RestoreCoordinator(this, database, settings, SnapshotImporter(database, settings)) }
  val snapshotWriter by lazy {
    SnapshotWriter(database, settings, backupDirectory(this), appScope, isRestorePending = { restore.isWriteBlocked })
  }
}
