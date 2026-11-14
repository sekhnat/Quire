package com.quire.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.quire.reader.data.ExternalOpenResult
import kotlinx.coroutines.launch

/** A book to open, or a message to show, handed from [OpenBookActivity] to the app's UI through [QuireApplication.externalOpens]. */
data class ExternalOpenRequest(val bookId: Long?, val message: String?)

/**
 * Receives book files from other apps: "Open with" and tapping a file (`VIEW`), and the share sheet's "Add to Quire"
 * (`SEND`, `SEND_MULTIPLE`). It has no UI. It brings each file into the library (matching a book already there, scanning
 * a watched folder, or copying it in; see LibraryRepository.addExternal), then hands the book to [MainActivity] in
 * Quire's own task and finishes, so Back from the reader goes to Quire's library rather than to the app that sent it.
 *
 * It stays alive until the files are read: a URI permission granted with the intent lasts only while the activity that
 * received it is running. The intent's data is untrusted; nothing here builds a path from it.
 */
class OpenBookActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val viewing = intent.action == Intent.ACTION_VIEW
    val uris = handedOver(intent)
    if (uris.isEmpty()) {
      finish()
      return
    }
    val library = (application as QuireApplication).library
    lifecycleScope.launch {
      if (viewing) {
        when (val r = library.openExternal(uris.first())) {
          is ExternalOpenResult.Opened -> hand(ExternalOpenRequest(r.bookId, if (r.added) ADDED else null))
          ExternalOpenResult.Unreadable -> Toast.makeText(this@OpenBookActivity, CANNOT_OPEN, Toast.LENGTH_SHORT).show()
        }
      } else {
        val results = library.addExternal(uris)
        val books = results.filterNotNull()
        when {
          books.isEmpty() -> Toast.makeText(this@OpenBookActivity, if (uris.size == 1) CANNOT_OPEN else CANNOT_OPEN_ALL, Toast.LENGTH_SHORT).show()
          uris.size == 1 -> hand(ExternalOpenRequest(books.single().first, if (books.single().second) ADDED else null))
          else -> hand(ExternalOpenRequest(null, addedSummary(added = books.count { it.second }, already = books.count { !it.second }, failed = results.count { it == null })))
        }
      }
      finish()
    }
  }

  private fun hand(request: ExternalOpenRequest) {
    startActivity(
      Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_BOOK_ID, request.bookId ?: -1L)
        .putExtra(EXTRA_MESSAGE, request.message),
    )
  }

  companion object {
    const val EXTRA_BOOK_ID = "com.quire.reader.extra.BOOK_ID"
    const val EXTRA_MESSAGE = "com.quire.reader.extra.MESSAGE"
    private const val ADDED = "Added to your library"
    private const val CANNOT_OPEN = "Quire can't open this file"
    private const val CANNOT_OPEN_ALL = "Quire can't open these files"

    /** At most this many files from one share; more is not a reading list but a mistake. */
    private const val MAX_FILES = 200

    /** The files an intent hands over: its data for `VIEW`, its streams for `SEND` and `SEND_MULTIPLE`. */
    fun handedOver(intent: Intent): List<Uri> = when (intent.action) {
      Intent.ACTION_VIEW -> listOfNotNull(intent.data)
      Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
      Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
      else -> emptyList()
    }.filter { it.scheme == "content" || it.scheme == "file" }.take(MAX_FILES)

    /** "3 books added · 1 already in your library · 1 couldn't be opened". */
    fun addedSummary(added: Int, already: Int, failed: Int): String = listOfNotNull(
      added.takeIf { it > 0 }?.let { if (it == 1) "1 book added" else "$it books added" },
      already.takeIf { it > 0 }?.let { "$it already in your library" },
      failed.takeIf { it > 0 }?.let { "$it couldn't be opened" },
    ).joinToString(" · ")
  }
}
