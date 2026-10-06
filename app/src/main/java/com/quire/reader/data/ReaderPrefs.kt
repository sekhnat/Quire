package com.quire.reader.data

import com.quire.reader.theme.ReaderTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ReadMode { Paged, Scroll }
enum class TextAlignPref { Left, Justify }

/** Automatic submits the navigator's own default; On and Off are explicit. */
@Serializable
enum class TriState { Auto, On, Off }

/** Level of a spacing-like control; Default submits null and leaves the choice to the book. */
@Serializable
enum class SpacingLevel { Default, None, Small, Medium, Large }

/** How far apart letters and words sit; Default submits null. */
@Serializable
enum class WidenLevel { Default, Slight, Wider, VeryWide, Max }

/** Boldness of the body text; Default submits null. */
@Serializable
enum class WeightLevel { Default, Light, Regular, Medium, Heavy }

/** Writing direction of the text itself, for CJK layouts. */
@Serializable
enum class VerticalTextPref { Automatic, Horizontal, Vertical }

/** Reading progression across pages. */
@Serializable
enum class DirectionPref { Automatic, LeftToRight, RightToLeft }

/** Filter applied to images in dark themes; Invert is never picked automatically. */
@Serializable
enum class ImageFilterPref { Original, Darken, Invert }

/** One or two columns; maps per layout, so the other preference stays unset. */
@Serializable
enum class PageLayoutPref { Auto, Single, Two }

/** Whose typography rules the page: Quire's controls, or the book's own styles. */
@Serializable
enum class TypographySource { Quire, Book }

/**
 * Which paragraph layout a book shows, derived from the indent and spacing levels. Default,
 * Traditional and Screen are the choices; any other combination reads as Custom, which is a
 * status and never a choice.
 */
@Serializable
enum class ParagraphPreset { Default, Traditional, Screen, Custom }

/**
 * The opt-in advanced typography and page-layout controls. [AdvancedReaderPrefs()] is the
 * single place factory defaults are defined, and every factory value maps to the navigator's
 * own default (the row submits null), so factory settings render exactly as they did before
 * the advanced controls existed.
 */
@Serializable
data class AdvancedReaderPrefs(
  val typographySource: TypographySource = TypographySource.Quire,
  val paragraphIndent: SpacingLevel = SpacingLevel.Default,
  val paragraphSpacing: SpacingLevel = SpacingLevel.Default,
  val letterSpacing: WidenLevel = WidenLevel.Default,
  val wordSpacing: WidenLevel = WidenLevel.Default,
  val fontWeight: WeightLevel = WeightLevel.Default,
  val hyphens: TriState = TriState.On,
  val ligatures: TriState = TriState.Auto,
  val verticalText: VerticalTextPref = VerticalTextPref.Automatic,
  val simplifyTypography: Boolean = false,
  val readingDirection: DirectionPref = DirectionPref.Automatic,
  val imageFilter: ImageFilterPref = ImageFilterPref.Original,
  val pageLayout: PageLayoutPref = PageLayoutPref.Auto,
) {
  /** The paragraph layout these indent and spacing levels produce. */
  val paragraphPreset: ParagraphPreset
    get() = when {
      paragraphIndent == SpacingLevel.Default && paragraphSpacing == SpacingLevel.Default -> ParagraphPreset.Default
      paragraphIndent == SpacingLevel.Medium && paragraphSpacing == SpacingLevel.None -> ParagraphPreset.Traditional
      paragraphIndent == SpacingLevel.None && paragraphSpacing == SpacingLevel.Medium -> ParagraphPreset.Screen
      else -> ParagraphPreset.Custom
    }

  /** True when nothing has been changed from the factory values. */
  val isFactory: Boolean get() = this == AdvancedReaderPrefs()

  /** This object with a preset's indent and spacing levels set; Custom leaves it unchanged. */
  fun withPreset(preset: ParagraphPreset): AdvancedReaderPrefs? =
    levelsFor(preset)?.let { (indent, spacing) -> copy(paragraphIndent = indent, paragraphSpacing = spacing) }
  companion object {
    /**
     * The indent and spacing levels a preset choice sets, reduced in one step. Custom is a
     * status, not a choice, and has no levels of its own.
     */
    fun levelsFor(preset: ParagraphPreset): Pair<SpacingLevel, SpacingLevel>? = when (preset) {
      ParagraphPreset.Default -> SpacingLevel.Default to SpacingLevel.Default
      ParagraphPreset.Traditional -> SpacingLevel.Medium to SpacingLevel.None
      ParagraphPreset.Screen -> SpacingLevel.None to SpacingLevel.Medium
      ParagraphPreset.Custom -> null
    }
  }
}

/** How the reader looks. There is one default set, and each book can override it. */
@Serializable
data class ReaderPrefs(
  val theme: ReaderTheme = ReaderTheme.Night,
  /** Index into the reader's font list. */
  val font: Int = 0,
  val fontSize: Int = 19,
  val lineHeight: Float = 1.6f,
  val margin: Int = 26,
  val align: TextAlignPref = TextAlignPref.Justify,
  val mode: ReadMode = ReadMode.Paged,
  /** The advanced controls; they ride inside the same stored JSON as the basics. */
  val advanced: AdvancedReaderPrefs = AdvancedReaderPrefs(),
) {
  fun toJson(): String = json.encodeToString(this)

  companion object {
    const val MIN_SIZE = 13
    const val MAX_SIZE = 30
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Tolerates damaged or older JSON by falling back to the defaults. */
    fun fromJson(text: String?): ReaderPrefs? = text?.let { runCatching { json.decodeFromString<ReaderPrefs>(it) }.getOrNull() }
  }
}
