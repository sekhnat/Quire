package com.quire.reader.ui.detail

import android.net.Uri
import com.quire.reader.ui.AppNavigator
import com.quire.reader.ui.NotesExport
import com.quire.reader.ui.ReaderRequest
import com.quire.reader.ui.Scope
import com.quire.reader.ui.Toasts
import com.quire.reader.data.EditField
import com.quire.reader.data.EditRejected
import com.quire.reader.data.PixelRect
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
  /** The bytes of an image picked with the photo picker; null when it cannot be read. */
  suspend fun readImage(image: Uri): ByteArray? = null
  /** The pictures inside the book, to choose a cover from. */
  suspend fun bookImages(bookId: Long): List<ByteArray> = emptyList()
  /** Gives the book [image] as its cover, the part [crop] marks; false when the image is unreadable. */
  suspend fun setCoverImage(bookId: Long, image: ByteArray, crop: PixelRect?): Boolean = false
  /** Goes back to the book's own cover. */
  suspend fun resetCover(bookId: Long) {}
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

  private val _cropping = MutableStateFlow<ByteArray?>(null)
  /** The image being framed on the crop screen, if it is open. */
  val cropping: StateFlow<ByteArray?> = _cropping

  private val _bookImages = MutableStateFlow<List<ByteArray>?>(null)
  /** The book's own pictures while the picker is open: null when closed, empty while (or after) finding none. */
  val bookImages: StateFlow<List<ByteArray>?> = _bookImages
  private val _bookImagesLoading = MutableStateFlow(false)
  val bookImagesLoading: StateFlow<Boolean> = _bookImagesLoading

  /** A photo was picked: frame it. */
  fun cropPicked(image: Uri) = persist.launch {
    val bytes = editor.readImage(image)
    if (bytes == null) toasts.show("That image can't be used as a cover") else _cropping.value = bytes
  }

  /** Shows the book's own pictures to choose from. */
  fun openBookImages() {
    _bookImages.value = emptyList()
    _bookImagesLoading.value = true
    persist.launch {
      val images = editor.bookImages(bookId)
      _bookImagesLoading.value = false
      if (_bookImages.value != null) _bookImages.value = images
    }
  }

  fun closeBookImages() { _bookImages.value = null; _bookImagesLoading.value = false }

  /** One of the book's pictures was chosen: frame it. */
  fun cropBookImage(bytes: ByteArray) { closeBookImages(); _cropping.value = bytes }

  fun cancelCrop() { _cropping.value = null }

  /** Saves the framed part of the image as the cover. */
  fun saveCrop(crop: PixelRect?) {
    val bytes = _cropping.value ?: return
    _cropping.value = null
    persist.launch { if (!editor.setCoverImage(bookId, bytes, crop)) toasts.show("That image can't be used as a cover") }
  }

  fun resetCover() = persist.launch { editor.resetCover(bookId) }

  fun revertAll() = persist.launch { editor.revertEdits(bookId); toasts.show("Back to the book's own details") }
}
