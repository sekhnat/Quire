package com.quire.reader.reader

import androidx.compose.ui.graphics.toArgb
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.TextAlignPref
import com.quire.reader.theme.ReaderTheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

/** The browser's default text size, which Readium's `fontSize` multiplies. */
private const val BASE_FONT_PX = 16.0

/** Page margin presets (S/M/L) as multiples of Readium's default margin. */
internal fun marginFactor(margin: Int): Double = when {
  margin <= 16 -> 0.7
  margin >= 40 -> 1.6
  else -> 1.0
}

@OptIn(ExperimentalReadiumApi::class)
fun ReaderPrefs.toEpubPreferences(): EpubPreferences = EpubPreferences(
  theme = when (theme) { ReaderTheme.Sepia -> Theme.SEPIA; ReaderTheme.Paper -> Theme.LIGHT; else -> Theme.DARK },
  backgroundColor = Color(theme.bg.toArgb()),
  textColor = Color(theme.fg.toArgb()),
  fontFamily = FontFamily(ReaderFontList[font.coerceIn(0, ReaderFontList.lastIndex)].name),
  fontSize = fontSize / BASE_FONT_PX,
  lineHeight = lineHeight.toDouble(),
  pageMargins = marginFactor(margin),
  // With publisher styles on, the book's own CSS overrides line spacing, alignment and hyphenation.
  publisherStyles = false,
  scroll = mode == ReadMode.Scroll,
  textAlign = if (align == TextAlignPref.Justify) TextAlign.JUSTIFY else TextAlign.START,
  hyphens = true,
)
