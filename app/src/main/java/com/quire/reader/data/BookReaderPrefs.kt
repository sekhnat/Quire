package com.quire.reader.data

import com.quire.reader.theme.ReaderTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What a book's `prefsJson` row holds: the seven basic fields as one optional group, plus the
 * advanced controls, each group overridable on its own.
 *
 * The basic group is detected by whether [theme] carried a value: a basic edit always writes
 * it, and an advanced-only edit leaves it out, so an advanced tweak on a book without a basic
 * override never pins the inherited basic settings. Older builds that only know `ReaderPrefs`
 * read an advanced-only row as factory basic settings — the unknown key is ignored, and the
 * missing basic keys decode to their defaults — an accepted, documented downgrade.
 */
@Serializable
data class BookReaderPrefs(
  val theme: ReaderTheme? = null,
  val font: Int? = null,
  val fontSize: Int? = null,
  val lineHeight: Float? = null,
  val margin: Int? = null,
  val align: TextAlignPref? = null,
  val mode: ReadMode? = null,
  val advanced: AdvancedReaderPrefs? = null,
) {
  /** True when the stored JSON carried the basic group: the book overrides the global basics. */
  val hasBasic: Boolean get() = theme != null

  /** True when the stored JSON carried an advanced object of its own. */
  val hasAdvanced: Boolean get() = advanced != null

  /** True when the row carries nothing at all and need not be stored. */
  val isEmpty: Boolean get() = !hasBasic && !hasAdvanced

  /**
   * Lays this row's groups over the global [defaults]: each group the row carries wins, and
   * a group it omits keeps the global value — so an advanced-only row keeps inheriting the
   * global basics, and a basic-only row keeps inheriting the global advanced object.
   */
  fun appliedTo(defaults: ReaderPrefs): ReaderPrefs = defaults.copy(
    theme = theme ?: defaults.theme,
    font = font ?: defaults.font,
    fontSize = fontSize ?: defaults.fontSize,
    lineHeight = lineHeight ?: defaults.lineHeight,
    margin = margin ?: defaults.margin,
    align = align ?: defaults.align,
    mode = mode ?: defaults.mode,
    advanced = advanced ?: defaults.advanced,
  )
  /** Rewrites the basic group from a full [ReaderPrefs]; the advanced group is left as it is. */
  fun withBasicFrom(prefs: ReaderPrefs): BookReaderPrefs = copy(
    theme = prefs.theme,
    font = prefs.font,
    fontSize = prefs.fontSize,
    lineHeight = prefs.lineHeight,
    margin = prefs.margin,
    align = prefs.align,
    mode = prefs.mode,
  )

  fun toJson(): String = json.encodeToString(this)

  companion object {
    // explicitNulls keeps an advanced-only row at exactly `{"advanced":{…}}`: the basic keys
    // stay out of the JSON, so an older decoder never meets a basic key carrying null.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** Tolerates damaged or older JSON by falling back to no override at all. */
    fun fromJson(text: String?): BookReaderPrefs? =
      text?.let { runCatching { json.decodeFromString<BookReaderPrefs>(it) }.getOrNull() }
  }
}
