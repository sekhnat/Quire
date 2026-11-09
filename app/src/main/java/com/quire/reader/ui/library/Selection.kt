package com.quire.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quire.reader.data.Book
import com.quire.reader.data.scan.BookFormats
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.BulkSheet
import com.quire.reader.ui.FileFactRows
import com.quire.reader.ui.FolderPickerSheet
import com.quire.reader.ui.Ic
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.LibraryData
import com.quire.reader.ui.LibraryUiState
import com.quire.reader.ui.Ph
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText
import com.quire.reader.ui.QTextField
import com.quire.reader.ui.SheetHost
import com.quire.reader.ui.Tag
import com.quire.reader.ui.books
import com.quire.reader.ui.fileFacts
import com.quire.reader.ui.filesSummary
import com.quire.reader.ui.shownPath

/**
 * What a tap and a long press on a book do. Outside selection a tap opens the book and a long press starts selecting
 * with it; while selecting, both pick or unpick it.
 */
internal class BookTaps(val selected: Set<Long>, private val library: LibraryState, private val buzz: () -> Unit) {
  val selecting: Boolean get() = selected.isNotEmpty()
  fun isSelected(b: Book) = b.id in selected
  fun click(b: Book) = if (selecting) library.toggleSelected(b.id) else library.openBook(b.id)
  fun longPress(b: Book) {
    buzz()
    if (selecting) library.toggleSelected(b.id) else library.startSelection(b.id)
  }
}

@Composable
internal fun rememberBookTaps(selected: Set<Long>, library: LibraryState): BookTaps {
  val haptics = LocalHapticFeedback.current
  return remember(selected, library, haptics) { BookTaps(selected, library) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) } }
}

/** A book's tap and long press, as [taps] decides. */
internal fun Modifier.bookTaps(b: Book, taps: BookTaps): Modifier = combinedClickable(onClick = { taps.click(b) }, onLongClick = { taps.longPress(b) })

/** A selected book's cover ring, so a picked cover stands out in the grid and on shelves. */
internal fun Modifier.selectedRing(on: Boolean, radius: Dp = 4.dp): Modifier =
  if (on) border(2.dp, Nq.accent, RoundedCornerShape(radius)) else this

/** The round check on a book while selecting: filled when picked, an empty ring when not. */
@Composable
internal fun SelectMark(on: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp) {
  Box(modifier.size(size).clip(CircleShape).background(if (on) Nq.bg else Nq.scrim.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
    Ph(if (on) Ic.CheckCircleFill else Ic.Circle, size, if (on) Nq.accent else Nq.neutral200)
  }
}

/** Takes the library header's place while selecting: how many are picked, and picking all or none of those shown. */
@Composable
internal fun SelectionHeader(s: LibraryUiState, lib: LibraryData, library: LibraryState) {
  val visible by library.visible.collectAsStateWithLifecycle()
  val allShown = visible.books.isNotEmpty() && visible.books.all { it.id in s.selected }
  val picked = remember(s.selected, lib) { s.selected.mapNotNull { lib.byId[it] } }
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    IconBtn(Ic.X, library::clearSelection, Modifier.padding(end = 6.dp), tint = Nq.neutral300)
    Column(Modifier.weight(1f).padding(end = 8.dp)) {
      QText("${s.selected.size} selected", 22f, weight = 500, ls = -0.01f, maxLines = 1)
      if (picked.isNotEmpty()) QText(filesSummary(picked), 11.5f, color = Nq.neutral500, maxLines = 1)
    }
    QButton(if (allShown) "Select none" else "Select all", library::toggleSelectAll, kind = BtnKind.Ghost, size = 12.5f, height = 32.dp)
  }
}

private class BarAction(val label: String, val icon: Int, val onClick: () -> Unit, val color: Color? = null)

/** The actions for the selected books, along the bottom of the library. */
@Composable
internal fun SelectionBar(library: LibraryState, modifier: Modifier = Modifier) {
  val actions = listOf(
    BarAction("Send", Ic.ShareNetwork, library::sendSelected),
    BarAction("Tag", Ic.Tag, { library.openBulk(BulkSheet.Tag) }),
    BarAction("Copy", Ic.Copy, { library.openBulk(BulkSheet.Copy) }),
    BarAction("Info", Ic.Info, { library.openBulk(BulkSheet.Info) }),
    BarAction("Remove", Ic.MinusCircle, { library.openBulk(BulkSheet.ConfirmRemove) }),
    BarAction("Delete", Ic.Trash, { library.openBulk(BulkSheet.ConfirmDelete) }, Nq.danger),
  )
  val shape = RoundedCornerShape(16.dp)
  Row(
    modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp + navBottomPadding())
      .clip(shape).background(Nq.surface).border(1.dp, Nq.neutral800, shape).padding(vertical = 6.dp, horizontal = 4.dp),
  ) {
    actions.forEach { a ->
      val tint = a.color ?: Nq.neutral200
      Column(
        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = a.onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Ph(a.icon, 21.dp, tint)
        QText(a.label, 10.5f, color = tint, maxLines = 1)
      }
    }
  }
}

/** Extra room under a book list while the selection bar covers its end. */
internal val SelectionBarSpace = 76.dp

/** The sheets the selection bar opens. */
@Composable
internal fun SelectionSheets(s: LibraryUiState, lib: LibraryData, library: LibraryState) {
  // The books stay on the sheet while it slides away, after an action has already ended selection mode.
  var shown by remember { mutableStateOf(emptyList<Book>()) }
  val picked = remember(s.selected, lib) { lib.all.filter { it.id in s.selected } }
  if (s.bulkSheet != null) shown = picked
  val close = { library.openBulk(null) }
  TagSheet(s.bulkSheet == BulkSheet.Tag, shown, lib, library, close)
  InfoSheet(s.bulkSheet == BulkSheet.Info, shown, lib.showWords, close)
  ConfirmSheet(
    s.bulkSheet == BulkSheet.ConfirmRemove,
    if (shown.size == 1) "Remove “${shown.single().title}” from the library?" else "Remove ${books(shown.size)} from the library?",
    "The ${if (shown.size == 1) "file stays" else "files stay"} on your device, and scans leave ${if (shown.size == 1) "it" else "them"} out. " +
      "Bring ${if (shown.size == 1) "it" else "them"} back from Settings, under Removed books. Reading history is kept.",
    "Remove", Nq.text, close, library::removeSelected,
  )
  ConfirmSheet(
    s.bulkSheet == BulkSheet.ConfirmDelete,
    if (shown.size == 1) "Delete “${shown.single().title}” from your device?" else "Delete ${books(shown.size)} from your device?",
    "The ${if (shown.size == 1) "book file is" else "book files are"} deleted for good, with ${if (shown.size == 1) "its" else "their"} reading position, bookmarks, highlights and notes. " +
      "Calibre's metadata and cover files are left in place. This can't be undone.",
    "Delete", Nq.danger, close, library::deleteSelected,
  )
  FolderPickerSheet(s.bulkSheet == BulkSheet.Copy, close, library::copySelected)
}

/** Adds one tag to every selected book: typed, or picked from the tags the library already has. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSheet(open: Boolean, books: List<Book>, lib: LibraryData, library: LibraryState, onDismiss: () -> Unit) {
  var newTag by remember(open) { mutableStateOf("") }
  // The library's most used tags; those every selected book already has are left out.
  val suggestions = remember(lib, books) {
    lib.tags.sortedByDescending { it.second }.map { it.first }
      .filter { t -> books.any { t !in it.tags } }.take(SUGGESTED_TAGS)
  }
  SheetHost(open, onDismiss, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      QText(if (books.size == 1) "Tag “${books.single().title}”" else "Tag ${books(books.size)}", 17f, weight = 500, lh = 1.3f, maxLines = 2)
      val submit = { if (newTag.isNotBlank()) library.tagSelected(newTag) }
      QTextField(newTag, { newTag = it }, "Add a tag", leadingIcon = Ic.Plus, onSubmit = submit)
      if (suggestions.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Kicker("Your library's tags")
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { t -> Tag(t, { library.tagSelected(t) }, size = 12f, vPad = 4.dp) }
          }
        }
      }
      QText("Tags you add stay in Quire and never change Calibre's files. Remove one from a book's page.", 11.5f, color = Nq.neutral500, lh = 1.4f)
      QButton("Add tag", submit, Modifier.fillMaxWidth(), BtnKind.Primary, icon = Ic.Tag, size = 13f, height = 42.dp, enabled = newTag.isNotBlank())
    }
  }
}

private const val SUGGESTED_TAGS = 16

/** The selected books' files: one book's facts as its page shows them, or a line for each of several. */
@Composable
private fun InfoSheet(open: Boolean, books: List<Book>, showWords: Boolean, onDismiss: () -> Unit) {
  SheetHost(open, onDismiss, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      QText(if (books.size == 1) books.single().title else "File info", 17f, weight = 500, lh = 1.3f, maxLines = 2)
      if (books.size == 1) {
        FileFactRows(fileFacts(books.single(), showWords))
      } else {
        QText(filesSummary(books), 12.5f, color = Nq.neutral400)
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
          items(books, key = { it.id }) { b ->
            Column(
              Modifier.fillMaxWidth().drawBehind { drawRect(Nq.neutral900, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }.padding(vertical = 9.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              QText(b.title, 13.5f, weight = 500, maxLines = 1)
              QText("${BookFormats.label(b.path)} · ${b.sizeLabel} · added ${b.addedLabel}", 11.5f, color = Nq.neutral400, maxLines = 1)
              QText(shownPath(b.path), 11f, color = Nq.neutral500, family = QuireFonts.Mono, maxLines = 2)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ConfirmSheet(open: Boolean, title: String, body: String, action: String, actionColor: Color, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  SheetHost(open, onDismiss, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      QText(title, 17f, weight = 500, lh = 1.3f, maxLines = 3)
      QText(body, 13f, color = Nq.neutral400, lh = 1.5f)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QButton("Cancel", onDismiss, Modifier.weight(1f), size = 13f, height = 42.dp)
        QButton(action, onConfirm, Modifier.weight(1f), size = 13f, height = 42.dp, color = actionColor)
      }
    }
  }
}
