package com.quire.reader.reader

import com.quire.reader.data.PageMargins
import com.quire.reader.data.ReaderPrefs
import kotlin.math.roundToInt

/**
 * Where the page sits on the screen. The S/M/L presets keep exactly the layout every book had before custom margins:
 * Readium's own page gutter on the sides (a multiple of [READIUM_GUTTER_DP]), and fixed space above and below. Custom
 * margins put the sides in padding around the reader instead, with a small Readium gutter kept so two columns never
 * touch: ReadiumCSS only knows symmetric margins, and padding inside paged columns would break pagination.
 */
object PageFrame {
  /** Readium's page gutter at a `pageMargins` factor of 1, in dp (measured: text starts 20 dp from the edge at M). */
  const val READIUM_GUTTER_DP = 20

  /** The `pageMargins` factor used under custom margins: a 6 dp gutter. */
  const val CUSTOM_GUTTER_FACTOR = 0.3
  private val CUSTOM_GUTTER_DP = (READIUM_GUTTER_DP * CUSTOM_GUTTER_FACTOR).roundToInt()

  /** The status line's own band above the page (the book title sat 6 dp below the status bar). */
  const val HEADER_BAND_DP = 24

  /** The status line's own band below the page (the footer text sat 18 dp above the navigation bar). */
  const val FOOTER_BAND_DP = 36

  /** The presets' space between the header band and the page, and between the page and the footer band: 32 and 46 dp in all, as before. */
  const val PRESET_TOP_DP = 8
  const val PRESET_BOTTOM_DP = 10

  /** The margins [prefs] give, in dp: the custom ones, or the preset's as they always looked. */
  fun effectiveMargins(prefs: ReaderPrefs): PageMargins = prefs.margins ?: run {
    val side = (marginFactor(prefs.margin) * READIUM_GUTTER_DP).roundToInt()
    PageMargins(top = PRESET_TOP_DP, bottom = PRESET_BOTTOM_DP, left = side, right = side)
  }

  /** The `pageMargins` factor to submit to Readium. */
  fun readiumFactor(prefs: ReaderPrefs): Double = if (prefs.margins == null) marginFactor(prefs.margin) else CUSTOM_GUTTER_FACTOR

  /** Padding around the reader view on the left and right, in dp; zero for the presets. */
  fun sidePadding(prefs: ReaderPrefs): Pair<Int, Int> {
    val m = prefs.margins ?: return 0 to 0
    return (m.left - CUSTOM_GUTTER_DP).coerceAtLeast(0) to (m.right - CUSTOM_GUTTER_DP).coerceAtLeast(0)
  }

  /** Space above the page in dp, below the status bar. */
  fun topSpace(prefs: ReaderPrefs, showHeader: Boolean): Int = (if (showHeader) HEADER_BAND_DP else 0) + effectiveMargins(prefs).top

  /** Space below the page in dp, above the navigation bar. */
  fun bottomSpace(prefs: ReaderPrefs, showFooter: Boolean): Int = (if (showFooter) FOOTER_BAND_DP else 0) + effectiveMargins(prefs).bottom

  /** [margins] snapped to the sliders' steps and range. */
  fun snapped(margins: PageMargins): PageMargins {
    fun s(v: Int) = ((v.coerceIn(0, PageMargins.MAX) + PageMargins.STEP / 2) / PageMargins.STEP * PageMargins.STEP).coerceAtMost(PageMargins.MAX)
    return PageMargins(s(margins.top), s(margins.bottom), s(margins.left), s(margins.right))
  }
}
