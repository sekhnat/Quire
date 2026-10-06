package com.quire.reader.ui.library

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quire.reader.theme.QuireFonts
import kotlin.math.ceil

/** One row of the tag cloud: the tags that fit side by side at the current width. */
internal data class TagLine(val tags: List<Pair<String, Int>>)

/** A tag chip's sizes in pixels, read from the composition so the background measure matches what Compose draws. */
internal class TagChipMetrics(val typeface: Typeface?, val namePx: Float, val countPx: Float, val padPx: Int, val innerGapPx: Int, val gapPx: Int)

/** Must match the `Tag(...)` call in `TagsList`: 13sp name, 11sp count, 12dp side padding, 6dp inside, 8dp between chips. */
@Composable
internal fun rememberTagChipMetrics(): TagChipMetrics {
  val density = LocalDensity.current
  val fonts = LocalFontFamilyResolver.current
  return remember(density, fonts) {
    with(density) {
      TagChipMetrics(fonts.resolve(QuireFonts.Inter, FontWeight(400)).value as? Typeface, 13.sp.toPx(), 11.sp.toPx(), 12.dp.roundToPx(), 6.dp.roundToPx(), 8.dp.roundToPx())
    }
  }
}

/**
 * Splits chips of the given widths into lines no wider than [available], filling each line in order as FlowRow does:
 * [gap] sits between chips, not after the last one, and a chip wider than a line gets a line to itself.
 */
internal fun packLines(widths: IntArray, available: Int, gap: Int): List<IntRange> {
  val lines = ArrayList<IntRange>()
  var start = 0
  var used = 0
  for (i in widths.indices) {
    when {
      i == start -> used = widths[i]
      used + gap + widths[i] > available -> { lines += start until i; start = i; used = widths[i] }
      else -> used += gap + widths[i]
    }
  }
  if (widths.isNotEmpty()) lines += start until widths.size
  return lines
}

/**
 * The width of each tag's chip (name, count), measured with a plain [Paint] rather than Compose text layout so it can
 * run off the main thread. Rounds text up the way Compose sizes a `Text`.
 */
internal fun tagChipWidths(tags: List<Pair<String, Int>>, m: TagChipMetrics): IntArray {
  val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = m.typeface }
  fun width(text: String, px: Float): Int { paint.textSize = px; return ceil(paint.measureText(text)).toInt() }
  return IntArray(tags.size) { i ->
    val (name, n) = tags[i]
    2 * m.padPx + width(name, m.namePx) + m.innerGapPx + width(n.toString(), m.countPx)
  }
}

/** Lays [tags] out as chip lines [available] pixels wide. */
internal fun tagLines(tags: List<Pair<String, Int>>, available: Int, m: TagChipMetrics): List<TagLine> =
  packLines(tagChipWidths(tags, m), available, m.gapPx).map { TagLine(tags.subList(it.first, it.last + 1)) }
