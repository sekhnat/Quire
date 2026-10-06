package com.quire.reader.reader

import androidx.compose.ui.graphics.toArgb
import com.quire.reader.data.AdvancedReaderPrefs
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
import com.quire.reader.navigator.epub.EpubDefaults
import com.quire.reader.navigator.epub.EpubPreferences
import com.quire.reader.navigator.epub.EpubPreferencesEditor
import com.quire.reader.theme.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.navigator.preferences.RangePreference
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Metadata

/**
 * The mapper's contract: the factory mapping is byte-for-byte what the pre-advanced mapper
 * produced (frozen here as literals, not by calling the mapper twice), every semantic level
 * lands on the vendored editor's supported steps, and the constants table never drifts from
 * the vendored editor's own ranges.
 */
class PrefsMapperTest {

  // ── frozen legacy golden ────────────────────────────────────────────────────

  @Test fun `the factory mapping equals the pre-advanced mapping`() {
    val expected = EpubPreferences(
      theme = Theme.DARK,
      backgroundColor = org.readium.r2.navigator.preferences.Color(ReaderTheme.Night.bg.toArgb()),
      textColor = org.readium.r2.navigator.preferences.Color(ReaderTheme.Night.fg.toArgb()),
      fontFamily = org.readium.r2.navigator.preferences.FontFamily("Literata"),
      fontSize = 19 / 16.0,
      lineHeight = 1.6f.toDouble(),
      pageMargins = 1.0,
      publisherStyles = false,
      scroll = false,
      textAlign = TextAlign.JUSTIFY,
      hyphens = true,
    )
    assertEquals(expected, ReaderPrefs().toEpubPreferences(Layout.REFLOWABLE))
    // The fixed layout takes the same basic mapping; only the page layout differs, and
    // Auto leaves both navigator preferences unset.
    assertEquals(expected, ReaderPrefs().toEpubPreferences(Layout.FIXED))
  }

  @Test fun `a customized basic set maps exactly as it did before the advanced controls`() {
    val prefs = ReaderPrefs(
      theme = ReaderTheme.Sepia, font = 2, fontSize = 25, lineHeight = 1.85f, margin = 40,
      align = TextAlignPref.Left, mode = ReadMode.Scroll,
    )
    val expected = EpubPreferences(
      theme = Theme.SEPIA,
      backgroundColor = org.readium.r2.navigator.preferences.Color(ReaderTheme.Sepia.bg.toArgb()),
      textColor = org.readium.r2.navigator.preferences.Color(ReaderTheme.Sepia.fg.toArgb()),
      fontFamily = org.readium.r2.navigator.preferences.FontFamily("Atkinson"),
      fontSize = 25 / 16.0,
      lineHeight = 1.85f.toDouble(),
      pageMargins = 1.6,
      publisherStyles = false,
      scroll = true,
      textAlign = TextAlign.START,
      hyphens = true,
    )
    assertEquals(expected, prefs.toEpubPreferences(Layout.REFLOWABLE))
  }

  // ── constants table against the vendored editor ─────────────────────────────

  @Test fun `the constants table matches the vendored editor's ranges and steps`() {
    val editor = reflowableEditor()
    checkRange(editor.fontWeight, EditorRanges.fontWeight)
    checkRange(editor.letterSpacing, EditorRanges.letterSpacing)
    checkRange(editor.wordSpacing, EditorRanges.wordSpacing)
    checkRange(editor.paragraphIndent, EditorRanges.paragraphIndent)
    checkRange(editor.paragraphSpacing, EditorRanges.paragraphSpacing)
  }

  private fun reflowableEditor(): EpubPreferencesEditor = EpubPreferencesEditor(
    initialPreferences = EpubPreferences(),
    publicationMetadata = Metadata(identifier = "test", localizedTitle = LocalizedString("Walk"), layout = Layout.REFLOWABLE),
    layout = Layout.REFLOWABLE,
    defaults = EpubDefaults(),
  )

  private fun checkRange(preference: RangePreference<Double>, range: EditorRanges.Range) {
    assertEquals(range.min..range.max, preference.supportedRange)
    // The step is read behaviourally: one increment from the range's minimum.
    preference.set(range.min)
    preference.increment()
    assertEquals(range.min + range.step, preference.value!!, 1e-9)
  }

  // ── semantic mappings ───────────────────────────────────────────────────────

  @Test fun `every advanced default maps to the navigator's own default`() {
    val mapped = ReaderPrefs().toEpubPreferences(Layout.REFLOWABLE)
    assertNull(mapped.fontWeight)
    assertNull(mapped.letterSpacing)
    assertNull(mapped.wordSpacing)
    assertNull(mapped.paragraphIndent)
    assertNull(mapped.paragraphSpacing)
    assertNull(mapped.textNormalization)
    assertNull(mapped.verticalText)
    assertNull(mapped.readingProgression)
    assertNull(mapped.imageFilter)
    assertNull(mapped.columnCount)
    assertNull(mapped.spread)
    assertNull(mapped.ligatures)
  }

  @Test fun `spacing and widening levels map onto the supported steps`() {
    fun mapped(indent: SpacingLevel, spacing: SpacingLevel, letter: WidenLevel, word: WidenLevel): EpubPreferences =
      ReaderPrefs(advanced = AdvancedReaderPrefs(
        paragraphIndent = indent, paragraphSpacing = spacing, letterSpacing = letter, wordSpacing = word,
      )).toEpubPreferences(Layout.REFLOWABLE)

    val none = mapped(SpacingLevel.None, SpacingLevel.None, WidenLevel.Default, WidenLevel.Default)
    assertEquals(0.0, none.paragraphIndent!!, 0.0)
    assertEquals(0.0, none.paragraphSpacing!!, 0.0)

    // 25/50/75% of each range, snapped to the nearest step with ties going upward.
    val small = mapped(SpacingLevel.Small, SpacingLevel.Small, WidenLevel.Slight, WidenLevel.Default)
    assertEquals(0.8, small.paragraphIndent!!, 0.0)   // 25% of 0..3 = 0.75 → 0.8
    assertEquals(0.5, small.paragraphSpacing!!, 0.0)  // 25% of 0..2 = 0.5
    assertEquals(0.3, small.letterSpacing!!, 0.0)     // 25% of 0..1 = 0.25 → 0.3

    val medium = mapped(SpacingLevel.Medium, SpacingLevel.Medium, WidenLevel.Wider, WidenLevel.Wider)
    assertEquals(1.6, medium.paragraphIndent!!, 0.0)  // 50% of 0..3 = 1.5 → 1.6
    assertEquals(1.0, medium.paragraphSpacing!!, 0.0)
    assertEquals(0.5, medium.letterSpacing!!, 0.0)
    assertEquals(0.5, medium.wordSpacing!!, 0.0)

    val large = mapped(SpacingLevel.Large, SpacingLevel.Large, WidenLevel.VeryWide, WidenLevel.VeryWide)
    assertEquals(2.2, large.paragraphIndent!!, 0.0)   // 75% of 0..3 = 2.25 → 2.2
    assertEquals(1.5, large.paragraphSpacing!!, 0.0)
    assertEquals(0.8, large.letterSpacing!!, 0.0)     // 75% of 0..1 = 0.75 → 0.8
    assertEquals(0.8, large.wordSpacing!!, 0.0)

    val max = mapped(SpacingLevel.Default, SpacingLevel.Default, WidenLevel.Max, WidenLevel.Max)
    assertNull(max.paragraphIndent)
    assertNull(max.paragraphSpacing)
    assertEquals(1.0, max.letterSpacing!!, 0.0)
    assertEquals(1.0, max.wordSpacing!!, 0.0)
  }

  @Test fun `weight levels map to the fixed steps`() {
    for (pair in listOf(
      WeightLevel.Light to 0.75, WeightLevel.Regular to 1.0, WeightLevel.Medium to 1.25, WeightLevel.Heavy to 1.75,
    )) {
      val mapped = ReaderPrefs(advanced = AdvancedReaderPrefs(fontWeight = pair.first)).toEpubPreferences(Layout.REFLOWABLE)
      assertEquals(pair.second, mapped.fontWeight!!, 0.0)
    }
    assertNull(ReaderPrefs().toEpubPreferences(Layout.REFLOWABLE).fontWeight)
  }

  @Test fun `switches and choices map to their navigator values`() {
    val prefs = ReaderPrefs(advanced = AdvancedReaderPrefs(
      typographySource = TypographySource.Book,
      hyphens = TriState.Off,
      ligatures = TriState.On,
      verticalText = VerticalTextPref.Vertical,
      simplifyTypography = true,
      readingDirection = DirectionPref.RightToLeft,
      imageFilter = ImageFilterPref.Invert,
    ))
    val mapped = prefs.toEpubPreferences(Layout.REFLOWABLE)
    assertTrue(mapped.publisherStyles!!)
    assertEquals(false, mapped.hyphens!!)
    assertEquals(true, mapped.ligatures!!)
    assertEquals(true, mapped.verticalText!!)
    assertEquals(true, mapped.textNormalization!!)
    assertEquals(ReadingProgression.RTL, mapped.readingProgression)
    assertEquals(ImageFilter.INVERT, mapped.imageFilter)

    val on = ReaderPrefs(advanced = AdvancedReaderPrefs(hyphens = TriState.On, verticalText = VerticalTextPref.Horizontal, readingDirection = DirectionPref.LeftToRight, imageFilter = ImageFilterPref.Darken))
    val mappedOn = on.toEpubPreferences(Layout.REFLOWABLE)
    assertEquals(true, mappedOn.hyphens!!)
    assertEquals(false, mappedOn.verticalText!!)
    assertEquals(ReadingProgression.LTR, mappedOn.readingProgression)
    assertEquals(ImageFilter.DARKEN, mappedOn.imageFilter)
  }

  @Test fun `the page layout maps per book layout and leaves the other preference unset`() {
    fun mapped(layout: Layout, pageLayout: PageLayoutPref) =
      ReaderPrefs(advanced = AdvancedReaderPrefs(pageLayout = pageLayout)).toEpubPreferences(layout)

    assertEquals(ColumnCount.ONE, mapped(Layout.REFLOWABLE, PageLayoutPref.Single).columnCount)
    assertEquals(ColumnCount.TWO, mapped(Layout.REFLOWABLE, PageLayoutPref.Two).columnCount)
    assertNull(mapped(Layout.REFLOWABLE, PageLayoutPref.Single).spread)
    assertNull(mapped(Layout.REFLOWABLE, PageLayoutPref.Auto).columnCount)

    assertEquals(Spread.NEVER, mapped(Layout.FIXED, PageLayoutPref.Single).spread)
    assertEquals(Spread.ALWAYS, mapped(Layout.FIXED, PageLayoutPref.Two).spread)
    assertNull(mapped(Layout.FIXED, PageLayoutPref.Two).columnCount)
    assertNull(mapped(Layout.FIXED, PageLayoutPref.Auto).spread)
  }

  // ── snapping ────────────────────────────────────────────────────────────────

  @Test fun `snapping clamps to the range and breaks ties upward`() {
    val indent = EditorRanges.paragraphIndent
    assertEquals(0.0, indent.snap(-1.0), 0.0)
    assertEquals(3.0, indent.snap(9.0), 0.0)
    assertEquals(0.2, indent.snap(0.1), 0.0)
    assertEquals(0.8, indent.snap(0.75), 0.0) // tie between 0.6 and 0.8 → up
    assertEquals(1.6, indent.snap(1.5), 0.0)  // tie → up
    assertEquals(2.2, indent.snap(2.25), 0.0) // nearer 2.2 than 2.4
    val letter = EditorRanges.letterSpacing
    assertEquals(1.0, letter.snap(1.0), 0.0)
    assertEquals(0.3, letter.snap(0.25), 0.0)
  }
}
