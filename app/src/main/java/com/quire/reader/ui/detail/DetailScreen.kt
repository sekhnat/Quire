package com.quire.reader.ui.detail

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.quire.reader.data.Book
import com.quire.reader.data.BookStatus
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.DetailCover
import com.quire.reader.ui.Ic
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.LibraryData
import com.quire.reader.ui.MoreCover
import com.quire.reader.ui.Ph
import com.quire.reader.ui.ProgressLine
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText
import com.quire.reader.ui.QTextField
import com.quire.reader.ui.QuireViewModel
import com.quire.reader.ui.Scope
import com.quire.reader.ui.ScopeKind
import com.quire.reader.ui.SheetHost
import com.quire.reader.ui.Tag
import com.quire.reader.ui.UiState
import com.quire.reader.ui.bleed
import com.quire.reader.ui.cornerGlow
import com.quire.reader.ui.statusLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(bookId: Long, s: UiState, lib: LibraryData, vm: QuireViewModel) {
  BackHandler { vm.goLibrary() }
  val book = lib.byId[bookId]
  if (book == null) { Box(Modifier.fillMaxSize().background(Nq.bg)); return }
  val siblings = lib.siblings(book)
  val more = lib.moreBy(book)
  val reading = book.status == BookStatus.Reading

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Nq.bg).cornerGlow(420.dp, 320.dp, Nq.section).statusBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Ic.ArrowLeft, vm::goLibrary)
        Box(Modifier.weight(1f))
        IconBtn(Ic.Pencil, { vm.openEdit(true) }, tint = Nq.neutral300)
      }
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
          DetailCover(book)
          Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (book.series != null) {
              QText(
                book.series + (book.seriesNoLabel?.let { " · Book $it" } ?: ""), 10f,
                Modifier.clickable { vm.openLibraryScope(Scope(ScopeKind.Series, book.series)) }, color = Nq.accent, ls = 0.1f, upper = true,
              )
            }
            QText(book.title, 22f, weight = 500, ls = -0.01f, lh = 1.15f, balance = true)
            QText(book.author, 13.5f, Modifier.clickable { vm.openLibraryScope(Scope(ScopeKind.Author, book.primaryAuthor)) }, color = Nq.accent300)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
              if (book.rating > 0) (1..5).forEach { i -> Ph(if (i <= book.rating) Ic.StarFill else Ic.Star, 13.dp, if (i <= book.rating) Nq.accent else Nq.neutral700) }
              QText(listOfNotNull(book.year?.toString(), "${book.pages} pages").joinToString(" · "), 11.5f, Modifier.padding(start = if (book.rating > 0) 6.dp else 0.dp), color = Nq.neutral500)
            }
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cta = when (book.status) { BookStatus.Reading -> "Continue · ${book.pct}%"; BookStatus.Finished -> "Read again"; BookStatus.Unread -> "Start reading" }
            QButton(cta, { vm.read(book.id, restart = book.status == BookStatus.Finished) }, Modifier.weight(1f), BtnKind.Primary, icon = Ic.BookOpen, height = 44.dp, enabled = book.readable)
            IconBtn(Ic.FolderSimplePlus, { vm.openEdit(true) }, size = 44.dp, iconSize = 19.dp, bordered = true)
            IconBtn(Ic.CheckBold, { vm.setFinished(book.id, book.status != BookStatus.Finished) }, size = 44.dp, iconSize = 19.dp, bordered = true, tint = if (book.status == BookStatus.Finished) Nq.accent else Nq.text)
          }
          if (!book.readable) QText("This file can’t be opened. It may be damaged or protected.", 12f, color = Nq.neutral500)
          if (reading) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
              ProgressLine(book.progress, Modifier.weight(1f))
              QText(book.timeLeftLabel().removePrefix("about "), 11f, color = Nq.neutral500)
            }
          }
        }

        if (!book.desc.isNullOrBlank()) QText(book.desc, 14f, color = Nq.neutral300, lh = 1.6f)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          book.tags.take(12).forEach { t -> Tag(t, { vm.openLibraryScope(Scope(ScopeKind.Tag, t)) }, size = 12f, vPad = 3.dp) }
          Tag("Tag", { vm.openEdit(true) }, outline = true, size = 12f, icon = Ic.Plus, vPad = 3.dp)
        }

        if (siblings.size > 1) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Kicker("Reading order")
            Column {
              siblings.forEach { b ->
                Row(
                  Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (b.id == book.id) Nq.surface else Color.Transparent).clickable { vm.openBook(b.id) }.padding(8.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                  QText(b.seriesNoLabel ?: "–", 12f, Modifier.width(16.dp), color = Nq.neutral500, tabular = true)
                  com.quire.reader.ui.CoverBox(b, Modifier.size(width = 26.dp, height = 39.dp), radius = 2.dp)
                  QText(b.title, 13.5f, Modifier.weight(1f), maxLines = 1)
                  QText(statusLabel(b), 11f, color = Nq.neutral500)
                }
              }
            }
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Kicker("File", Modifier.padding(bottom = 6.dp))
          listOf(
            Triple("Format", "EPUB · ${book.sizeLabel}", false),
            Triple("Language", book.languageLabel, false),
            Triple("Added", book.addedLabel, false),
            Triple("Source", if (book.fromCalibre) "Calibre · metadata.opf" else "Folder scan", false),
            Triple("Location", book.path.removePrefix("/storage/emulated/0"), true),
          ).forEach { (k, v, mono) ->
            Row(
              Modifier.fillMaxWidth().drawBehind { drawRect(Nq.neutral900, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }.padding(vertical = 7.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              QText(k, 12.5f, Modifier.width(72.dp), color = Nq.neutral500)
              QText(v, if (mono) 11f else 12.5f, Modifier.weight(1f), color = Nq.neutral200, family = if (mono) QuireFonts.Mono else QuireFonts.Inter)
            }
          }
        }

        if (more.isNotEmpty()) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Kicker("More by ${book.authorLast}")
            LazyRow(Modifier.bleed(20.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              items(more, key = { it.id }) { b ->
                Column(Modifier.width(84.dp).clickable { vm.openBook(b.id) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  MoreCover(b)
                  QText(statusLabel(b), 11f, color = Nq.neutral500)
                }
              }
            }
          }
        }
      }
    }
    EditSheet(s.editOpen, book, vm)
  }
}

/** Quire-side edits: rating and tags. They live in Quire's database and never change Calibre's files. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditSheet(open: Boolean, book: Book, vm: QuireViewModel) {
  var newTag by remember(book.id, open) { mutableStateOf("") }
  val notesExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
    if (uri != null) vm.exportNotes(book.id, uri)
  }
  SheetHost(open, { vm.openEdit(false) }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      QText("Edit in Quire", 17f, weight = 500)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker("Rating")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
          (1..5).forEach { i ->
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.setRating(book.id, if (book.rating == i) null else i) }, contentAlignment = Alignment.Center) {
              Ph(if (i <= book.rating) Ic.StarFill else Ic.Star, 22.dp, if (i <= book.rating) Nq.accent else Nq.neutral600)
            }
          }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker("Tags")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          book.tags.filter { it !in book.userTags }.forEach { t -> Tag(t, {}, size = 12f, vPad = 4.dp) }
          book.userTags.forEach { t -> Tag(t, { vm.removeTag(book.id, t) }, size = 12f, icon = Ic.X, accent = true, vPad = 4.dp) }
        }
        QTextField(newTag, { newTag = it }, "Add a tag", leadingIcon = Ic.Plus, onSubmit = { if (newTag.isNotBlank()) { vm.addTag(book.id, newTag); newTag = "" } })
      }
      QText("Your tags (outlined in purple) can be removed here. Tags that came with the book can only be changed in Calibre. Your changes stay in Quire.", 11.5f, color = Nq.neutral500, lh = 1.4f)
      QButton("Export notes", { notesExport.launch("${book.title} — notes.md") }, Modifier.fillMaxWidth(), icon = Ic.FileDown, size = 12.5f)
    }
  }
}
