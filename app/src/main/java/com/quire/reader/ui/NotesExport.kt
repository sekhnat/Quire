package com.quire.reader.ui

import android.net.Uri
import com.quire.reader.QuireApplication
import com.quire.reader.data.backup.NotesExporter
import com.quire.reader.data.backup.identityKeyFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** Writes a book's highlights to a document the user picked (a book's page and Settings both offer it). */
fun interface NotesExport {
  fun export(bookId: Long, uri: Uri)
}

/** Exports a book's highlights as Markdown; missing books export what they kept. Runs on [scope], so leaving the screen doesn't stop it. */
class MarkdownNotesExport(private val app: QuireApplication, private val toasts: Toasts, private val scope: CoroutineScope) : NotesExport {
  override fun export(bookId: Long, uri: Uri) {
    scope.launch(Dispatchers.IO) {
      val repo = app.library
      val book = repo.book(bookId)
      if (book == null) { toasts.show("That book is no longer in the library"); return@launch }
      val highlights = repo.highlights(bookId).first()
      if (highlights.isEmpty()) { toasts.show("No highlights to export"); return@launch }
      val chapterTitles = if (book.missingSince == null && File(book.path).isFile) {
        NotesExporter.chapterTitles(app.publicationLoader, book.path)
      } else emptyMap()
      val markdown = NotesExporter.markdown(book.title, book.author, identityKeyFor(book), highlights, chapterTitles)
      val ok = runCatching {
        app.contentResolver.openOutputStream(uri)?.use { it.write(markdown.toByteArray(Charsets.UTF_8)) } != null
      }.getOrDefault(false)
      toasts.show(if (ok) "Notes exported" else "Couldn't write the file")
    }
  }
}
