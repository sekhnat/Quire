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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
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
import com.quire.reader.ui.authorLine
import com.quire.reader.ui.scanStatus
import com.quire.reader.ui.synopsisPreview
import com.quire.reader.ui.LibraryUiState
import com.quire.reader.ui.SearchScope
import com.quire.reader.ui.Segmented
import com.quire.reader.ui.SegOption
import com.quire.reader.ui.SheetHost
import com.quire.reader.ui.ShelfCover
import com.quire.reader.ui.SortKey
import com.quire.reader.ui.TabRow2
import com.quire.reader.ui.Tag

import com.quire.reader.ui.bleed
import com.quire.reader.ui.cardStatus
import com.quire.reader.ui.cssGradient
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.isActive

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

/**
 * Keeps a sideways row's leftover drag and fling to itself. Without it a row scrolled to its end hands the rest of the
 * swipe to the library pager as a plain scroll, and the pager is left stopped between two views instead of snapping.
 */
private object KeepSidewaysScroll : NestedScrollConnection {
  override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available.copy(y = 0f)
  override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available.copy(y = 0f)
}

@Composable
fun LibraryScreen(s: LibraryUiState, lib: LibraryData, library: LibraryState) {
  var pickingFolder by remember { mutableStateOf(false) }
  val textMode = s.searchOpen && s.searchScope == SearchScope.Text
  BackHandler(enabled = s.scope != null) { library.setScope(null) }
  // The views sit side by side so a swipe moves between them. The pager follows the view model (tab taps, picking an
  // author…), and a swipe that lands tells it. `steering` keeps the in-between page of a scroll cut short by another
  // (two quick tab taps) from being reported as a swipe.
  val pager = rememberPagerState(initialPage = s.view.ordinal) { LibView.entries.size }
  var steering by remember { mutableStateOf(false) }
  LaunchedEffect(s.view) {
    val page = s.view.ordinal
    if (pager.currentPage == page && pager.currentPageOffsetFraction == 0f) { steering = false; return@LaunchedEffect }
    steering = true
    // Still active when a drag took the pager over, so that swipe is reported.
    try { pager.animateScrollToPage(page) } finally { if (isActive) steering = false }
  }
  LaunchedEffect(pager) {
    snapshotFlow { pager.settledPage }.collect { page ->
      if (!steering && page != library.state.value.view.ordinal) library.setView(LibView.entries[page])
    }
  }
  Box(Modifier.fillMaxSize().background(Nq.bg)) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
      Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LibraryHeader(s, lib, library)
        ScanStatusCard(library)
        AnimatedVisibility(s.searchOpen) {
          val focus = remember { FocusRequester() }
          LaunchedEffect(Unit) { focus.requestFocus() }
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.searchScope == SearchScope.Text) {
              QTextField(s.textLibraryQuery, library::setTextLibraryQuery, "Words or a “phrase” inside books", leadingIcon = Ic.Search, onClear = { library.setTextLibraryQuery("") }, focusRequester = focus)
            } else {
              QTextField(s.query, library::setQuery, "Title, author, series, tag", leadingIcon = Ic.Search, focusRequester = focus)
            }
            Segmented(
              SearchScope.entries.map { SegOption(it.label, s.searchScope == it, { library.setSearchScope(it) }) },
              height = 32.dp, minWidth = 0.dp, size = 12f,
            )
          }
        }
        TabRow2(LibView.entries.map { it.label }, pager.targetPage, { library.setView(LibView.entries[it]) })
      }
      HorizontalPager(pager, Modifier.weight(1f)) { page ->
        when (LibView.entries[page]) {
          LibView.Books -> if (textMode) {
            // Only observed while the text search is on screen, so the library does no search work otherwise.
            val search by library.textSearch.collectAsStateWithLifecycle()
            TextSearchResults(s, lib, search, library)
          } else BooksView(s, lib, library)
          LibView.Authors -> AuthorsView(library)
          LibView.Series -> SeriesView(library)
          LibView.Tags -> TagsView(lib, library)
        }
      }
    }
    ImportSheet(s, lib, library, onAddFolder = { pickingFolder = true })
    SortSheet(s, library)
    FolderPickerSheet(pickingFolder, { pickingFolder = false }, library::addFolder)
  }
}

@Composable
private fun LibraryHeader(s: LibraryUiState, lib: LibraryData, library: LibraryState) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Column {
      QText("Library", 26f, weight = 500, ls = -0.01f)
      val folders = lib.folders.size
      QText("${fmt(lib.books.size)} ${if (lib.books.size == 1) "book" else "books"} · $folders watched ${if (folders == 1) "folder" else "folders"}", 11.5f, color = Nq.neutral500)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      IconBtn(Ic.Search, library::toggleSearch, tint = if (s.searchOpen) Nq.accent else Nq.neutral300)
      IconBtn(if (s.sortAscending) Ic.SortAsc else Ic.SortDesc, { library.openSort(true) }, tint = if (s.sort != SortKey.Opened) Nq.accent else Nq.neutral300)
      IconBtn(
        when (s.layout) { LibLayout.Grid -> Ic.Grid; LibLayout.List -> Ic.ListDashes; LibLayout.Comfortable -> Ic.ListBullets; LibLayout.Shelves -> Ic.Rows },
        library::cycleLayout, tint = Nq.neutral300,
      )
      IconBtn(Ic.Plus, { library.openImport(true) }, tint = Nq.accent)
      IconBtn(Ic.Gear, library::openSettings, tint = Nq.neutral300)
    }
  }
}

// ── books ───────────────────────────────────────────────────────────────────

/** While a folder scan runs, what it is doing, so a library still filling up doesn't look finished. */
@Composable
private fun ScanStatusCard(library: LibraryState) {
  val scan by library.scan.collectAsStateWithLifecycle()
  val status = scanStatus(scan) ?: return
  val shape = RoundedCornerShape(10.dp)
  Column(Modifier.fillMaxWidth().clip(shape).background(Nq.surface).border(1.dp, Nq.neutral800, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    QText(status.line, 12.5f, color = Nq.neutral300, lh = 1.45f)
    status.progress?.let { ProgressLine(it) }
  }
}

@Composable
private fun BooksView(s: LibraryUiState, lib: LibraryData, library: LibraryState) {
  val visible by library.visible.collectAsStateWithLifecycle()
  val list = visible.books
  val filtered = s.scope != null || s.query.isNotBlank() || s.filter != LibFilter.All
  val layout = if (s.layout == LibLayout.Shelves && filtered) LibLayout.Grid else s.layout
  val bottom = 32.dp + navBottomPadding()
  val hPad = 20.dp
  when (layout) {
    LibLayout.Grid -> LazyVerticalGrid(
      GridCells.Fixed(3), Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = hPad, end = hPad, top = 14.dp, bottom = bottom),
      horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      item(span = { GridItemSpan(maxLineSpan) }) { BooksHeader(s, lib, library, visible, layout) }
      items(list, key = { it.id }) { b -> GridCard(b, s.sort) { library.openBook(b.id) } }
    }
    LibLayout.List -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = hPad, end = hPad, top = 14.dp, bottom = bottom)) {
      item { Box(Modifier.padding(bottom = 16.dp)) { BooksHeader(s, lib, library, visible, layout) } }
      items(list, key = { it.id }) { b -> ListRow(b, s.sort) { library.openBook(b.id) } }
    }
    LibLayout.Comfortable -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = hPad, end = hPad, top = 14.dp, bottom = bottom)) {
      item { Box(Modifier.padding(bottom = 8.dp)) { BooksHeader(s, lib, library, visible, layout) } }
      items(list, key = { it.id }) { b ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nq.neutral800))
        ComfortableRow(b, s.sort) { library.openBook(b.id) }
      }
    }
    LibLayout.Shelves -> {
      // Only observed while shelves are on screen, so the other layouts never build them.
      val shelves = library.shelves.collectAsStateWithLifecycle().value?.value.orEmpty()
      LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = hPad, end = hPad, top = 14.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { BooksHeader(s, lib, library, visible, layout) }
        items(shelves, key = { it.title }) { shelf -> ShelfRow(shelf, s.sort, library) }
      }
    }
  }
}

@Composable
private fun BooksHeader(s: LibraryUiState, lib: LibraryData, library: LibraryState, visible: VisibleBooks, layout: LibLayout) {
  val list = visible.books
  val showHero = s.scope == null && s.filter == LibFilter.All && s.query.isBlank() && layout != LibLayout.Shelves
  val showSortRow = layout != LibLayout.Shelves
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    if (s.scope != null) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Tag(s.scope.label, { library.setScope(null) }, accent = true, size = 12f, icon = Ic.X, hPad = 10.dp, vPad = 6.dp)
        QText(list.size.toString() + if (list.size == 1) " book" else " books", 11.5f, color = Nq.neutral500)
      }
    } else {
      FilterChips(s, lib, library)
    }
    if (showSortRow) SortRow(s, library)
    val resume = lib.resume
    if (showHero && resume != null) Hero(resume, library)
    // From the same snapshot as the list, so a list still being worked out never shows as empty.
    if (visible.lib.loaded && list.isEmpty() && layout != LibLayout.Shelves) {
      Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Ph(Ic.Books, 28.dp, Nq.neutral500)
        if (visible.lib.books.isEmpty()) {
          QText("No books yet. Add a folder that has EPUB files in it.", 13f, color = Nq.neutral500, align = androidx.compose.ui.text.style.TextAlign.Center)
          QButton("Add books", { library.openImport(true) }, kind = BtnKind.Primary, icon = Ic.Plus, size = 13f)
        } else QText("Nothing matches that filter.", 13f, color = Nq.neutral500)
      }
    }
  }
}

@Composable
internal fun FilterChips(s: LibraryUiState, lib: LibraryData, library: LibraryState) {
  LazyRow(Modifier.bleed(20.dp).nestedScroll(KeepSidewaysScroll), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    items(LibFilter.entries) { f ->
      val on = s.filter == f
      val shape = RoundedCornerShape(999.dp)
      Row(
        Modifier.clip(shape).background(if (on) Nq.accentA(0.12f) else Color.Transparent).border(1.dp, if (on) Nq.accent else Nq.neutral800, shape).clickable { library.setFilter(f) }.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
      ) {
        QText(f.label, 12.5f, color = if (on) Nq.accent200 else Nq.neutral300, maxLines = 1)
        QText(fmt(lib.counts.getValue(f)), 11f, color = Nq.neutral500, tabular = true)
      }
    }
  }
}

@Composable
private fun SortRow(s: LibraryUiState, library: LibraryState) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.clickable { library.openSort(true) }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Ph(Ic.ArrowsDownUp, 14.dp, Nq.accent)
      QText(s.sort.label, 12f, color = Nq.neutral400)
      Ph(Ic.CaretDown, 11.dp, Nq.neutral400)
    }
    Row(Modifier.clickable { library.flipSort() }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      QText(if (s.sortAscending) s.sort.asc else s.sort.desc, 12f, color = Nq.neutral500)
      Ph(if (s.sortAscending) Ic.ArrowUp else Ic.ArrowDown, 13.dp, Nq.neutral500)
    }
  }
}

@Composable
private fun Hero(book: Book, library: LibraryState) {
  val shape = RoundedCornerShape(14.dp)
  Row(
    Modifier.fillMaxWidth().clip(shape).background(cssGradient(135f, Nq.surface, Nq.neutral900)).border(1.dp, Nq.neutral800, shape).clickable { library.read(book.id) }.padding(12.dp),
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
      QText(authorLine(b), 12f, color = Nq.neutral500, maxLines = 1)
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
      QText(cardStatus(b, sort), 11f, color = if (b.status == BookStatus.Reading) Nq.accent300 else Nq.neutral500, tabular = true)
      ProgressLine(b.progress, Modifier.width(40.dp))
    }
  }
}

/** The roomy list row: a larger cover with the title, author and the start of the synopsis beside it. */
@Composable
private fun ComfortableRow(b: Book, sort: SortKey, onClick: () -> Unit) {
  val synopsis = remember(b.desc) { synopsisPreview(b.desc) }
  Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    HeroCover(b)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      QText(b.title, 15f, weight = 500, lh = 1.2f, maxLines = 2)
      QText(authorLine(b), 12f, color = Nq.neutral500, maxLines = 1)
      if (synopsis != null) QText(synopsis, 12.5f, color = Nq.neutral400, lh = 1.45f, maxLines = 3)
      Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        QText(cardStatus(b, sort), 11f, color = if (b.status == BookStatus.Reading) Nq.accent300 else Nq.neutral500, tabular = true)
        ProgressLine(b.progress, Modifier.width(64.dp))
      }
    }
  }
}

@Composable
private fun ShelfRow(shelf: ShelfDef, sort: SortKey, library: LibraryState) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
      QText(shelf.title, 15f, weight = 500)
      QButton(shelf.sub, { library.showShelf(shelf.filter, shelf.scope) }, kind = BtnKind.Ghost, size = 12f, height = 28.dp)
    }
    LazyRow(Modifier.bleed(20.dp).nestedScroll(KeepSidewaysScroll), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      items(shelf.books, key = { it.id }) { b ->
        Column(Modifier.width(shelf.coverWidthDp.dp).clickable { library.openBook(b.id) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
          ShelfCover(b)
          QText(cardStatus(b, sort), 11f, color = Nq.neutral500, maxLines = 1)
        }
      }
    }
  }
}

// ── sheets ──────────────────────────────────────────────────────────────────

@Composable
private fun ImportSheet(s: LibraryUiState, lib: LibraryData, library: LibraryState, onAddFolder: () -> Unit) {
  val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> library.importFiles(uris) }
  SheetHost(s.importOpen, { library.openImport(false) }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
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
            IconBtn(Ic.X, { library.removeFolder(f.id) }, tint = Nq.neutral500, size = 32.dp, iconSize = 16.dp)
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QButton("Rescan", library::rescan, Modifier.weight(1f), icon = Ic.Refresh, size = 12.5f)
        QButton("Add folder", onAddFolder, Modifier.weight(1f), icon = Ic.FolderPlus, size = 12.5f)
      }
      QButton("Import EPUB files", { filePicker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, Modifier.fillMaxWidth(), BtnKind.Primary, icon = Ic.FileDown, size = 13f, height = 42.dp)
    }
  }
}

@Composable
private fun SortSheet(s: LibraryUiState, library: LibraryState) {
  SheetHost(s.sortOpen, { library.openSort(false) }, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 30.dp)) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        QText("Sort by", 17f, weight = 500)
        Segmented(
          listOf(
            SegOption(s.sort.desc, !s.sortAscending, { library.setSortAscending(false) }, Ic.ArrowDown),
            SegOption(s.sort.asc, s.sortAscending, { library.setSortAscending(true) }, Ic.ArrowUp),
          ),
          height = 32.dp, minWidth = 0.dp, size = 12f,
        )
      }
      SortKey.entries.forEach { k ->
        val on = s.sort == k
        Row(
          Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Nq.accentA(0.10f) else Color.Transparent).clickable { library.pickSort(k) }.padding(horizontal = 10.dp, vertical = 11.dp),
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
