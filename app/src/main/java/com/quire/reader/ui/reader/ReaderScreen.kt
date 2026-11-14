package com.quire.reader.ui.reader

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import com.quire.reader.data.StatusItem
import com.quire.reader.reader.PageFrame
import com.quire.reader.reader.SelectionAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quire.reader.ReaderKeyHost
import com.quire.reader.data.HighlightColor
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderControls
import com.quire.reader.data.ReaderOrientation
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.reader.EpubHost
import com.quire.reader.reader.ReadAloudStatus
import com.quire.reader.reader.ReadAloudUi
import com.quire.reader.reader.speedLabel
import com.quire.reader.reader.ReaderSession
import com.quire.reader.reader.toEpubPreferences
import com.quire.reader.theme.Nq
import com.quire.reader.theme.ReaderTheme
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.ColorDot
import com.quire.reader.ui.Ic
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.MetaLine
import com.quire.reader.ui.Ph
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QSlider
import com.quire.reader.ui.QText
import com.quire.reader.ui.Sheet
import com.quire.reader.ui.TocTab
import com.quire.reader.ui.dashedBorder
import kotlin.math.roundToInt

/** The room the read-aloud controls take from the bottom of the page. */
private val READ_ALOUD_BAR_SPACE = 56.dp

/** Fractions of the screen width at which a tap means "back", "menu" or "next". */
private const val ZONE_BACK = 1f / 3.3f
private const val ZONE_NEXT = 2.3f / 3.3f

@Composable
fun ReaderScreen(reader: ReaderState) {
  val load by reader.load.collectAsStateWithLifecycle()
  val s by reader.ui.collectAsStateWithLifecycle()
  when (val l = load) {
    is ReaderLoad.Ready -> ReaderContent(l.session, s, reader)
    is ReaderLoad.Failed -> Message(l.message, reader)
    else -> Message(null, reader)
  }
}

@Composable
private fun Message(error: String?, reader: ReaderState) {
  BackHandler { reader.leave() }
  Column(Modifier.fillMaxSize().background(Nq.bg).statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    QText(error ?: "Opening book…", 14f, color = Nq.neutral400, align = TextAlign.Center)
    if (error != null) QButton("Back to library", reader::leave, Modifier.padding(top = 20.dp), BtnKind.Primary, icon = Ic.ArrowLeft)
  }
}

@Composable
private fun ReaderContent(session: ReaderSession, s: ReaderUiState, reader: ReaderState) {
  val prefs by reader.prefs.collectAsStateWithLifecycle()
  val bookmarks by reader.bookmarks.collectAsStateWithLifecycle()
  val highlights by reader.highlights.collectAsStateWithLifecycle()
  val search by reader.search.collectAsStateWithLifecycle()
  val bookSearch by reader.bookSearchUi.collectAsStateWithLifecycle()
  val hasOverride by reader.hasBookOverride.collectAsStateWithLifecycle()
  val hasAdvancedOverride by reader.hasBookAdvancedOverride.collectAsStateWithLifecycle()
  val locator by session.current.collectAsStateWithLifecycle()
  val theme = prefs.theme
  val book = session.book

  LightBarsEffect(theme, s.chrome || s.textSearchOpen)
  val bgColor by animateColorAsState(theme.bg, tween(300), label = "readerBg")
  val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val controls by reader.controls.collectAsStateWithLifecycle()
  val listening by reader.readAloud.collectAsStateWithLifecycle()
  // While it reads aloud the volume keys are the phone's own, so the volume can be set.
  ReaderControlsEffects(session, controls, s.overlaysOpen || listening.active, reader, keepAwake = s.auto.running)
  LifecycleResumeEffect(session) {
    reader.resumeReading()
    reader.pauseAuto(AutoPause.Background, false)
    onPauseOrDispose { reader.pauseReading(); reader.pauseAuto(AutoPause.Background, true) }
  }
  // The menu, a sheet, a selection or the full-screen viewer holds auto-scroll still while it is open.
  val overlay = s.overlaysOpen || s.definition != null
  LaunchedEffect(overlay) { reader.pauseAuto(AutoPause.Overlay, overlay) }
  LaunchedEffect(prefs.mode, prefs.fontSize, prefs.lineHeight) { reader.refreshAuto() }
  // The user's fonts are part of the key: removing the font a book uses must take it back to the default.
  val userFonts by reader.fonts.fonts.collectAsStateWithLifecycle()
  val epubPrefs = remember(prefs, session.layout, userFonts) { prefs.toEpubPreferences(session.layout) }
  // The single submission path: mapped preferences go to the session, which deduplicates
  // by equality and applies them through the navigator in one serialized pass.
  LaunchedEffect(session, epubPrefs) { session.submit(epubPrefs) }
  val bookmarked = reader.isBookmarked(session, bookmarks)

  BackHandler {
    when {
      s.quote != null -> reader.closeQuote()
      s.definition != null -> reader.closeDefinition()
      s.noteFor != null -> reader.editNote(null)
      s.textSearchOpen -> reader.setTextSearch(false)
      s.sheet != null || s.showZones || s.activeHighlight != null || s.chrome -> reader.closeReaderOverlays()
      else -> reader.leave()
    }
  }

  val onTap: (Float) -> Unit = { x ->
    when {
      s.definition != null -> reader.closeDefinition()
      s.activeHighlight != null -> reader.setActiveHighlight(null)
      s.chrome -> reader.setChrome(false)
      x < ZONE_BACK -> if (!session.goBackward()) reader.toast("Start of book")
      x > ZONE_NEXT -> if (!session.goForward()) reader.toast("End of book")
      else -> reader.setChrome(true)
    }
  }

  val status = controls.statusLine
  val tick by rememberMinuteTick()
  val battery by rememberBattery(enabled = status.uses(StatusItem.Battery))
  // Built from the collected locator, so every move recomposes it (the session's own getters are not state).
  val here = locator
  val position = here?.locations?.position ?: 1
  val progress = (here?.locations?.totalProgression ?: 0.0).toFloat().coerceIn(0f, 1f)
  val facts = StatusFacts(
    bookTitle = book.title,
    chapterTitle = if (status.uses(StatusItem.ChapterTitle)) session.chapterTitle(here) else "",
    clock = clockText(tick),
    battery = battery,
    position = position,
    total = session.positions.size,
    pagesLeftInChapter = StatusLineText.pagesLeftInChapter(session.chapterStarts(), position, session.positions.size),
    minutesLeftInBook = session.minutesLeftAt(progress),
    progress = progress,
  )
  // The page's frame: margins (custom ones put the sides in padding), and the status lines' bands when they show.
  val (padLeft, padRight) = PageFrame.sidePadding(prefs)
  val topSpace = PageFrame.topSpace(prefs, status.showHeader).dp
  val bottomSpace = PageFrame.bottomSpace(prefs, status.showFooter).dp

  var lastPressY by remember { mutableFloatStateOf(0f) }
  BoxWithConstraints(Modifier.fillMaxSize().background(bgColor)) {
    val fullWidth = maxWidth
    if (status.showHeader) {
      StatusLineRow(status.header, facts, theme.muted, Modifier.padding(top = statusTop + 6.dp, start = 24.dp, end = 24.dp))
    }

    Box(
      Modifier.fillMaxSize().padding(top = statusTop + topSpace, bottom = navBottom + bottomSpace + if (listening.active || s.auto.on) READ_ALOUD_BAR_SPACE else 0.dp)
        // Watches (never consumes) where each touch on the page starts, to place the definition card clear of the word.
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
              when (event.type) {
                androidx.compose.ui.input.pointer.PointerEventType.Press -> {
                  lastPressY = event.changes.first().position.y
                  reader.pauseAuto(AutoPause.Touch, true)
                }
                androidx.compose.ui.input.pointer.PointerEventType.Release ->
                  if (event.changes.all { !it.pressed }) reader.pauseAuto(AutoPause.Touch, false)
              }
            }
          }
        },
    ) {
      // Taps on a custom margin still turn pages or show the menu, by where they land across the whole reader.
      if (padLeft > 0) MarginTaps(Modifier.align(Alignment.CenterStart).width(padLeft.dp)) { x -> onTap(x / fullWidth.value) }
      if (padRight > 0) MarginTaps(Modifier.align(Alignment.CenterEnd).width(padRight.dp)) { x -> onTap((fullWidth.value - padRight + x) / fullWidth.value) }
      val innerWidth = fullWidth.value - padLeft - padRight
      EpubHost(
        session = session, preferences = epubPrefs, selectionActions = reader.selectionActions,
        onTap = { f -> onTap((padLeft + f * innerWidth) / fullWidth.value) },
        onSelectionAction = reader::onSelectionAction, onHighlightTapped = { reader.setActiveHighlight(it) },
        onInternalLink = reader::onInternalLink,
        onMedia = reader::openMedia,
        onSelectionStarted = reader::onSelectionStarted,
        onSelectionEnded = reader::onSelectionEnded,
        modifier = Modifier.fillMaxSize().padding(start = padLeft.dp, end = padRight.dp),
      )
      if (bookmarked) Ph(Ic.BookmarkFill, 26.dp, Nq.accent, Modifier.align(Alignment.TopEnd).padding(end = 22.dp).offset(y = (-6).dp))
    }

    if (status.showFooter) {
      StatusLineRow(status.footer, facts, theme.muted, Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = navBottom + 18.dp))
    }

    ReadAloudBar(listening, reader, Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + 40.dp))
    AutoScrollBar(s.auto, controls.autoScroll, prefs.mode == ReadMode.Paged, reader, Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + 40.dp))

    // The definition card, over the page: at the bottom, or at the top when the selected word is low on the screen.
    val pageHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { (maxHeight - statusTop - topSpace - navBottom - bottomSpace).toPx() }
    // Where the long press landed decides: a word low on the page gets the card at the top, out of its way.
    val cardAtTop = lastPressY > pageHeightPx * 0.5f
    DefinitionCard(
      s.definition, cardAtTop, maxExpanded = maxHeight * 0.7f,
      actions = DefinitionCardActions(
        close = reader::closeDefinition, back = reader::definitionBack, more = reader::expandDefinition, lookUp = reader::lookUpInCard,
        highlight = { reader.onSelectionAction(SelectionAction.Highlight) }, copy = { reader.onSelectionAction(SelectionAction.Copy) },
        openInApp = reader.openInApp?.let { open -> { s.definition?.let { open(it.query) }; Unit } },
      ),
      modifier = Modifier.align(if (cardAtTop) Alignment.TopCenter else Alignment.BottomCenter)
        .padding(top = statusTop + 8.dp, bottom = navBottom + 8.dp),
    )

    s.returnTo?.let { point ->
      ReturnChip(point.label, reader::returnFromLink,
        Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + 40.dp + if (listening.active || s.auto.on) READ_ALOUD_BAR_SPACE else 0.dp))
    }

    // in-app brightness dimmer (never intercepts touches)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1 - s.brightness / 100f) * 0.8f)))

    ReaderChrome(s, session, reader, bookmarked, locator?.locations?.totalProgression?.toFloat() ?: 0f)
    HighlightActions(s, highlights, reader, navBottom)
    DisplaySheet(s, session, prefs, hasOverride, hasAdvancedOverride, reader)
    ContentsSheet(s, session, bookmarks, highlights, reader)
    SearchOverlay(s, search, bookSearch, reader)
    NoteSheet(s, highlights, reader)
    VoiceSheet(s, reader)
    StatusLineSheet(s.sheet == Sheet.StatusLine, controls.statusLine, facts, theme, { reader.openSheet(null) }) { change -> reader.updateControls { it.copy(statusLine = change(it.statusLine)) } }

    ShareQuoteSheet(s.quote, reader::closeQuote, reader::shareQuoteText, reader::toast)
    s.media?.let { media ->
      MediaViewer(
        media, theme, prefs.advanced.imageFilter, reader::mediaBytes,
        intercept = { request -> session.navigator?.interceptViewerRequest(request) },
        onClose = reader::closeMedia, toast = reader::toast,
      )
    }

    // The warm light, over the page, the status lines, the menus and the sheets; it never takes touches.
    if (controls.warmLight.isActive(tick, minuteOfDay(tick))) WarmTint(controls.warmLight)

    if (s.showZones) TapZones { reader.showZones(false) }
  }
}

/** Applies the app-wide reading controls while this reader is on screen, and puts everything back when it leaves. */
@Composable
private fun ReaderControlsEffects(session: ReaderSession, controls: ReaderControls, overlaysOpen: Boolean, reader: ReaderState, keepAwake: Boolean) {
  val view = LocalView.current
  val activity = LocalActivity.current

  // Auto-scroll keeps the screen on while it runs, whatever *Keep screen on* is set to.
  DisposableEffect(view, controls.keepScreenOn, keepAwake) {
    view.keepScreenOn = controls.keepScreenOn || keepAwake
    onDispose { view.keepScreenOn = false }
  }

  DisposableEffect(activity, controls.orientation) {
    activity?.requestedOrientation = when (controls.orientation) {
      ReaderOrientation.Auto -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      ReaderOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      // Either landscape, so turning the phone over still works.
      ReaderOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
  }

  val host = activity as? ReaderKeyHost
  DisposableEffect(host, session, controls.volumeKeys, overlaysOpen) {
    val listener: ((KeyEvent) -> Boolean)? = if (!controls.volumeKeys) null else { event ->
      when (volumeKeyFor(event.keyCode, event.action, event.repeatCount, overlaysOpen)) {
        VolumeKey.Ignore -> false
        VolumeKey.Consume -> true
        VolumeKey.Forward -> { if (!session.goForward()) reader.toast("End of book"); true }
        VolumeKey.Backward -> { if (!session.goBackward()) reader.toast("Start of book"); true }
      }
    }
    host?.readerKeys = listener
    onDispose { if (host?.readerKeys === listener) host?.readerKeys = null }
  }
}

/** Catches taps on a custom margin, outside the page's own view; [onTap] gets x in dp across this strip. */
@Composable
private fun MarginTaps(modifier: Modifier, onTap: (Float) -> Unit) {
  val density = androidx.compose.ui.platform.LocalDensity.current
  Box(modifier.fillMaxHeight().pointerInput(Unit) { detectTapGestures { offset -> onTap(with(density) { offset.x.toDp().value }) } })
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
private fun ReaderChrome(s: ReaderUiState, session: ReaderSession, reader: ReaderState, marked: Boolean, progress: Float) {
  val book = session.book
  val words by reader.wordCount.collectAsStateWithLifecycle()
  var dragging by remember { mutableStateOf<Float?>(null) }
  Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(s.chrome, Modifier.align(Alignment.TopCenter), enter = slideInVertically(tween(250)) { -it }, exit = slideOutVertically(tween(250)) { -it }) {
      Row(
        Modifier.fillMaxWidth().background(Nq.surface).statusBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        IconBtn(Ic.ArrowLeft, reader::leave)
        Column(Modifier.weight(1f)) {
          QText(book.title, 14f, weight = 500, maxLines = 1)
          MetaLine(book.author, words, 11f, Nq.neutral500)
        }
        IconBtn(Ic.Play, reader::toggleAutoScroll, tint = if (s.auto.on) Nq.accent else Nq.text)
        IconBtn(Ic.Search, { reader.setTextSearch(true) })
        IconBtn(if (marked) Ic.BookmarkFill else Ic.Bookmark, reader::toggleBookmark, tint = if (marked) Nq.accent else Nq.text)
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
            Triple(Ic.ListBullets, "Contents") { reader.openSheet(Sheet.Contents, TocTab.Contents) },
            Triple(Ic.TextAa, "Display") { reader.openSheet(Sheet.Display) },
            Triple(Ic.Highlighter, "Highlights") { reader.openSheet(Sheet.Contents, TocTab.Highlights) },
            Triple(Ic.Headphones, "Listen") { reader.setChrome(false); reader.startReadAloud() },
            Triple(Ic.Search, "Search") { reader.setTextSearch(true) },
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

/** Actions for a highlight the reader just tapped: its colour, and what to do with it. */
@Composable
private fun HighlightActions(s: ReaderUiState, highlights: List<HighlightEntity>, reader: ReaderState, navBottom: Dp) {
  val id = s.activeHighlight
  val color = highlights.firstOrNull { it.id == id }?.color ?: 0
  Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(id != null, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = navBottom + 56.dp), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
      val shape = RoundedCornerShape(14.dp)
      Column(Modifier.fillMaxWidth().shadow(16.dp, shape).background(Nq.surface, shape).padding(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
          HighlightColor.entries.forEach { c -> ColorDot(c, selected = c.ordinal == HighlightColor.fromIndex(color).ordinal) { id?.let { reader.setHighlightColor(it, c.ordinal) } } }
        }
        Row(Modifier.fillMaxWidth()) {
          listOf(
            Triple(Ic.NotePencil, "Note", Nq.text) to { id?.let { reader.editNote(it) } },
            Triple(Ic.Copy, "Copy", Nq.text) to { id?.let { reader.copyHighlight(it) } },
            Triple(Ic.ShareNetwork, "Share", Nq.text) to { id?.let { reader.shareHighlight(it) } },
            Triple(Ic.X, "Remove", Nq.accent) to { id?.let { reader.deleteHighlight(it) } },
          ).forEach { (spec, act) ->
            val (icon, label, tint) = spec
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = { act() }).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Ph(icon, 20.dp, tint)
              QText(label, 11f, color = tint)
            }
          }
        }
      }
    }
  }
}

/** Play and pause, sentence by sentence, speed, voice and stop for read aloud, over the foot of the page. */
@Composable
private fun ReadAloudBar(ui: ReadAloudUi, reader: ReaderState, modifier: Modifier) {
  AnimatedVisibility(ui.active, modifier, enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 2 }, exit = fadeOut(tween(150))) {
    val shape = RoundedCornerShape(14.dp)
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(READ_ALOUD_BAR_SPACE - 4.dp).shadow(8.dp, shape).background(Nq.surface, shape).padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
      IconBtn(Ic.SkipBack, reader::readAloudPrevious)
      IconBtn(if (ui.status == ReadAloudStatus.Paused) Ic.Play else Ic.Pause, reader::toggleReadAloud, size = 44.dp, iconSize = 24.dp, tint = Nq.accent)
      IconBtn(Ic.SkipForward, reader::readAloudNext)
      QText(
        speedLabel(ui.speed), 12.5f, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = reader::cycleReadAloudSpeed).padding(horizontal = 10.dp, vertical = 8.dp),
        color = Nq.neutral200, tabular = true,
      )
      IconBtn(Ic.Speaker, { reader.openSheet(Sheet.Voices) })
      IconBtn(Ic.X, { reader.stopReadAloud() })
    }
  }
}

/** Auto-scroll's controls, where the read-aloud bar sits: play or pause, slower, the speed, faster, and stop. */
@Composable
private fun AutoScrollBar(state: AutoScrollState, speeds: com.quire.reader.data.AutoScrollPrefs, paged: Boolean, reader: ReaderState, modifier: Modifier) {
  AnimatedVisibility(state.on, modifier, enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 2 }, exit = fadeOut(tween(150))) {
    val shape = RoundedCornerShape(14.dp)
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(READ_ALOUD_BAR_SPACE - 4.dp).shadow(8.dp, shape).background(Nq.surface, shape).padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
      // Paused by hand, it stays paused until play is tapped.
      IconBtn(if (AutoPause.Manual in state.pauses) Ic.Play else Ic.Pause, { reader.pauseAuto(AutoPause.Manual, AutoPause.Manual !in state.pauses) }, size = 44.dp, iconSize = 24.dp, tint = Nq.accent)
      QText("−", 22f, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = reader::autoSlower).padding(horizontal = 14.dp, vertical = 4.dp), color = Nq.neutral200)
      QText(AutoScrollSpeed.label(speeds, paged), 12.5f, color = Nq.neutral200, tabular = true)
      QText("+", 22f, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = reader::autoFaster).padding(horizontal = 14.dp, vertical = 4.dp), color = Nq.neutral200)
      IconBtn(Ic.X, reader::stopAutoScroll)
    }
  }
}

/** "Back to page 34": a small pill over the foot of the page after following a link inside the book. */
@Composable
private fun ReturnChip(label: String, onClick: () -> Unit, modifier: Modifier) {
  val shape = RoundedCornerShape(50)
  Row(
    modifier.shadow(6.dp, shape).background(Nq.surface, shape).clip(shape).clickable(onClick = onClick)
      .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Ph(Ic.ArrowLeft, 14.dp, Nq.accent200)
    QText(label, 12f, color = Nq.neutral200, tabular = true)
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
