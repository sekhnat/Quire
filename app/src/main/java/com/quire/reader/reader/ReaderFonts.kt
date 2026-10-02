package com.quire.reader.reader

import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import com.quire.reader.theme.QuireFonts

/** A reading font: the name Readium knows it by, the bundled files, and the Compose family used for previews. */
class ReaderFont(val name: String, val preview: ComposeFontFamily, val files: List<FontFile>)

class FontFile(val asset: String, val italic: Boolean = false, val weights: IntRange)

/** Fonts live in `assets/fonts` and are served to the EPUB's WebView by Readium. */
val ReaderFontList: List<ReaderFont> = listOf(
  ReaderFont("Literata", QuireFonts.Literata, listOf(FontFile("fonts/literata.ttf", weights = 200..900), FontFile("fonts/literata-italic.ttf", italic = true, weights = 200..900))),
  ReaderFont("Source Serif", QuireFonts.SourceSerif, listOf(FontFile("fonts/source-serif.ttf", weights = 200..900))),
  ReaderFont("Atkinson", QuireFonts.Atkinson, listOf(FontFile("fonts/atkinson-regular.ttf", weights = 400..400), FontFile("fonts/atkinson-bold.ttf", weights = 700..700))),
  ReaderFont("Inter", QuireFonts.Inter, listOf(FontFile("fonts/inter.ttf", weights = 100..900))),
)
