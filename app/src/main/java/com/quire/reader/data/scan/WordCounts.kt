package com.quire.reader.data.scan

import android.os.Process
import android.util.Log
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.data.db.UncountedBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Counts the words of books that have no count yet, one at a time, while [enabled] says the word count is shown. Ask for
 * a pass with [request]; requests made during a pass run one more pass after it, so a book stored meanwhile is counted.
 *
 * A file that cannot be read now (no all-files access yet, or the file changed since it was scanned) is skipped and left
 * uncounted, so a later scan brings it back; a file that opens but whose text cannot be read is stored as 0 (unknown),
 * so it is not read again until it changes. Counts are only stored for the file signature that was counted.
 */
class WordCounts(
  private val uncounted: suspend () -> List<UncountedBook>,
  private val store: suspend (counts: Map<UncountedBook, Int>) -> Unit,
  private val enabled: suspend () -> Boolean,
  private val scope: CoroutineScope,
  private val dispatcher: CoroutineDispatcher = backgroundThread(),
  private val count: (File) -> Int? = WordCounter::count,
  private val readable: (File, UncountedBook) -> Boolean = { f, b -> f.canRead() && f.length() == b.sizeBytes && f.lastModified() == b.mtime },
  private val clock: () -> Long = System::currentTimeMillis,
) {
  constructor(db: QuireDatabase, enabled: suspend () -> Boolean, scope: CoroutineScope) : this(
    uncounted = { db.books().uncounted() },
    store = { counts -> db.books().setWordCounts(counts) },
    enabled = enabled,
    scope = scope,
  )

  private val wake = Channel<Unit>(Channel.CONFLATED)
  private var loop: Job? = null

  /** Asks for a pass over the uncounted books. Safe to call often. */
  @Synchronized
  fun request() {
    if (loop == null) {
      loop = scope.launch(dispatcher) {
        while (true) {
          wake.receive()
          try {
            pass()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "word count pass failed", e)
          }
        }
      }
    }
    wake.trySend(Unit)
  }

  /**
   * Counts every uncounted book once; returns how many counts were stored. Counts are stored in batches (every
   * [BATCH_BOOKS] books or [BATCH_MILLIS]), since each store makes the library map its books again. A pass that stores
   * nothing changes no row, so nothing requests another one: skipped books wait for the next change to the library (a
   * scan) to be tried again.
   */
  internal suspend fun pass(): Int {
    var stored = 0
    val batch = LinkedHashMap<UncountedBook, Int>()
    var batchStarted = clock()
    suspend fun flush() {
      if (batch.isEmpty()) return
      store(LinkedHashMap(batch))
      stored += batch.size
      batch.clear()
    }
    try {
      for (book in uncounted()) {
        currentCoroutineContext().ensureActive()
        if (!enabled()) break
        val file = File(book.path)
        if (!readable(file, book)) continue
        val words = runCatching { count(file) }.getOrNull()
        currentCoroutineContext().ensureActive()
        if (words == null) Log.i(TAG, "cannot count the words of ${book.path}")
        if (batch.isEmpty()) batchStarted = clock()
        batch[book] = words ?: 0
        if (batch.size >= BATCH_BOOKS || clock() - batchStarted >= BATCH_MILLIS) flush()
      }
    } finally {
      // Counts already made are kept, also when the pass is cancelled or fails.
      withContext(NonCancellable) { flush() }
    }
    return stored
  }

  companion object {
    private const val TAG = "WordCounts"
    internal const val BATCH_BOOKS = 25
    internal const val BATCH_MILLIS = 2_000L

    /** One low-priority thread, so counting never competes with the UI. */
    private fun backgroundThread(): CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
      Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "word-counts").apply { isDaemon = true }
    }.asCoroutineDispatcher()
  }
}
