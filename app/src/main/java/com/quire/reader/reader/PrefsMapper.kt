package com.quire.reader.reader

import androidx.compose.ui.graphics.toArgb
import com.quire.reader.data.DirectionPref
import com.quire.reader.data.ImageFilterPref
import com.quire.reader.data.PageLayoutPref
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.SpacingLevel
import com.quire.reader.data.TextAlignPref
import com.quire.reader.data.TriState
import com.quire.reader.data.TypographySource
import com.quire.reader.data.VerticalTextPref
import com.quire.reader.data.WidenLevel
import com.quire.reader.data.WeightLevel
import com.quire.reader.theme.ReaderTheme
import com.quire.reader.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.preferences.Spread
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/** The browser's default text size, which Readium's `fontSize` multiplies. */
private const val BASE_FONT_PX = 16.0

/** Page margin presets (S/M/L) as multiples of Readium's default margin. */
internal fun marginFactor(margin: Int): Double = when {
  margin <= 16 -> 0.7
  margin >= 40 -> 1.6
  else -> 1.0
}

/**
 * The vendored editor's supported ranges and steps for the five advanced range rows, as
 * constants: the mapper snaps semantic levels onto them without building an editor. The walk
 * test reads the real vendored editor and checks it against this table, so the two cannot
 * drift apart.
 */
internal object EditorRanges {
  val fontWeight = Range(0.0, 2.5, 0.25)
  val letterSpacing = Range(0.0, 1.0, 0.1)
  val wordSpacing = Range(0.0, 1.0, 0.1)
  val paragraphIndent = Range(0.0, 3.0, 0.2)
  val paragraphSpacing = Range(0.0, 2.0, 0.1)

  private const val TIE_TOLERANCE = 1e-9

  data class Range(val min: Double, val max: Double, val step: Double) {
    /** [fraction] of the span, snapped to the nearest supported step. */
    fun fraction(fraction: Double): Double = snap(min + (max - min) * fraction)

    /**
     * The nearest supported step for [value], clamped to the range. Ties within
     * floating-point tolerance go to the higher step: a level computed as 50% of a range
     * whose step cannot represent it must not fall downward on a rounding artifact.
     */
    fun snap(value: Double): Double {
      val clamped = value.coerceIn(min, max)
      val units = (clamped - min) / step
      val lower = floor(units)
      val frac = units - lower
      val steps = if (abs(frac - 0.5) <= TIE_TOLERANCE) lower + 1.0 else round(units)
      return clean(min + steps * step).coerceIn(min, max)
    }

    /** Rounds away floating-point dust so submitted values read like the editor's own. */
    private fun clean(value: Double): Double = round(value * 1e6) / 1e6
  }
}

private fun SpacingLevel.rangeValue(range: EditorRanges.Range): Double? = when (this) {
  SpacingLevel.Default -> null
  SpacingLevel.None -> range.min
  SpacingLevel.Small -> range.fraction(0.25)
  SpacingLevel.Medium -> range.fraction(0.50)
  SpacingLevel.Large -> range.fraction(0.75)
}

private fun WidenLevel.rangeValue(range: EditorRanges.Range): Double? = when (this) {
  WidenLevel.Default -> null
  WidenLevel.Slight -> range.fraction(0.25)
  WidenLevel.Wider -> range.fraction(0.50)
  WidenLevel.VeryWide -> range.fraction(0.75)
  WidenLevel.Max -> range.fraction(1.0)
}

private fun WeightLevel.weightValue(): Double? = when (this) {
  WeightLevel.Default -> null
  WeightLevel.Light -> 0.75
  WeightLevel.Regular -> 1.0
  WeightLevel.Medium -> 1.25
  WeightLevel.Heavy -> 1.75
}

private fun TriState.booleanValue(): Boolean? = when (this) {
  TriState.Auto -> null
  TriState.On -> true
  TriState.Off -> false
}

/**
 * Maps the semantic reading preferences onto Readium's, for a publication of [layout]. Pure
 * and complete: every field is set from these preferences alone (never `plus`-ed onto what
 * the navigator holds), so the result is a full replacement. Numbers land on the vendored
 * editor's supported steps; the basic fields keep their exact pre-advanced values.
 */
@OptIn(ExperimentalReadiumApi::class)
fun ReaderPrefs.toEpubPreferences(layout: Layout): EpubPreferences {
  val advanced = advanced
  val reflowable = layout == Layout.REFLOWABLE
  return EpubPreferences(
    theme = when (theme) { ReaderTheme.Sepia -> Theme.SEPIA; ReaderTheme.Paper -> Theme.LIGHT; else -> Theme.DARK },
    backgroundColor = Color(theme.bg.toArgb()),
    textColor = Color(theme.fg.toArgb()),
    fontFamily = FontFamily(ReaderFontList[font.coerceIn(0, ReaderFontList.lastIndex)].name),
    fontSize = fontSize / BASE_FONT_PX,
    lineHeight = lineHeight.toDouble(),
    pageMargins = marginFactor(margin),
    // With publisher styles on, the book's own CSS overrides line spacing, alignment and hyphenation.
    publisherStyles = advanced.typographySource == TypographySource.Book,
    scroll = mode == ReadMode.Scroll,
    textAlign = if (align == TextAlignPref.Justify) TextAlign.JUSTIFY else TextAlign.START,
    hyphens = advanced.hyphens.booleanValue(),
    ligatures = advanced.ligatures.booleanValue(),
    fontWeight = advanced.fontWeight.weightValue(),
    letterSpacing = advanced.letterSpacing.rangeValue(EditorRanges.letterSpacing),
    wordSpacing = advanced.wordSpacing.rangeValue(EditorRanges.wordSpacing),
    paragraphIndent = advanced.paragraphIndent.rangeValue(EditorRanges.paragraphIndent),
    paragraphSpacing = advanced.paragraphSpacing.rangeValue(EditorRanges.paragraphSpacing),
    textNormalization = if (advanced.simplifyTypography) true else null,
    verticalText = when (advanced.verticalText) {
      VerticalTextPref.Automatic -> null
      VerticalTextPref.Horizontal -> false
      VerticalTextPref.Vertical -> true
    },
    readingProgression = when (advanced.readingDirection) {
      DirectionPref.Automatic -> null
      DirectionPref.LeftToRight -> ReadingProgression.LTR
      DirectionPref.RightToLeft -> ReadingProgression.RTL
    },
    imageFilter = when (advanced.imageFilter) {
      ImageFilterPref.Original -> null
      ImageFilterPref.Darken -> ImageFilter.DARKEN
      ImageFilterPref.Invert -> ImageFilter.INVERT
    },
    // One page-layout choice maps per layout; the other preference stays unset.
    columnCount = when {
      reflowable && advanced.pageLayout == PageLayoutPref.Single -> ColumnCount.ONE
      reflowable && advanced.pageLayout == PageLayoutPref.Two -> ColumnCount.TWO
      else -> null
    },
    spread = when {
      !reflowable && advanced.pageLayout == PageLayoutPref.Single -> org.readium.r2.navigator.preferences.Spread.NEVER
      !reflowable && advanced.pageLayout == PageLayoutPref.Two -> org.readium.r2.navigator.preferences.Spread.ALWAYS
      else -> null
    },
  )
}
