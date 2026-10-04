package com.quire.reader.data.scan

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Reads files while they are still being found: everything [walk] emits is handed to up to [parallelism]
 * readers straight away, and the call returns once the walk has ended and every read has finished. A large
 * folder tree can take a minute to walk on a phone, so books reach the library from the first seconds of a
 * scan instead of after the whole walk, and work done before the app is closed is kept.
 */
suspend fun <T> readWhileFinding(parallelism: Int, walk: suspend (emit: suspend (T) -> Unit) -> Unit, read: suspend (T) -> Unit) = coroutineScope {
  val queue = Channel<T>(Channel.UNLIMITED)
  val readers = List(parallelism) { launch { for (item in queue) read(item) } }
  try {
    walk { queue.send(it) }
  } finally {
    queue.close()
  }
  readers.joinAll()
}
