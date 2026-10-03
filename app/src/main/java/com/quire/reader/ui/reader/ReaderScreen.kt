package com.quire.reader.ui.reader

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quire.reader.data.ReadMode
import com.quire.reader.reader.EpubHost
import com.quire.reader.reader.ReaderSession
import com.quire.reader.reader.toEpubPreferences
import com.quire.reader.theme.Nq
import com.quire.reader.theme.ReaderTheme
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.Ic
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.LibraryData
import com.quire.reader.ui.Ph
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QSlider
import com.quire.reader.ui.QText
import com.quire.reader.ui.QuireViewModel
import com.quire.reader.ui.ReaderLoad
import com.quire.reader.ui.Sheet
import com.quire.reader.ui.TocTab
import com.quire.reader.ui.UiState
import com.quire.reader.ui.dashedBorder
import kotlin.math.roundToInt

/** Fractions of the screen width at which a tap means "back", "menu" or "next". */
private const val ZONE_BACK = 1f / 3.3f
private const val ZONE_NEXT = 2.3f / 3.3f

@Composable
fun ReaderScreen(s: UiState, lib: LibraryData, vm: QuireViewModel) {
  val load by vm.reader.collectAsStateWithLifecycle()
  when (val l = load) {
    is ReaderLoad.Ready -> ReaderContent(l.session, s, vm)
    is ReaderLoad.Failed -> Message(l.message, vm)
    else -> Message(null, vm)
  }
}

@Composable
private fun Message(error: String?, vm: QuireViewModel) {
  BackHandler { vm.closeReader() }
  Column(Modifier.fillMaxSize().background(Nq.bg).statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    QText(error ?: "Opening book…", 14f, color = Nq.neutral400, align = TextAlign.Center)
    if (error != null) QButton("Back to library", vm::closeReader, Modifier.padding(top = 20.dp), BtnKind.Primary, icon = Ic.ArrowLeft)
  }
}

@Composable
private fun ReaderContent(session: ReaderSession, s: UiState, vm: QuireViewModel) {
  val prefs by vm.prefs.collectAsStateWithLifecycle()
  val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
  val highlights by vm.highlights.collectAsStateWithLifecycle()
  val search by vm.search.collectAsStateWithLifecycle()
  val bookSearch by vm.bookSearchUi.collectAsStateWithLifecycle()
  val hasOverride by vm.hasBookOverride.collectAsStateWithLifecycle()
  val locator by session.current.collectAsStateWithLifecycle()
  val theme = prefs.theme
  val book = session.book

  LightBarsEffect(theme, s.chrome || s.textSearchOpen)
  val bgColor by animateColorAsState(theme.bg, tween(300), label = "readerBg")
  val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val epubPrefs = remember(prefs) { prefs.toEpubPreferences() }
  val bookmarked = vm.isBookmarked(session, bookmarks)

  BackHandler {
    when {
      s.noteFor != null -> vm.editNote(null)
      s.textSearchOpen -> vm.setTextSearch(false)
      s.sheet != null || s.showZones || s.activeHighlight != null || s.chrome -> vm.closeReaderOverlays()
      else -> vm.closeReader()
    }
  }

  val onTap: (Float) -> Unit = { x ->
    when {
      s.activeHighlight != null -> vm.setActiveHighlight(null)
      s.chrome -> vm.setChrome(false)
      x < ZONE_BACK -> if (!session.goBackward()) vm.toast("Start of book")
      x > ZONE_NEXT -> if (!session.goForward()) vm.toast("End of book")
      else -> vm.setChrome(true)
    }
  }

  Box(Modifier.fillMaxSize().background(bgColor)) {
    QText(book.title, 11f, Modifier.padding(top = statusTop + 6.dp, start = 24.dp, end = 24.dp).fillMaxWidth(), color = theme.muted, ls = 0.04f, maxLines = 1, align = TextAlign.Center)

    Box(Modifier.fillMaxSize().padding(top = statusTop + 32.dp, bottom = navBottom + 46.dp)) {
      EpubHost(
        session = session, preferences = epubPrefs, onTap = onTap,
        onSelectionAction = vm::onSelectionAction, onHighlightTapped = { vm.setActiveHighlight(it) },
        modifier = Modifier.fillMaxSize(),
      )
      if (bookmarked) Ph(Ic.BookmarkFill, 26.dp, Nq.accent, Modifier.align(Alignment.TopEnd).padding(end = 22.dp).offset(y = (-6).dp))
    }

    // footer: where you are, and how long is left
    val left = session.minutesLeft()
    Row(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = navBottom + 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      QText("Page ${session.position} of ${session.positions.size}", 11f, color = theme.muted, tabular = true)
      QText(timeLabel(left), 11f, color = theme.muted, tabular = true)
    }

    // in-app brightness dimmer (never intercepts touches)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1 - s.brightness / 100f) * 0.8f)))

    if (s.showZones) TapZones { vm.showZones(false) }

    ReaderChrome(s, session, vm, bookmarked, locator?.locations?.totalProgression?.toFloat() ?: 0f)
    HighlightActions(s, vm, navBottom)
    DisplaySheet(s, prefs, hasOverride, vm)
    ContentsSheet(s, session, bookmarks, highlights, vm)
    SearchOverlay(s, search, bookSearch, vm)
    NoteSheet(s, highlights, vm)
  }
}

private fun timeLabel(minutes: Int): String = when {
  minutes < 1 -> "Almost done"
  minutes < 60 -> "$minutes min left"
  else -> "${minutes / 60}h ${minutes % 60}m left"
}

@Composable
private fun LightBarsEffect(theme: ReaderTheme, overlay: Boolean) {
  val view = LocalView.current
  val context = LocalContext.current
  DisposableEffect(theme, overlay) {
    var c = context
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    val window = (c as? Activity)?.window
    val controller = window?.let { WindowCompat.getInsetsController(it, view) }
    val light = theme.isLight && !overlay
    controller?.isAppearanceLightStatusBars = light
    controller?.isAppearanceLightNavigationBars = light
    onDispose {
      controller?.isAppearanceLightStatusBars = false
      controller?.isAppearanceLightNavigationBars = false
    }
  }
}

@Composable
private fun ReaderChrome(s: UiState, session: ReaderSession, vm: QuireViewModel, marked: Boolean, progress: Float) {
  val book = session.book
  var dragging by remember { mutableStateOf<Float?>(null) }
  Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(s.chrome, Modifier.align(Alignment.TopCenter), enter = slideInVertically(tween(250)) { -it }, exit = slideOutVertically(tween(250)) { -it }) {
      Row(
        Modifier.fillMaxWidth().background(Nq.surface).statusBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        IconBtn(Ic.ArrowLeft, vm::closeReader)
        Column(Modifier.weight(1f)) {
          QText(book.title, 14f, weight = 500, maxLines = 1)
          QText(book.author, 11f, color = Nq.neutral500, maxLines = 1)
        }
        IconBtn(Ic.Search, { vm.setTextSearch(true) })
        IconBtn(if (marked) Ic.BookmarkFill else Ic.Bookmark, vm::toggleBookmark, tint = if (marked) Nq.accent else Nq.text)
      }
    }
    AnimatedVisibility(s.chrome, Modifier.align(Alignment.BottomCenter), enter = slideInVertically(tween(250)) { it }, exit = slideOutVertically(tween(250)) { it }) {
      Column(
        Modifier.fillMaxWidth().background(Nq.surface).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        val shown = dragging ?: progress
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          QText(session.chapterTitle().ifEmpty { "—" }, 11.5f, Modifier.weight(1f), color = Nq.neutral400, maxLines = 1)
          QText("%.1f%%".format(java.util.Locale.US, shown * 100), 11.5f, color = Nq.neutral400, tabular = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          IconBtn(Ic.CaretLineLeft, session::firstPage, tint = Nq.neutral400, iconSize = 18.dp)
          QSlider(
            shown, { dragging = it }, 0f..1f, Modifier.weight(1f),
            onValueChangeFinished = { dragging?.let { session.goToProgress(it) }; dragging = null },
          )
          IconBtn(Ic.CaretLineRight, session::lastPage, tint = Nq.neutral400, iconSize = 18.dp)
        }
        Row(Modifier.fillMaxWidth()) {
          listOf(
            Triple(Ic.ListBullets, "Contents") { vm.openSheet(Sheet.Contents, TocTab.Contents) },
            Triple(Ic.TextAa, "Display") { vm.openSheet(Sheet.Display) },
            Triple(Ic.Highlighter, "Highlights") { vm.openSheet(Sheet.Contents, TocTab.Highlights) },
            Triple(Ic.Search, "Search") { vm.setTextSearch(true) },
          ).forEach { (icon, label, act) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = act).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Ph(icon, 22.dp, Nq.text)
              QText(label, 11f, color = Nq.neutral300)
            }
          }
        }
      }
    }
  }
}

/** Actions for a highlight the reader just tapped. */
@Composable
private fun HighlightActions(s: UiState, vm: QuireViewModel, navBottom: Dp) {
  val id = s.activeHighlight
  Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(id != null, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = navBottom + 56.dp), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
      val shape = RoundedCornerShape(14.dp)
      Row(Modifier.fillMaxWidth().shadow(16.dp, shape).background(Nq.surface, shape).padding(6.dp)) {
        listOf(
          Triple(Ic.NotePencil, "Note", Nq.text) to { id?.let { vm.editNote(it) } },
          Triple(Ic.Copy, "Copy", Nq.text) to { id?.let { vm.copyHighlight(it) } },
          Triple(Ic.X, "Remove", Nq.accent) to { id?.let { vm.deleteHighlight(it) } },
        ).forEach { (spec, act) ->
          val (icon, label, color) = spec
          Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = { act() }).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Ph(icon, 20.dp, color)
            QText(label, 11f, color = color)
          }
        }
      }
    }
  }
}

@Composable
private fun TapZones(onDismiss: () -> Unit) {
  val zones = listOf(Triple(1f, Ic.CaretLeft, "Back"), Triple(1.3f, Ic.List, "Menu"), Triple(1f, Ic.CaretRight, "Next"))
  Row(Modifier.fillMaxSize().background(Nq.scrim.copy(alpha = 0.6f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
    zones.forEach { (weight, icon, label) ->
      Column(
        Modifier.weight(weight).fillMaxHeight().padding(start = 4.dp, end = 4.dp, top = 70.dp, bottom = 60.dp).dashedBorder(Nq.accent500, 8.dp).background(Nq.accentA(0.08f), RoundedCornerShape(8.dp)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
      ) {
        Ph(icon, 22.dp, Nq.accent200)
        QText(label, 12f, color = Nq.accent200)
      }
    }
  }
}
