package com.quire.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quire.reader.R
import com.quire.reader.data.Book
import com.quire.reader.data.Ground
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ── icons ───────────────────────────────────────────────────────────────────

/** Phosphor icons, converted to vector drawables (regular weight unless the name says otherwise). */
object Ic {
  val BookOpen = R.drawable.ph_book_open
  val FolderOpen = R.drawable.ph_folder_open
  val ArrowLeft = R.drawable.ph_arrow_left
  val ArrowRight = R.drawable.ph_arrow_right
  val ArrowUp = R.drawable.ph_arrow_up
  val ArrowDown = R.drawable.ph_arrow_down
  val Plus = R.drawable.ph_plus
  val Search = R.drawable.ph_magnifying_glass
  val CircleNotch = R.drawable.ph_circle_notch
  val Circle = R.drawable.ph_circle
  val CheckCircleFill = R.drawable.ph_check_circle_fill
  val CheckCircle = R.drawable.ph_check_circle
  val X = R.drawable.ph_x
  val XCircle = R.drawable.ph_x_circle
  val ArrowsDownUp = R.drawable.ph_arrows_down_up
  val CaretDown = R.drawable.ph_caret_down
  val CaretLeft = R.drawable.ph_caret_left
  val CaretRight = R.drawable.ph_caret_right
  val CaretLineLeft = R.drawable.ph_caret_line_left
  val CaretLineRight = R.drawable.ph_caret_line_right
  val Books = R.drawable.ph_books
  val Sparkle = R.drawable.ph_sparkle
  val CircleDashed = R.drawable.ph_circle_dashed
  val Star = R.drawable.ph_star
  val StarFill = R.drawable.ph_star_fill
  val Pencil = R.drawable.ph_pencil_simple
  val MoreVertical = R.drawable.ph_dots_three_vertical
  val FolderPlus = R.drawable.ph_folder_plus
  val FolderSimple = R.drawable.ph_folder_simple
  val FolderSimplePlus = R.drawable.ph_folder_simple_plus
  val Refresh = R.drawable.ph_arrows_clockwise
  val FileDown = R.drawable.ph_file_arrow_down
  val SortAsc = R.drawable.ph_sort_ascending
  val SortDesc = R.drawable.ph_sort_descending
  val Grid = R.drawable.ph_squares_four
  val ListDashes = R.drawable.ph_list_dashes
  val Rows = R.drawable.ph_rows
  val Bookmark = R.drawable.ph_bookmark_simple
  val BookmarkFill = R.drawable.ph_bookmark_simple_fill
  val ListBullets = R.drawable.ph_list_bullets
  val List = R.drawable.ph_list
  val TextAa = R.drawable.ph_text_aa
  val Highlighter = R.drawable.ph_highlighter
  val SunDim = R.drawable.ph_sun_dim
  val Sun = R.drawable.ph_sun
  val HandTap = R.drawable.ph_hand_tap
  val BookOpenText = R.drawable.ph_book_open_text
  val Scroll = R.drawable.ph_scroll
  val AlignLeft = R.drawable.ph_text_align_left
  val AlignJustify = R.drawable.ph_text_align_justify
  val NotePencil = R.drawable.ph_note_pencil
  val Copy = R.drawable.ph_copy
  val BookBookmark = R.drawable.ph_book_bookmark
  val CheckBold = R.drawable.ph_check_bold
}

@Composable
fun Ph(icon: Int, size: Dp = 20.dp, tint: Color = Nq.text, modifier: Modifier = Modifier) {
  Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

// ── text ────────────────────────────────────────────────────────────────────

/** Text sized in sp with letter-spacing and line-height given in em, as the design specifies them. */
@Composable
fun QText(
  text: String,
  size: Float,
  modifier: Modifier = Modifier,
  color: Color = Nq.text,
  weight: Int = 400,
  family: FontFamily = QuireFonts.Inter,
  ls: Float = 0f,
  lh: Float = 0f,
  upper: Boolean = false,
  maxLines: Int = Int.MAX_VALUE,
  align: TextAlign? = null,
  tabular: Boolean = false,
  balance: Boolean = false,
) {
  Text(
    text = if (upper) text.uppercase() else text,
    modifier = modifier,
    color = color,
    maxLines = maxLines,
    overflow = if (maxLines == 1 || maxLines != Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip,
    style = TextStyle(
      fontFamily = family,
      fontSize = size.sp,
      fontWeight = FontWeight(weight),
      letterSpacing = (ls * size).sp,
      lineHeight = if (lh > 0f) (lh * size).sp else TextStyle.Default.lineHeight,
      textAlign = align ?: TextAlign.Unspecified,
      fontFeatureSettings = if (tabular) "tnum" else null,
      lineBreak = if (balance) LineBreak.Heading else LineBreak.Paragraph,
    ),
  )
}

/** The small, uppercase, letter-spaced label used throughout the design. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = Nq.neutral500) =
  QText(text, 10f, modifier, color, ls = 0.12f, upper = true)

// ── surfaces ────────────────────────────────────────────────────────────────

/** CSS `linear-gradient(<angle>deg, ...)` — sized to the box it paints, like CSS does. */
fun cssGradient(angleDeg: Float, from: Color, to: Color): Brush = object : ShaderBrush() {
  override fun createShader(size: Size): Shader {
    val a = Math.toRadians(angleDeg.toDouble())
    val dx = sin(a).toFloat()
    val dy = -cos(a).toFloat()
    val len = abs(size.width * dx) + abs(size.height * dy)
    val c = Offset(size.width / 2, size.height / 2)
    val half = Offset(dx * len / 2, dy * len / 2)
    return LinearGradientShader(c - half, c + half, listOf(from, to))
  }
}

fun Ground.brush(): Brush = cssGradient(160f, from, to)

/** `radial-gradient(<rx>px <ry>px at 0% 0%, color, transparent 70%)` — an elliptical glow from the top-left. */
fun Modifier.cornerGlow(rx: Dp, ry: Dp, color: Color, stop: Float = 0.7f): Modifier = drawBehind {
  val rxPx = rx.toPx()
  val ryPx = ry.toPx()
  scale(1f, ryPx / rxPx, pivot = Offset.Zero) {
    drawRect(
      brush = Brush.radialGradient(0f to color, stop to color.copy(alpha = 0f), center = Offset.Zero, radius = rxPx),
      size = Size(size.width, size.height * rxPx / ryPx),
    )
  }
}

/** The 2px reading-progress line used on covers and cards. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, track: Color = Nq.neutral800, fill: Color = Nq.accent) {
  Box(modifier) {
    Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
      Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(fill))
    }
  }
}

// ── buttons ─────────────────────────────────────────────────────────────────

enum class BtnKind { Primary, Secondary, Ghost }

@Composable
fun QButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  kind: BtnKind = BtnKind.Secondary,
  icon: Int? = null,
  trailingIcon: Int? = null,
  size: Float = 14f,
  height: Dp = 36.dp,
  enabled: Boolean = true,
  color: Color? = null,
) {
  val shape = RoundedCornerShape(8.dp)
  val fg = color ?: if (kind == BtnKind.Secondary) Nq.text else Nq.accent
  val border = when (kind) {
    BtnKind.Primary -> Nq.accent
    BtnKind.Secondary -> Nq.divider
    BtnKind.Ghost -> Color.Transparent
  }
  Row(
    modifier
      .alpha(if (enabled) 1f else 0.45f)
      .height(height)
      .clip(shape)
      .border(BorderStroke(1.dp, border), shape)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = if (kind == BtnKind.Ghost) 6.dp else 10.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) Ph(icon, (size + 4).dp, fg)
    QText(text, size, color = fg, weight = 500)
    if (trailingIcon != null) Ph(trailingIcon, (size + 4).dp, fg)
  }
}

@Composable
fun IconBtn(icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Nq.text, size: Dp = 36.dp, iconSize: Dp = 20.dp, bordered: Boolean = false) {
  val shape = RoundedCornerShape(8.dp)
  Box(
    modifier.size(size).clip(shape).then(if (bordered) Modifier.border(1.dp, Nq.divider, shape) else Modifier).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) { Ph(icon, iconSize, tint) }
}

@Composable
fun Tag(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, outline: Boolean = false, accent: Boolean = false, size: Float = 12f, count: String? = null, icon: Int? = null, hPad: Dp = 10.dp, vPad: Dp = 5.dp) {
  val shape = RoundedCornerShape(6.dp)
  val bg = when { outline -> Color.Transparent; accent -> Nq.accent800; else -> Nq.neutral800 }
  val fg = when { outline -> Nq.accent; accent -> Nq.accent100; else -> Nq.neutral100 }
  Row(
    modifier.clip(shape).background(bg).then(if (outline) Modifier.border(1.dp, Nq.accent, shape) else Modifier).clickable(onClick = onClick).padding(horizontal = hPad, vertical = vPad),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null && !accent) Ph(icon, (size + 1).dp, fg)
    QText(text, size, color = fg)
    if (count != null) QText(count, 11f, color = Nq.neutral400)
    if (icon != null && accent) Ph(icon, (size + 1).dp, fg)
  }
}

/** Bordered pair/trio of options (sort direction, reading mode, margins…). `selected` gets an accent ring. */
@Composable
fun Segmented(options: List<SegOption>, modifier: Modifier = Modifier, height: Dp = 34.dp, minWidth: Dp = 46.dp, size: Float = 12.5f) {
  val shape = RoundedCornerShape(8.dp)
  Row(modifier.clip(shape).border(1.dp, Nq.neutral800, shape)) {
    options.forEach { o ->
      Row(
        Modifier
          .height(height).defaultMinSize(minWidth = minWidth)
          .then(if (o.selected) Modifier.border(1.dp, Nq.accent, androidx.compose.ui.graphics.RectangleShape) else Modifier)
          .clickable(onClick = o.onClick)
          .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val tint = if (o.selected) Nq.accent else Nq.neutral300
        if (o.icon != null) Ph(o.icon, 16.dp, tint)
        if (o.label.isNotEmpty()) QText(o.label, size, color = tint)
      }
    }
  }
}

data class SegOption(val label: String, val selected: Boolean, val onClick: () -> Unit, val icon: Int? = null)

@Composable
fun Toggle(on: Boolean, onClick: () -> Unit) {
  val x by animateDpAsState(if (on) 16.dp else 0.dp, tween(200), label = "knob")
  Box(
    Modifier.size(width = 38.dp, height = 22.dp).clip(CircleShape).border(1.dp, if (on) Nq.accent else Nq.neutral600, CircleShape).clickable(onClick = onClick),
  ) {
    Box(Modifier.padding(start = 3.dp).align(Alignment.CenterStart).offset(x = x).size(14.dp).clip(CircleShape).background(if (on) Nq.accent else Nq.neutral500))
  }
}

// ── sheets ──────────────────────────────────────────────────────────────────

private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/** A dimmed backdrop plus a surface sheet that slides up from the bottom edge. */
@Composable
fun SheetHost(
  visible: Boolean,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  backdrop: Float = 0.55f,
  content: @Composable ColumnScope.() -> Unit,
) {
  Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
      Box(Modifier.fillMaxSize().background(Nq.scrim.copy(alpha = backdrop)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(
      visible,
      Modifier.align(Alignment.BottomCenter),
      enter = slideInVertically(tween(300)) { it },
      exit = slideOutVertically(tween(300)) { it },
    ) {
      Column(
        Modifier.fillMaxWidth().shadow(16.dp, SheetShape).clip(SheetShape).background(Nq.surface).border(1.dp, Nq.neutral800, SheetShape).navigationBarsPadding().then(modifier),
      ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(4.dp)).background(Nq.neutral700))
        content()
      }
    }
  }
}

@Composable
fun Toast(text: String?, modifier: Modifier = Modifier) {
  AnimatedVisibility(text != null, modifier, enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { 8 }, exit = fadeOut(tween(150))) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.widthIn(max = 300.dp).shadow(8.dp, shape).clip(shape).background(Nq.neutral900).padding(horizontal = 14.dp, vertical = 10.dp)) {
      QText(text ?: "", 12.5f, color = Nq.neutral100, lh = 1.4f, align = TextAlign.Center)
    }
  }
}

/** Lets a child run edge to edge inside a padded parent (the CSS `margin: 0 -20px` trick). */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
  val extra = horizontal.roundToPx() * 2
  val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
  layout(constraints.maxWidth, placeable.height) { placeable.place(-horizontal.roundToPx(), 0) }
}

/** The design system's `.input`: surface fill, divider border, accent caret. */
@Composable
fun QTextField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier = Modifier,
  leadingIcon: Int? = null,
  onClear: (() -> Unit)? = null,
  focusRequester: FocusRequester? = null,
  onSubmit: (() -> Unit)? = null,
  singleLine: Boolean = true,
  minLines: Int = 1,
) {
  val shape = RoundedCornerShape(8.dp)
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = singleLine,
    minLines = if (singleLine) 1 else minLines,
    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSubmit?.invoke() }),
    textStyle = TextStyle(fontFamily = QuireFonts.Inter, fontSize = 14.sp, color = Nq.text),
    cursorBrush = SolidColor(Nq.accent),
    modifier = modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    decorationBox = { inner ->
      Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp).clip(shape).background(Nq.surface).border(1.dp, Nq.divider, shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (leadingIcon != null) Ph(leadingIcon, 16.dp, Nq.neutral500)
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
          if (value.isEmpty()) QText(placeholder, 14f, color = Nq.neutral500)
          inner()
        }
        if (onClear != null && value.isNotEmpty()) Box(Modifier.clip(CircleShape).clickable(onClick = onClear)) { Ph(Ic.XCircle, 16.dp, Nq.neutral500) }
      }
    },
  )
}

/** Underlined text tabs (library views, contents sheet). */
@Composable
fun TabRow2(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, fill: Boolean = false, horizontalPadding: Dp = 10.dp) {
  Row(modifier.fillMaxWidth().drawBehind {
    drawRect(Nq.neutral900, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
  }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    labels.forEachIndexed { i, label ->
      val on = i == selected
      Box(
        Modifier.then(if (fill) Modifier.weight(1f) else Modifier).clickable { onSelect(i) }.drawBehind {
          if (on) drawRect(Nq.accent, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
      ) {
        QText(label, 13f, Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp).padding(bottom = 2.dp), color = if (on) Nq.text else Nq.neutral500)
      }
    }
  }
}

/** A dashed 1dp outline (used for "add" affordances and the tap-zone preview). */
fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.dp): Modifier = drawBehind {
  val w = width.toPx()
  drawRoundRect(
    color = color,
    topLeft = Offset(w / 2, w / 2),
    size = Size(size.width - w, size.height - w),
    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
    style = androidx.compose.ui.graphics.drawscope.Stroke(w, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
  )
}

/** A flat accent range input (the design's `input[type=range]`), without Material's gapped track. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  valueRange: ClosedFloatingPointRange<Float>,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onValueChangeFinished: (() -> Unit)? = null,
) {
  val colors = androidx.compose.material3.SliderDefaults.colors(
    thumbColor = Nq.accent, activeTrackColor = Nq.accent, inactiveTrackColor = Nq.neutral800,
    disabledThumbColor = Nq.neutral700, disabledActiveTrackColor = Nq.neutral700, disabledInactiveTrackColor = Nq.neutral800,
  )
  val interaction = remember { MutableInteractionSource() }
  androidx.compose.material3.Slider(
    value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled, modifier = modifier, colors = colors, interactionSource = interaction, onValueChangeFinished = onValueChangeFinished,
    thumb = { Box(Modifier.size(18.dp).clip(CircleShape).background(if (enabled) Nq.accent else Nq.neutral700).border(2.dp, Nq.surface, CircleShape)) },
    track = { st ->
      androidx.compose.material3.SliderDefaults.Track(st, Modifier.height(4.dp), colors = colors, enabled = enabled, drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp)
    },
  )
}
