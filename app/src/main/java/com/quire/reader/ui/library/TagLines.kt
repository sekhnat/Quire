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
import com.quire.reader.data.TagKind
import com.quire.reader.theme.QuireFonts
import com.quire.reader.ui.parseTagQuery
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

/**
 * The [tags] whose name contains [query] (trimmed, ignoring case), in their order; all of them for a blank query. The
 * same rule as the hidden-tags sheet's search.
 */
internal fun matchingTags(tags: List<Pair<String, Int>>, query: String, kinds: Map<String, TagKind> = emptyMap()): List<Pair<String, Int>> {
  val typed = parseTagQuery(query)
  val q = typed.text.lowercase()
  if (q.isEmpty() && typed.terms.isEmpty()) return tags
  return tags.filter { (tag, _) ->
    (q.isEmpty() || tag.lowercase().contains(q)) && (typed.terms.isEmpty() || typed.terms.any { it.matches(tag, kinds[tag]) })
  }
}

/** A heading of the Tags tab and the tags under it; [kind] is null for the plain tags. */
internal data class TagSection(val kind: TagKind?, val tags: List<Pair<String, Int>>) {
  val title: String get() = kind?.plural ?: "Other tags"
}

/**
 * [tags] grouped by kind in [TagKind.displayOrder], plain tags last, each keeping the order it had; empty groups are
 * left out. A library without typed tags gets one section of plain tags.
 */
internal fun tagSections(tags: List<Pair<String, Int>>, kinds: Map<String, TagKind>): List<TagSection> {
  if (kinds.isEmpty()) return if (tags.isEmpty()) emptyList() else listOf(TagSection(null, tags))
  val grouped = tags.groupBy { kinds[it.first] }
  return (TagKind.displayOrder + listOf(null)).mapNotNull { k -> grouped[k]?.let { TagSection(k, it) } }
}

/** Lays [tags] out as chip lines [available] pixels wide. */
internal fun tagLines(tags: List<Pair<String, Int>>, available: Int, m: TagChipMetrics): List<TagLine> =
  packLines(tagChipWidths(tags, m), available, m.gapPx).map { TagLine(tags.subList(it.first, it.last + 1)) }
