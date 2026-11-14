package com.quire.reader.ui.detail

import android.net.Uri
import com.quire.reader.ui.AppNavigator
import com.quire.reader.ui.NotesExport
import com.quire.reader.ui.ReaderRequest
import com.quire.reader.ui.Scope
import com.quire.reader.ui.Toasts
import com.quire.reader.data.EditField
import com.quire.reader.data.EditRejected
import com.quire.reader.data.db.BookEditEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The edits a book's page makes (`LibraryRepository`). */
interface BookEditor {
  suspend fun setFinished(bookId: Long, finished: Boolean)
  suspend fun setUserRating(bookId: Long, rating: Int?)
  suspend fun addTag(bookId: Long, tag: String)
  suspend fun removeTag(bookId: Long, tag: String)
  /** The user's own title, authors, series and cover for the book; null when nothing is edited. */
  fun edits(bookId: Long): Flow<BookEditEntity?> = flowOf(null)
  /** Sets one field, or resets it with null; throws [EditRejected] for a value that cannot be saved. */
  suspend fun setEdit(bookId: Long, field: EditField, value: String?) {}
  /** Gives the book the picked image as its cover, or its own cover back with null; false when the image is unreadable. */
  suspend fun setCover(bookId: Long, image: Uri?): Boolean = false
  suspend fun revertEdits(bookId: Long) {}
}

/**
 * One book's page: its edit sheet, the reading status, rating and tags, and the ways out of it. Made for each visit and
 * dropped when the page is left. Edits run on [persist], which outlives the page, so leaving right after one never
 * loses it.
 */
class DetailState(
  val bookId: Long,
  private val editor: BookEditor,
  private val notes: NotesExport,
  private val nav: AppNavigator,
  private val toasts: Toasts,
  private val persist: CoroutineScope,
) {
  private val _editOpen = MutableStateFlow(false)
  val editOpen: StateFlow<Boolean> = _editOpen

  fun openEdit(open: Boolean) { _editOpen.value = open }

  fun back() = nav.openLibrary()
  fun openBook(id: Long) = nav.openDetail(id)
  /** The library narrowed to this book's author, series or a tag. */
  fun showScope(scope: Scope) = nav.openLibraryScope(scope)
  /** Opens the book; [restart] ignores the saved position (the "Read again" button). */
  fun read(restart: Boolean) = nav.openReader(ReaderRequest(bookId, restart = restart))

  fun setFinished(finished: Boolean) = persist.launch {
    editor.setFinished(bookId, finished)
    toasts.show(if (finished) "Marked as finished" else "Marked as unread")
  }
  fun setRating(rating: Int?) = persist.launch { editor.setUserRating(bookId, rating) }
  fun addTag(tag: String) = persist.launch { editor.addTag(bookId, tag) }
  fun removeTag(tag: String) = persist.launch { editor.removeTag(bookId, tag) }

  fun exportNotes(uri: Uri) = notes.export(bookId, uri)

  /** The user's edits to this book (null fields are the file's own values). */
  val edits: Flow<BookEditEntity?> = editor.edits(bookId)

  /** Saves what the user typed for [field]; an invalid value is explained and nothing changes. */
  fun setEdit(field: EditField, value: String?) = persist.launch {
    try {
      editor.setEdit(bookId, field, value)
    } catch (e: EditRejected) {
      toasts.show(e.message ?: "That can't be saved")
    }
  }

  fun resetEdit(field: EditField) = setEdit(field, null)

  fun setCover(image: Uri?) = persist.launch {
    if (!editor.setCover(bookId, image)) toasts.show(if (image == null) "Couldn't restore the cover" else "That image can't be used as a cover")
  }

  fun revertAll() = persist.launch { editor.revertEdits(bookId); toasts.show("Back to the book's own details") }
}
