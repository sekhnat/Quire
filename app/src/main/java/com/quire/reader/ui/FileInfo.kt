package com.quire.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.quire.reader.data.Book
import com.quire.reader.data.fileSizeLabel
import com.quire.reader.data.scan.BookFormats
import com.quire.reader.data.scan.StoragePaths
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts

/** One line of a book's file facts; [mono] lines (paths) are set in the monospace face. */
data class FileFact(val label: String, val value: String, val mono: Boolean = false)

/** Where a book's file is, as shown: shared storage's own prefix is left off. */
fun shownPath(path: String): String = path.removePrefix(StoragePaths.PRIMARY_ROOT)

/** A book's file facts, as its page and the library's file info show them. */
fun fileFacts(book: Book, showWords: Boolean): List<FileFact> = listOfNotNull(
  FileFact("Format", "${BookFormats.label(book.path)} · ${book.sizeLabel}"),
  book.wordsLong?.takeIf { showWords }?.let { FileFact("Length", it) },
  FileFact("Language", book.languageLabel),
  FileFact("Added", book.addedLabel),
  FileFact("Source", if (book.fromCalibre) "Calibre · metadata.opf" else "Folder scan"),
  FileFact("Location", shownPath(book.path), mono = true),
)

/** Several books' files in one line: "4 books · 12.3 MB · 3 EPUB, 1 AZW3". */
fun filesSummary(books: List<Book>): String {
  val formats = books.groupingBy { BookFormats.label(it.path) }.eachCount().entries
    .sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" }
  return listOf(books(books.size), fileSizeLabel(books.sumOf { it.sizeBytes }), formats).joinToString(" · ")
}

/** [facts] as label / value rows over hairlines. */
@Composable
fun FileFactRows(facts: List<FileFact>, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    facts.forEach { (k, v, mono) ->
      Row(
        Modifier.fillMaxWidth().drawBehind { drawRect(Nq.neutral900, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }.padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        QText(k, 12.5f, Modifier.width(72.dp), color = Nq.neutral500)
        QText(v, if (mono) 11f else 12.5f, Modifier.weight(1f), color = Nq.neutral200, family = if (mono) QuireFonts.Mono else QuireFonts.Inter)
      }
    }
  }
}
