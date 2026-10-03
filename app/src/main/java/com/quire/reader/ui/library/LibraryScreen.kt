package com.quire.reader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quire.reader.data.Book
import com.quire.reader.data.BookStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quire.reader.data.scan.StoragePaths
import com.quire.reader.ui.LibraryData
import com.quire.reader.ui.ShelfDef
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.quire.reader.theme.Nq
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.FolderPickerSheet
import com.quire.reader.ui.GridCover
import com.quire.reader.ui.HeroCover
import com.quire.reader.ui.Ic
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.LibFilter
import com.quire.reader.ui.LibLayout
import com.quire.reader.ui.LibView
import com.quire.reader.ui.ListCover
import com.quire.reader.ui.Ph
import com.quire.reader.ui.ProgressLine
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText
import com.quire.reader.ui.QTextField
import com.quire.reader.ui.QuireViewModel
import com.quire.reader.ui.SearchScope
import com.quire.reader.ui.Segmented
import com.quire.reader.ui.SegOption
import com.quire.reader.ui.SheetHost
import com.quire.reader.ui.ShelfCover
import com.quire.reader.ui.SortKey
import com.quire.reader.ui.TabRow2
import com.quire.reader.ui.Tag
import com.quire.reader.ui.UiState
import com.quire.reader.ui.bleed
import com.quire.reader.ui.cardStatus
import com.quire.reader.ui.cssGradient
import com.quire.reader.ui.visibleBooks
import java.text.NumberFormat
import java.util.Locale

internal fun syncedLabel(at: Long): String {
  if (at <= 0) return "not scanned yet"
  val minutes = (System.currentTimeMillis() - at) / 60_000
  return when {
    minutes < 1 -> "synced just now"
    minutes < 60 -> "synced ${minutes}m ago"
    minutes < 24 * 60 -> "synced ${minutes / 60}h ago"
    else -> "synced ${minutes / (24 * 60)}d ago"
  }
}

internal fun fmt(n: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

@Composable
internal fun navBottomPadding() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
fun LibraryScreen(s: UiState, lib: LibraryData, vm: QuireViewModel) {
  var pickingFolder by remember { mutableStateOf(false) }
  val textMode = s.searchOpen && s.searchScope == SearchScope.Text
  BackHandler(enabled = s.scope != null) { vm.setScope(null) }
  Box(Modifier.fillMaxSize().background(Nq.bg)) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
      Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LibraryHeader(s, lib, vm)
        AnimatedVisibility(s.searchOpen) {
          val focus = remember { FocusRequester() }
          LaunchedEffect(Unit) { focus.requestFocus() }
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.searchScope == SearchScope.Text) {
              QTextField(s.textLibraryQuery, vm::setTextLibraryQuery, "Words or a “phrase” inside books", leadingIcon = Ic.Search, onClear = { vm.setTextLibraryQuery("") }, focusRequester = focus)
            } else {
              QTextField(s.query, vm::setQuery, "Title, author, series, tag", leadingIcon = Ic.Search, focusRequester = focus)
            }
            Segmented(
              SearchScope.entries.map { SegOption(it.label, s.searchScope == it, { vm.setSearchScope(it) }) },
              height = 32.dp, minWidth = 0.dp, size = 12f,
            )
          }
        }
        TabRow2(LibView.entries.map { it.label }, s.view.ordinal, { vm.setView(LibView.entries[it]) })
      }
      Box(Modifier.weight(1f)) {
        when (s.view) {
          LibView.Books -> if (textMode) {
            // Only observed while the text search is on screen, so the library does no search work otherwise.
            val search by vm.textSearch.collectAsStateWithLifecycle()
            TextSearchResults(s, lib, search, vm)
          } else BooksView(s, lib, vm)
          LibView.Authors -> AuthorsView(lib, vm)
          LibView.Series -> SeriesView(lib, vm)
          LibView.Tags -> TagsView(lib, vm)
        }
      }
    }
    ImportSheet(s, lib, vm, onAddFolder = { pickingFolder = true })
    SortSheet(s, vm)
    FolderPickerSheet(pickingFolder, { pickingFolder = false }, vm::addFolder)
  }
}

@Composable
private fun LibraryHeader(s: UiState, lib: LibraryData, vm: QuireViewModel) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Column {
      QText("Library", 26f, weight = 500, ls = -0.01f)
      val folders = lib.folders.size
      QText("${fmt(lib.books.size)} ${if (lib.books.size == 1) "book" else "books"} · $folders watched ${if (folders == 1) "folder" else "folders"}", 11.5f, color = Nq.neutral500)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      IconBtn(Ic.Search, vm::toggleSearch, tint = if (s.searchOpen) Nq.accent else Nq.neutral300)
      IconBtn(if (s.sortAscending) Ic.SortAsc else Ic.SortDesc, { vm.openSort(true) }, tint = if (s.sort != SortKey.Opened) Nq.accent else Nq.neutral300)
      IconBtn(
        when (s.layout) { LibLayout.Grid -> Ic.Grid; LibLayout.List -> Ic.ListDashes; LibLayout.Shelves -> Ic.Rows },
        vm::cycleLayout, tint = Nq.neutral300,
      )
      IconBtn(Ic.Plus, { vm.openImport(true) }, tint = Nq.accent)
      IconBtn(Ic.Gear, vm::openSettings, tint = Nq.neutral300)
    }
  }
}

// ── books ───────────────────────────────────────────────────────────────────

@Composable
private fun BooksView(s: UiState, lib: LibraryData, vm: QuireViewModel) {
  val list = visibleBooks(s, lib.books)
  val filtered = s.scope != null || s.query.isNotBlank() || s.filter != LibFilter.All
  val layout = if (s.layout == LibLayout.Shelves && filtered) LibLayout.Grid else s.layout
  val bottom = 32.dp + navBottomPadding()
  val hPad = 20.dp
  val shelves = lib.shelves
  when (layout) {
    LibLayout.Grid -> LazyVerticalGrid(
      GridCells.Fixed(3), Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = hPad, end = hPad, top = 14.dp, bottom = bottom),
      horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      item(span = { GridItemSpan(maxLineSpan) }) { BooksHeader(s, lib, vm, list, layout) }
      items(list, key = { it.id }) { b -> GridCard(b, s.sort) { vm.openBook(b.id) } }
    }
    LibLayout.List -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = hPad, end = hPad, top = 14.dp, bottom = bottom)) {
      item { Box(Modifier.padding(bottom = 16.dp)) { BooksHeader(s, lib, vm, list, layout) } }
      items(list, key = { it.id }) { b -> ListRow(b, s.sort) { vm.openBook(b.id) } }
    }
    LibLayout.Shelves -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = hPad, end = hPad, top = 14.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(22.dp)) {
      item { BooksHeader(s, lib, vm, list, layout) }
      items(shelves, key = { it.title }) { shelf -> ShelfRow(shelf, s.sort, vm) }
    }
  }
}

@Composable
private fun BooksHeader(s: UiState, lib: LibraryData, vm: QuireViewModel, list: List<Book>, layout: LibLayout) {
  val showHero = s.scope == null && s.filter == LibFilter.All && s.query.isBlank() && layout != LibLayout.Shelves
  val showSortRow = layout != LibLayout.Shelves
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    if (s.scope != null) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Tag(s.scope.label, { vm.setScope(null) }, accent = true, size = 12f, icon = Ic.X, hPad = 10.dp, vPad = 6.dp)
        QText(list.size.toString() + if (list.size == 1) " book" else " books", 11.5f, color = Nq.neutral500)
      }
    } else {
      FilterChips(s, lib, vm)
    }
    if (showSortRow) SortRow(s, vm)
    val resume = lib.resume
    if (showHero && resume != null) Hero(resume, vm)
    if (lib.loaded && list.isEmpty() && layout != LibLayout.Shelves) {
      Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Ph(Ic.Books, 28.dp, Nq.neutral500)
        if (lib.books.isEmpty()) {
          QText("No books yet. Add a folder that has EPUB files in it.", 13f, color = Nq.neutral500, align = androidx.compose.ui.text.style.TextAlign.Center)
          QButton("Add books", { vm.openImport(true) }, kind = BtnKind.Primary, icon = Ic.Plus, size = 13f)
        } else QText("Nothing matches that filter.", 13f, color = Nq.neutral500)
      }
    }
  }
}

@Composable
internal fun FilterChips(s: UiState, lib: LibraryData, vm: QuireViewModel) {
  LazyRow(Modifier.bleed(20.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    items(LibFilter.entries) { f ->
      val on = s.filter == f
      val shape = RoundedCornerShape(999.dp)
      Row(
        Modifier.clip(shape).background(if (on) Nq.accentA(0.12f) else Color.Transparent).border(1.dp, if (on) Nq.accent else Nq.neutral800, shape).clickable { vm.setFilter(f) }.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
      ) {
        QText(f.label, 12.5f, color = if (on) Nq.accent200 else Nq.neutral300, maxLines = 1)
        QText(fmt(lib.counts.getValue(f)), 11f, color = Nq.neutral500, tabular = true)
      }
    }
  }
}

@Composable
private fun SortRow(s: UiState, vm: QuireViewModel) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.clickable { vm.openSort(true) }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Ph(Ic.ArrowsDownUp, 14.dp, Nq.accent)
      QText(s.sort.label, 12f, color = Nq.neutral400)
      Ph(Ic.CaretDown, 11.dp, Nq.neutral400)
    }
    Row(Modifier.clickable { vm.flipSort() }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      QText(if (s.sortAscending) s.sort.asc else s.sort.desc, 12f, color = Nq.neutral500)
      Ph(if (s.sortAscending) Ic.ArrowUp else Ic.ArrowDown, 13.dp, Nq.neutral500)
    }
  }
}

@Composable
private fun Hero(book: Book, vm: QuireViewModel) {
  val shape = RoundedCornerShape(14.dp)
  Row(
    Modifier.fillMaxWidth().clip(shape).background(cssGradient(135f, Nq.surface, Nq.neutral900)).border(1.dp, Nq.neutral800, shape).clickable { vm.read(book.id) }.padding(12.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    HeroCover(book)
    Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Kicker("Continue reading", color = Nq.accent)
      QText(book.title, 16f, weight = 500, lh = 1.2f, maxLines = 2)
      QText(book.author, 12f, color = Nq.neutral500, maxLines = 1)
      ProgressLine(book.progress, Modifier.padding(top = 8.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        QText(book.timeLeftLabel(), 11f, color = Nq.neutral500)
        QText("${book.pct}%", 11f, color = Nq.neutral500)
      }
    }
  }
}

@Composable
private fun GridCard(b: Book, sort: SortKey, onClick: () -> Unit) {
  Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
    GridCover(b)
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
      QText(b.title, 12f, weight = 500, maxLines = 1)
      QText(cardStatus(b, sort), 11f, color = Nq.neutral500, maxLines = 1)
    }
  }
}

@Composable
private fun ListRow(b: Book, sort: SortKey, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    ListCover(b)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      QText(b.title, 14f, weight = 500, maxLines = 1)
      QText(if (b.series != null) "${b.author} · ${b.series}${b.seriesNoLabel?.let { " $it" } ?: ""}" else b.author, 12f, color = Nq.neutral500, maxLines = 1)
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
      QText(cardStatus(b, sort), 11f, color = if (b.status == BookStatus.Reading) Nq.accent300 else Nq.neutral500, tabular = true)
      ProgressLine(b.progress, Modifier.width(40.dp))
    }
  }
}

@Composable
private fun ShelfRow(shelf: ShelfDef, sort: SortKey, vm: QuireViewModel) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
      QText(shelf.title, 15f, weight = 500)
      QButton(shelf.sub, { vm.showShelf(shelf.filter, shelf.scope) }, kind = BtnKind.Ghost, size = 12f, height = 28.dp)
    }
    LazyRow(Modifier.bleed(20.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      items(shelf.books, key = { it.id }) { b ->
        Column(Modifier.width(shelf.coverWidthDp.dp).clickable { vm.openBook(b.id) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
          ShelfCover(b)
          QText(cardStatus(b, sort), 11f, color = Nq.neutral500, maxLines = 1)
        }
      }
    }
  }
}

// ── sheets ──────────────────────────────────────────────────────────────────

@Composable
private fun ImportSheet(s: UiState, lib: LibraryData, vm: QuireViewModel, onAddFolder: () -> Unit) {
  val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importFiles(uris) }
  SheetHost(s.importOpen, { vm.openImport(false) }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 14.dp)) {
      QText("Add books", 17f, weight = 500)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Kicker("Watched folders")
        if (lib.folders.isEmpty()) QText("No folders yet.", 12f, Modifier.padding(vertical = 8.dp), color = Nq.neutral500)
        lib.folders.forEach { f ->
          val count = lib.books.count { it.folderId == f.id }
          Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Ph(Ic.FolderSimple, 18.dp, Nq.neutral400)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
              QText(StoragePaths.displayName(f.path), 13.5f, maxLines = 1)
              QText("${fmt(count)} ${if (count == 1) "book" else "books"} · ${syncedLabel(f.lastScanAt)}", 11f, color = Nq.neutral500, maxLines = 1)
            }
            IconBtn(Ic.X, { vm.removeFolder(f.id) }, tint = Nq.neutral500, size = 32.dp, iconSize = 16.dp)
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QButton("Rescan", vm::rescan, Modifier.weight(1f), icon = Ic.Refresh, size = 12.5f)
        QButton("Add folder", onAddFolder, Modifier.weight(1f), icon = Ic.FolderPlus, size = 12.5f)
      }
      QButton("Import EPUB files", { filePicker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, Modifier.fillMaxWidth(), BtnKind.Primary, icon = Ic.FileDown, size = 13f, height = 42.dp)
    }
  }
}

@Composable
private fun SortSheet(s: UiState, vm: QuireViewModel) {
  SheetHost(s.sortOpen, { vm.openSort(false) }, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 30.dp)) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        QText("Sort by", 17f, weight = 500)
        Segmented(
          listOf(
            SegOption(s.sort.desc, !s.sortAscending, { vm.setSortAscending(false) }, Ic.ArrowDown),
            SegOption(s.sort.asc, s.sortAscending, { vm.setSortAscending(true) }, Ic.ArrowUp),
          ),
          height = 32.dp, minWidth = 0.dp, size = 12f,
        )
      }
      SortKey.entries.forEach { k ->
        val on = s.sort == k
        Row(
          Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Nq.accentA(0.10f) else Color.Transparent).clickable { vm.pickSort(k) }.padding(horizontal = 10.dp, vertical = 11.dp),
          horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
          Ph(k.icon, 19.dp, if (on) Nq.accent else Nq.neutral400)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            QText(k.label, 14f, color = if (on) Nq.accent200 else Nq.text)
            QText(k.desc + " / " + k.asc.lowercase(), 11f, color = Nq.neutral500)
          }
          if (on) Ph(Ic.CheckBold, 16.dp, Nq.accent)
        }
      }
    }
  }
}
