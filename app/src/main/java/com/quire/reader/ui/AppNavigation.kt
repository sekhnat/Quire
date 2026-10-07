package com.quire.reader.ui

import com.quire.reader.data.index.IndexTarget
import com.quire.reader.ui.detail.DetailState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Where the app is. A destination that carries a state holder owns it: leaving the destination closes it. */
sealed interface Destination {
  data object Splash : Destination
  data object Onboard : Destination
  data object Library : Destination
  data class Detail(val state: DetailState) : Destination
  data object Reader : Destination
  data object Settings : Destination
}

/** How to open a book in the reader. */
data class ReaderRequest(
  val bookId: Long,
  /** Ignore the saved position (the "Read again" button). */
  val restart: Boolean = false,
  /** A library text-search result to open at, underlined, in place of the saved position for this opening only. */
  val target: IndexTarget? = null,
  /** "Show all in this book": open with the search overlay in library-search mode for this query. */
  val libraryQuery: String? = null,
)

/** Moves between destinations; what feature state holders use instead of reaching into each other. */
interface AppNavigator {
  fun openLibrary()
  /** The library narrowed to an author, series or tag (the links on a book's page). */
  fun openLibraryScope(scope: Scope)
  fun openDetail(bookId: Long)
  fun openReader(request: ReaderRequest)
  fun openSettings()
}

/** Shows a short message at the bottom of the screen. */
fun interface Toasts {
  fun show(text: String)
}

/** The one toast on screen: a newer message replaces the current one, and each disappears after a moment. */
class Toaster(private val scope: CoroutineScope) : Toasts {
  private val _text = MutableStateFlow<String?>(null)
  val text: StateFlow<String?> = _text
  private var job: Job? = null

  override fun show(text: String) {
    job?.cancel()
    _text.value = text
    job = scope.launch { delay(2200); _text.value = null }
  }
}

/** A scope for a feature state holder: cancelled with its parent, or on its own when the holder closes. */
fun CoroutineScope.childScope(): CoroutineScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
