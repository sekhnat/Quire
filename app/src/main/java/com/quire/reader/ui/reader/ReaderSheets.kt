package com.quire.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.TextAlignPref
import com.quire.reader.data.db.BookmarkEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.reader.ReaderFontList
import com.quire.reader.reader.ReaderSession
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts
import com.quire.reader.theme.ReaderTheme
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.Ic
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.Ph
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QSlider
import com.quire.reader.ui.QText
import com.quire.reader.ui.QTextField
import com.quire.reader.ui.QuireViewModel
import com.quire.reader.ui.SearchUi
import com.quire.reader.ui.SegOption
import com.quire.reader.ui.Segmented
import com.quire.reader.ui.Sheet
import com.quire.reader.ui.SheetHost
import com.quire.reader.ui.TabRow2
import com.quire.reader.ui.TocTab
import com.quire.reader.ui.UiState
import com.quire.reader.ui.bleed
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed

// ── display settings ────────────────────────────────────────────────────────

@Composable
internal fun DisplaySheet(s: UiState, prefs: ReaderPrefs, hasOverride: Boolean, vm: QuireViewModel) {
  val maxH = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
  SheetHost(s.sheet == Sheet.Display, { vm.openSheet(null) }, Modifier.heightIn(max = maxH), backdrop = 0.5f) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Ph(Ic.SunDim, 18.dp, Nq.neutral400)
        QSlider(s.brightness.toFloat(), { vm.setBrightness(it.toInt()) }, 30f..100f, Modifier.weight(1f))
        Ph(Ic.Sun, 20.dp, Nq.neutral200)
      }

      ReadingControls(prefs) { change -> vm.updatePrefs(change) }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QButton("Tap zones", { vm.showZones(true) }, Modifier.weight(1f), icon = Ic.HandTap, size = 12.5f)
        QButton("Make default", vm::useForAllBooks, Modifier.weight(1f), BtnKind.Primary, icon = Ic.CheckCircle, size = 12.5f)
      }
      if (hasOverride) QButton("Back to my defaults", vm::resetBookPrefs, Modifier.fillMaxWidth(), icon = Ic.Refresh, size = 12.5f)
      QText(if (hasOverride) "This book has its own settings" else "Changes here apply to this book only. Set your defaults in Settings.", 11f, Modifier.fillMaxWidth(), color = Nq.neutral500, align = TextAlign.Center)
    }
  }
}

/** The reading settings, shared by the reader's Display sheet and the Settings screen. */
@Composable
internal fun ReadingControls(prefs: ReaderPrefs, onChange: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderTheme.entries.forEach { t ->
          val on = prefs.theme == t
          val shape = RoundedCornerShape(8.dp)
          Column(Modifier.weight(1f).clickable { onChange { it.copy(theme = t) } }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
              Modifier.fillMaxWidth().height(50.dp).clip(shape).background(t.bg).border(if (on) 2.dp else 1.dp, if (on) Nq.accent else Nq.neutral800, shape),
              contentAlignment = Alignment.Center,
            ) { QText("Aa", 18f, color = t.fg, family = QuireFonts.Literata) }
            QText(t.label, 11f, color = if (on) Nq.accent else Nq.neutral400, maxLines = 1)
          }
        }
      }

      LazyRow(Modifier.bleed(20.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rowItemsIndexed(ReaderFontList) { i, font ->
          val on = prefs.font == i
          val shape = RoundedCornerShape(8.dp)
          Box(Modifier.clip(shape).border(1.dp, if (on) Nq.accent else Nq.neutral800, shape).clickable { onChange { it.copy(font = i) } }.padding(horizontal = 14.dp, vertical = 9.dp)) {
            QText(font.name, 15f, color = if (on) Nq.accent200 else Nq.neutral200, family = font.preview, maxLines = 1)
          }
        }
      }

      SettingRow("Size") {
        val shape = RoundedCornerShape(8.dp)
        Row(Modifier.clip(shape).border(1.dp, Nq.neutral800, shape), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(36.dp).clickable { onChange { it.copy(fontSize = (it.fontSize - 1).coerceIn(ReaderPrefs.MIN_SIZE, ReaderPrefs.MAX_SIZE)) } }, contentAlignment = Alignment.Center) { QText("A", 13f) }
          QText(prefs.fontSize.toString(), 13f, Modifier.width(40.dp), tabular = true, align = TextAlign.Center)
          Box(Modifier.size(36.dp).clickable { onChange { it.copy(fontSize = (it.fontSize + 1).coerceIn(ReaderPrefs.MIN_SIZE, ReaderPrefs.MAX_SIZE)) } }, contentAlignment = Alignment.Center) { QText("A", 19f) }
        }
      }
      SettingRow("Reading mode") {
        Segmented(listOf(
          SegOption("Pages", prefs.mode == ReadMode.Paged, { onChange { it.copy(mode = ReadMode.Paged) } }, Ic.BookOpenText),
          SegOption("Scroll", prefs.mode == ReadMode.Scroll, { onChange { it.copy(mode = ReadMode.Scroll) } }, Ic.Scroll),
        ))
      }
      SettingRow("Line spacing") {
        Segmented(listOf(1.4f to "1.4", 1.6f to "1.6", 1.85f to "1.8").map { (v, label) -> SegOption(label, prefs.lineHeight == v, { onChange { it.copy(lineHeight = v) } }) })
      }
      SettingRow("Margins") {
        Segmented(listOf(16 to "S", 26 to "M", 40 to "L").map { (v, label) -> SegOption(label, prefs.margin == v, { onChange { it.copy(margin = v) } }) })
      }
      SettingRow("Alignment") {
        Segmented(listOf(
          SegOption("", prefs.align == TextAlignPref.Left, { onChange { it.copy(align = TextAlignPref.Left) } }, Ic.AlignLeft),
          SegOption("", prefs.align == TextAlignPref.Justify, { onChange { it.copy(align = TextAlignPref.Justify) } }, Ic.AlignJustify),
        ))
      }

  }
}

@Composable
internal fun SettingRow(label: String, control: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    QText(label, 13f, color = Nq.neutral300)
    control()
  }
}

// ── contents / bookmarks / highlights ───────────────────────────────────────

private class Row2(val title: String, val sub: String, val right: String, val current: Boolean, val indent: Int, val serif: Boolean, val marked: Boolean, val onDelete: (() -> Unit)?, val onClick: () -> Unit)

@Composable
internal fun ContentsSheet(s: UiState, session: ReaderSession, bookmarks: List<BookmarkEntity>, highlights: List<HighlightEntity>, vm: QuireViewModel) {
  val locator by session.current.collectAsStateWithLifecycle()
  val toc by session.tocFlow.collectAsStateWithLifecycle()
  SheetHost(s.sheet == Sheet.Contents, { vm.openSheet(null) }, Modifier.fillMaxHeight(0.78f)) {
    Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      TabRow2(TocTab.entries.map { it.label }, s.tocTab.ordinal, { vm.setTocTab(TocTab.entries[it]) }, Modifier.padding(horizontal = 20.dp), fill = true, horizontalPadding = 0.dp)
      val currentChapter = session.chapterIndex(locator)
      val rows: List<Row2> = when (s.tocTab) {
        TocTab.Contents -> toc.mapIndexed { i, e ->
          Row2(e.title, "", if (e.position > 0) e.position.toString() else "", i == currentChapter, e.depth, false, false, null) { vm.goTo(e) }
        }
        TocTab.Bookmarks -> bookmarks.map { b ->
          Row2(b.label, "${(b.progress * 100).toInt()}% through the book", "", false, 0, false, true, { vm.deleteBookmark(b.id) }) { ReaderSession.parseLocator(b.locatorJson)?.let(vm::goTo) }
        }
        TocTab.Highlights -> highlights.map { h ->
          Row2(h.text.ifBlank { "Highlight" }, h.note ?: "${(h.progress * 100).toInt()}% through the book · highlighted", "", false, 0, true, true, { vm.deleteHighlight(h.id) }) { ReaderSession.parseLocator(h.locatorJson)?.let(vm::goTo) }
        }
      }
      val list = rememberLazyListState()
      LaunchedEffect(s.sheet, s.tocTab) { if (s.sheet == Sheet.Contents && s.tocTab == TocTab.Contents && currentChapter > 2) list.scrollToItem(currentChapter - 2) }
      LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        itemsIndexed(rows) { _, r -> TocItem(r) }
        if (rows.isEmpty()) item {
          QText(
            when (s.tocTab) { TocTab.Contents -> "This book has no table of contents."; TocTab.Bookmarks -> "No bookmarks yet. Tap the bookmark icon at the top to add one."; TocTab.Highlights -> "No highlights yet. Press and hold a word, then drag to select, and choose Highlight." },
            13f, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp), color = Nq.neutral500, align = TextAlign.Center,
          )
        }
      }
    }
  }
}

@Composable
private fun TocItem(r: Row2) {
  val mark = r.current || r.marked
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (r.current) Nq.accentA(0.10f) else Color.Transparent).clickable(onClick = r.onClick).padding(start = 10.dp + (14 * r.indent).dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.width(2.dp).heightIn(min = 30.dp).clip(RoundedCornerShape(2.dp)).background(if (mark) Nq.accent else Color.Transparent))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      QText(r.title, 14f, color = if (r.current) Nq.accent200 else if (r.serif) Nq.neutral200 else Nq.text, family = if (r.serif) QuireFonts.Literata else QuireFonts.Inter, lh = 1.4f, maxLines = 2)
      if (r.sub.isNotEmpty()) QText(r.sub, 11f, color = Nq.neutral500, maxLines = 2)
    }
    if (r.right.isNotEmpty()) QText(r.right, 11f, color = Nq.neutral500, tabular = true)
    if (r.onDelete != null) IconBtn(Ic.X, r.onDelete, tint = Nq.neutral500, size = 32.dp, iconSize = 16.dp)
  }
}

// ── in-book search ──────────────────────────────────────────────────────────

@Composable
internal fun SearchOverlay(s: UiState, search: SearchUi, vm: QuireViewModel) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(s.textSearchOpen) { if (s.textSearchOpen) { delay(300); runCatching { focus.requestFocus() } } }
  Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(s.textSearchOpen, enter = slideInVertically(tween(280)) { -it }, exit = slideOutVertically(tween(280)) { -it }) {
      Column(Modifier.fillMaxSize().background(Nq.bg).statusBarsPadding()) {
        Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          IconBtn(Ic.ArrowLeft, { vm.setTextSearch(false) })
          QTextField(s.textQuery, vm::setTextQuery, "Search in this book", Modifier.weight(1f), onClear = { vm.setTextQuery("") }, focusRequester = focus)
        }
        val idle = s.textQuery.trim().length < 2
        val n = search.hits.size
        QText(
          when {
            idle -> "Searches the whole book"
            search.running && n == 0 -> "Searching…"
            n == 0 -> "No matches"
            else -> (if (n >= ReaderSession.MAX_HITS) "$n+ matches" else "$n ${if (n == 1) "match" else "matches"}") + if (search.running) " · searching…" else ""
          },
          11.5f, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), color = Nq.neutral500,
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
          itemsIndexed(search.hits) { _, r ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.openSearchHit(r) }.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              QText(r.chapter.ifEmpty { "—" }, 11f, color = Nq.neutral500, maxLines = 1)
              Text(
                buildAnnotatedString {
                  append(r.before)
                  withStyle(SpanStyle(color = Nq.text, background = Nq.accentA(0.30f))) { append(r.hit) }
                  append(r.after)
                },
                style = TextStyle(fontFamily = QuireFonts.Literata, fontSize = 14.sp, lineHeight = 21.sp, color = Nq.neutral300, fontWeight = FontWeight.Normal),
              )
            }
          }
        }
      }
    }
  }
}

// ── notes ───────────────────────────────────────────────────────────────────

@Composable
internal fun NoteSheet(s: UiState, highlights: List<HighlightEntity>, vm: QuireViewModel) {
  val id = s.noteFor
  val h = highlights.firstOrNull { it.id == id }
  var text by remember(id) { mutableStateOf(h?.note.orEmpty()) }
  val focus = remember { FocusRequester() }
  LaunchedEffect(id) { if (id != null) { delay(350); runCatching { focus.requestFocus() } } }
  SheetHost(id != null, { vm.editNote(null) }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      QText("Note", 17f, weight = 500)
      if (h != null) QText(h.text, 13f, color = Nq.neutral400, family = QuireFonts.Literata, lh = 1.5f, maxLines = 3)
      QTextField(text, { text = it }, "Add a note to this passage", singleLine = false, minLines = 3, focusRequester = focus)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QButton("Remove highlight", { id?.let(vm::deleteHighlight) }, Modifier.weight(1f), icon = Ic.X, size = 12.5f)
        QButton("Save", { id?.let { vm.saveNote(it, text) } }, Modifier.weight(1f), BtnKind.Primary, icon = Ic.CheckBold, size = 12.5f)
      }
    }
  }
}
