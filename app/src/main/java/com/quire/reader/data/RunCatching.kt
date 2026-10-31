package com.quire.reader.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspend code: a cancellation is rethrown, so a cancelled job stops instead of carrying on and
 * recording the cancellation as a failure of whatever it was doing.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
  runCatching(block).onFailure { if (it is CancellationException) throw it }
