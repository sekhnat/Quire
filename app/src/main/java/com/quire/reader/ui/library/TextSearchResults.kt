package com.quire.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quire.reader.data.index.BookTextResult
import com.quire.reader.data.scan.StoragePaths
import com.quire.reader.theme.Nq
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.Ic
import com.quire.reader.ui.IndexStatusText
import com.quire.reader.ui.LibraryData
import com.quire.reader.ui.LibraryTextSearch
import com.quire.reader.ui.ListCover
import com.quire.reader.ui.Ph
import com.quire.reader.ui.ProgressLine
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText
import com.quire.reader.ui.QuireViewModel
import com.quire.reader.ui.SnippetStyle
import com.quire.reader.ui.Tag
import com.quire.reader.ui.TextSearchStatus
import com.quire.reader.ui.UiState
import com.quire.reader.ui.cardNote
import com.quire.reader.ui.hiddenSnippets
import com.quire.reader.ui.indexStatusText
import com.quire.reader.ui.passageLabel
import com.quire.reader.ui.resultNotices
import com.quire.reader.ui.shownSnippets
import com.quire.reader.ui.snippetText
import com.quire.reader.ui.textSearchStatusCopy

/**
 * The library's "Inside books" results: the filters, what the index covers, then either a message for the state the
 * search is in or the matching books with their excerpts. Author, series and tag navigation and the status chips work as
 * they do for titles and authors, and narrow these results.
 */
@Composable
internal fun TextSearchResults(s: UiState, lib: LibraryData, search: LibraryTextSearch, vm: QuireViewModel) {
  // Cards expanded to five excerpts; typing starts every card over.
  var expanded by remember(s.textLibraryQuery) { mutableStateOf(emptySet<Long>()) }
  val index = indexStatusText(search.coverage, search.activity)
  val status = search.status
  val copy = textSearchStatusCopy(status, search.coverage)
  val notices = when (status) {
    is TextSearchStatus.Results -> resultNotices(status.result)
    is TextSearchStatus.NoMatch -> resultNotices(status.result)
    else -> emptyList()
  }
  // Scrolling the results means the user is reading them, not typing: let go of the keyboard.
  val listState = rememberLazyListState()
  val focus = LocalFocusManager.current
  LaunchedEffect(listState) { snapshotFlow { listState.isScrollInProgress }.collect { if (it) focus.clearFocus() } }
  LazyColumn(
    Modifier.fillMaxSize().imePadding(), state = listState,
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp + navBottomPadding()),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item {
      if (s.scope != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          Tag(s.scope.label, { vm.setScope(null) }, accent = true, size = 12f, icon = Ic.X, hPad = 10.dp, vPad = 6.dp)
        }
      } else {
        FilterChips(s, lib, vm)
      }
    }
    item { IndexStatusBlock(index) }
    copy?.let { c ->
      item {
        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Ph(Ic.Search, 26.dp, Nq.neutral500)
          QText(c.title, 15f, weight = 500, align = TextAlign.Center)
          c.detail?.let { QText(it, 12.5f, color = Nq.neutral400, lh = 1.5f, align = TextAlign.Center) }
        }
      }
    }
    items(notices) { QText(it, 11.5f, color = Nq.neutral400, lh = 1.45f) }
    if (status is TextSearchStatus.Results) {
      items(status.result.books, key = { it.book.id }) { result ->
        BookCard(
          result, expanded = result.book.id in expanded,
          onToggle = { expanded = if (result.book.id in expanded) expanded - result.book.id else expanded + result.book.id },
          vm = vm, query = s.textLibraryQuery,
        )
      }
    }
  }
}

/** Why books may be missing, with the way out when there is one, and how many are searchable. */
@Composable
private fun IndexStatusBlock(index: IndexStatusText) {
  val context = LocalContext.current
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    index.headline?.let { headline ->
      val shape = RoundedCornerShape(10.dp)
      Column(Modifier.fillMaxWidth().clip(shape).background(Nq.surface).border(1.dp, Nq.neutral800, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QText(headline, 12.5f, color = Nq.neutral300, lh = 1.45f)
        index.progress?.let { ProgressLine(it) }
        if (index.needsAccess) {
          QButton("Allow access", { runCatching { context.startActivity(StoragePaths.allFilesAccessIntent(context)) } }, kind = BtnKind.Primary, icon = Ic.ArrowRight, size = 12.5f)
        }
      }
    }
    index.coverage?.let { QText(it, 11.5f, color = Nq.neutral500) }
  }
}

@Composable
private fun BookCard(result: BookTextResult, expanded: Boolean, onToggle: () -> Unit, vm: QuireViewModel, query: String) {
  val book = result.book
  val shape = RoundedCornerShape(12.dp)
  val shown = shownSnippets(result.snippets, expanded)
  val hidden = hiddenSnippets(result.snippets, expanded)
  Column(Modifier.fillMaxWidth().clip(shape).background(Nq.surface).border(1.dp, Nq.neutral800, shape)) {
    Row(Modifier.fillMaxWidth().clickable { vm.openBook(book.id) }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      ListCover(book)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        QText(book.title, 14f, weight = 500, maxLines = 2)
        QText(book.author, 12f, color = Nq.neutral500, maxLines = 1)
      }
      QText(passageLabel(result.passages), 11.5f, color = Nq.accent300, tabular = true, maxLines = 1)
    }
    cardNote(result.gap)?.let { note ->
      QText(note, 11.5f, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), color = Nq.neutral500)
    }
    shown.forEach { snippet ->
      Column(
        Modifier.fillMaxWidth().clickable { vm.openTextHit(snippet.target) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        QText(snippet.chapter.ifEmpty { "—" }, 11f, color = Nq.neutral500, maxLines = 1)
        Text(snippetText(snippet.spans), style = SnippetStyle)
      }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      if (hidden > 0) QButton("Show $hidden more", onToggle, kind = BtnKind.Ghost, size = 12f, height = 32.dp)
      else if (expanded) QButton("Show fewer", onToggle, kind = BtnKind.Ghost, size = 12f, height = 32.dp)
      else Spacer(Modifier)
      QButton("Show all in this book", { vm.openBookSearch(book.id, query) }, kind = BtnKind.Ghost, size = 12f, height = 32.dp)
    }
  }
}
