package com.quire.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quire.reader.data.Book
import com.quire.reader.data.BookStatus
import com.quire.reader.theme.Nq
import com.quire.reader.ui.Ic
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.LibFilter
import com.quire.reader.ui.LibraryData
import com.quire.reader.ui.MissingCover
import com.quire.reader.ui.Ph
import com.quire.reader.ui.QText
import com.quire.reader.ui.Scope
import com.quire.reader.ui.ScopeKind
import com.quire.reader.ui.SeriesCover
import com.quire.reader.ui.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── authors ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AuthorsView(library: LibraryState) {
  val derived = library.authors.collectAsStateWithLifecycle().value
  val groups = derived?.value.orEmpty()
  // Keyed by the snapshot, which compares by identity, rather than by the list, which would compare every author.
  val letterIndex = remember(derived) {
    var i = 0
    groups.associate { (letter, authors) -> letter to i.also { i += 1 + authors.size } }
  }
  val state = rememberLazyListState()
  val scope = rememberCoroutineScope()
  Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(start = 20.dp, end = 40.dp, top = 6.dp, bottom = 32.dp + navBottomPadding())) {
      groups.forEach { (letter, authors) ->
        stickyHeader(key = "letter-$letter") {
          QText(letter.toString(), 11f, Modifier.fillMaxWidth().background(Nq.bg).padding(top = 10.dp, bottom = 4.dp), color = Nq.accent, ls = 0.12f)
        }
        items(authors.size, key = { authors[it].name }) { i ->
          val a = authors[i]
          Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { library.setScope(Scope(ScopeKind.Author, a.name)) }.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              QText(displayName(a.name), 14f, maxLines = 1)
              QText("${a.books.size} " + if (a.books.size > 1) "books" else "book", 11.5f, color = Nq.neutral500)
            }
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
              a.books.take(3).forEach { b ->
                com.quire.reader.ui.CoverBox(b, Modifier.size(width = 22.dp, height = 33.dp).border(1.5.dp, Nq.bg, RoundedCornerShape(2.dp)), radius = 2.dp)
              }
            }
          }
        }
      }
    }
    // A–Z rail
    val letters = ('A'..'Z').toList() + '#'
    Column(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 16.dp, bottom = 16.dp + navBottomPadding()).width(22.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
      letters.forEach { l ->
        val has = letterIndex.containsKey(l)
        QText(l.toString(), 9.5f, Modifier.clickable(enabled = has) { scope.launch { state.animateScrollToItem(letterIndex.getValue(l)) } }, color = if (has) Nq.accent300 else Nq.neutral700)
      }
    }
  }
}

/** "Arthur Conan Doyle" → "Doyle, Arthur Conan". */
private fun displayName(name: String): String {
  val parts = name.trim().split(Regex("\\s+"))
  return if (parts.size < 2) name else parts.last() + ", " + parts.dropLast(1).joinToString(" ")
}

// ── series ──────────────────────────────────────────────────────────────────

@Composable
internal fun SeriesView(library: LibraryState) {
  // Null until worked out, which shows nothing rather than the empty message.
  val derived = library.series.collectAsStateWithLifecycle().value
  val series = derived?.value.orEmpty()
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp + navBottomPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (derived != null && series.isEmpty()) item { Empty("No series found. Series come from your Calibre metadata.") }
    items(series.size, key = { series[it].name }) { i ->
      val entry = series[i]
      Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nq.surface).clickable { library.setScope(Scope(ScopeKind.Series, entry.name)) }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            QText(entry.name, 15f, weight = 500, maxLines = 1)
            QText(entry.author, 12f, color = Nq.neutral500, maxLines = 1)
          }
          QText("${entry.finished} of ${entry.total} read", 11.5f, color = Nq.neutral400)
        }
        // A short strip: owned books, then the first few gaps in the numbering.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          entry.books.take(5).forEach { SeriesCover(it, dim = it.status == BookStatus.Finished) }
          entry.missing.take((5 - entry.books.size).coerceAtLeast(0)).forEach { MissingCover(it) }
        }
      }
    }
  }
}

// ── tags ────────────────────────────────────────────────────────────────────

private class Smart(val label: String, val rule: String, val icon: Int, val count: String, val onPick: () -> Unit)

/**
 * Smart collections, then every tag as a chip cloud. The cloud is packed into lines off the main thread and each
 * line is its own lazy item, so only the chips on screen are composed however many tags the library has.
 */
@Composable
internal fun TagsView(lib: LibraryData, library: LibraryState) {
  val counts = lib.counts
  val smart = listOf(
    Smart("In progress", "Opened, not finished", Ic.BookOpen, fmt(counts.getValue(LibFilter.Reading))) { library.showFilter(LibFilter.Reading) },
    Smart("Recently added", "Added in the last 30 days", Ic.Sparkle, fmt(counts.getValue(LibFilter.Recent))) { library.showFilter(LibFilter.Recent) },
    Smart("Unread", "Never opened", Ic.CircleDashed, fmt(counts.getValue(LibFilter.Unread))) { library.showFilter(LibFilter.Unread) },
    Smart("Finished", "Marked as read", Ic.Star, fmt(counts.getValue(LibFilter.Finished))) { library.showFilter(LibFilter.Finished) },
  )
  val metrics = rememberTagChipMetrics()
  val density = LocalDensity.current
  BoxWithConstraints(Modifier.fillMaxSize()) {
    // The list's 20dp content padding, rounded per side as LazyColumn does.
    val available = constraints.maxWidth - 2 * with(density) { 20.dp.roundToPx() }
    // Keeps the previous lines while a library change is repacked, so the cloud doesn't blink.
    val lines by produceState<List<TagLine>?>(null, lib, available, metrics) {
      value = withContext(Dispatchers.Default) { tagLines(lib.tags, available, metrics) }
    }
    TagsList(lines, lib.loaded, smart, library)
  }
}

@Composable
private fun TagsList(lines: List<TagLine>?, loaded: Boolean, smart: List<Smart>, library: LibraryState) {
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp + navBottomPadding()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item(contentType = "smart") {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker("Smart collections")
        smart.forEach { sm ->
          Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Nq.surface).clickable(onClick = sm.onPick).padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
          ) {
            Ph(sm.icon, 18.dp, Nq.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
              QText(sm.label, 14f)
              QText(sm.rule, 11f, color = Nq.neutral500)
            }
            QText(sm.count, 12f, color = Nq.neutral400, tabular = true)
          }
        }
      }
    }
    item(contentType = "kicker") { Kicker("Tags · from your books", Modifier.padding(top = 12.dp)) }
    items(lines.orEmpty(), key = { it.tags.first().first }, contentType = { "tagLine" }) { line ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        line.tags.forEach { (name, n) ->
          Tag(name, { library.setScope(Scope(ScopeKind.Tag, name)) }, size = 13f, count = n.toString(), hPad = 12.dp, vPad = 7.dp)
        }
      }
    }
    if (lines?.isEmpty() == true && loaded) item { QText("No tags yet. Tags come from Calibre, or add your own from a book's page.", 12f, color = Nq.neutral500) }
  }
}

@Composable
private fun Empty(text: String) {
  Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Ph(Ic.Books, 28.dp, Nq.neutral500)
    QText(text, 13f, color = Nq.neutral500, align = androidx.compose.ui.text.style.TextAlign.Center)
  }
}
