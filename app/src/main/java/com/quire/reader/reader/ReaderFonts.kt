package com.quire.reader.reader

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import com.quire.reader.theme.QuireFonts

/**
 * A reading font: the name Readium knows it by, the bundled files, and how it is previewed.
 *
 * The first fonts are also the app's own UI fonts, so they carry a [bundled] Compose family from `res/font`. The others are
 * previewed straight from their reading file in `assets/fonts`, so they are not stored twice. A font with no [files] is one
 * Readium declares itself; [previewAsset] names the file to preview it with.
 */
class ReaderFont(
  val name: String,
  val files: List<FontFile>,
  private val bundled: ComposeFontFamily? = null,
  private val previewAsset: String? = null,
) {
  /** The regular weight, for the font picker and the Settings preview. */
  @OptIn(ExperimentalTextApi::class)
  fun preview(assets: AssetManager): ComposeFontFamily {
    bundled?.let { return it }
    val file = files.firstOrNull { !it.italic && FontWeight.Normal.weight in it.weights } ?: files.firstOrNull { !it.italic }
    val path = file?.asset ?: requireNotNull(previewAsset) { "$name has nothing to preview" }
    // A variable file opens on its default instance, which can be Thin: ask for Regular.
    val variation = if (file != null && file.weights.first != file.weights.last) FontVariation.Settings(FontVariation.weight(FontWeight.Normal.weight)) else FontVariation.Settings()
    return ComposeFontFamily(Font(path, assets, FontWeight.Normal, FontStyle.Normal, variationSettings = variation))
  }
}

/** A bundled file of a reading font: one style, and the weight it has (a range for a variable file). */
class FontFile(val asset: String, val italic: Boolean = false, val weights: IntRange)

/** [ReaderFont.preview] for this composition's assets. */
@Composable
fun ReaderFont.previewFamily(): ComposeFontFamily {
  val assets = LocalContext.current.assets
  return remember(this) { preview(assets) }
}

private fun variable(asset: String, weights: IntRange, italicAsset: String? = null) =
  listOfNotNull(FontFile(asset, weights = weights), italicAsset?.let { FontFile(it, italic = true, weights = weights) })

private fun static(slug: String) = listOf(
  FontFile("fonts/$slug-regular.ttf", weights = 400..400),
  FontFile("fonts/$slug-italic.ttf", italic = true, weights = 400..400),
  FontFile("fonts/$slug-bold.ttf", weights = 700..700),
  FontFile("fonts/$slug-bold-italic.ttf", italic = true, weights = 700..700),
)

/**
 * Fonts live in `assets/fonts` and are served to the EPUB's WebView by Readium; their licenses are in
 * `assets/licenses/fonts`. Books store the chosen font as an index into this list, so new fonts go at the end.
 */
val ReaderFontList: List<ReaderFont> = listOf(
  ReaderFont("Literata", listOf(FontFile("fonts/literata.ttf", weights = 200..900), FontFile("fonts/literata-italic.ttf", italic = true, weights = 200..900)), QuireFonts.Literata),
  ReaderFont("Source Serif", listOf(FontFile("fonts/source-serif.ttf", weights = 200..900)), QuireFonts.SourceSerif),
  ReaderFont("Atkinson", listOf(FontFile("fonts/atkinson-regular.ttf", weights = 400..400), FontFile("fonts/atkinson-bold.ttf", weights = 700..700)), QuireFonts.Atkinson),
  ReaderFont("Inter", listOf(FontFile("fonts/inter.ttf", weights = 100..900)), QuireFonts.Inter),
  // Serifs made for long reading, on paper and on screens.
  ReaderFont("Merriweather", static("merriweather")),
  ReaderFont("Lora", variable("fonts/lora.ttf", 400..700, "fonts/lora-italic.ttf")),
  ReaderFont("EB Garamond", variable("fonts/eb-garamond.ttf", 400..800, "fonts/eb-garamond-italic.ttf")),
  ReaderFont("Crimson Pro", variable("fonts/crimson-pro.ttf", 200..900, "fonts/crimson-pro-italic.ttf")),
  ReaderFont("Libre Baskerville", variable("fonts/libre-baskerville.ttf", 400..700, "fonts/libre-baskerville-italic.ttf")),
  ReaderFont("Alegreya", variable("fonts/alegreya.ttf", 400..900, "fonts/alegreya-italic.ttf")),
  ReaderFont("Spectral", static("spectral")),
  ReaderFont("Newsreader", variable("fonts/newsreader.ttf", 200..800, "fonts/newsreader-italic.ttf")),
  ReaderFont("Charis SIL", static("charis-sil")),
  ReaderFont("Bitter", variable("fonts/bitter.ttf", 100..900, "fonts/bitter-italic.ttf")),
  // Sans-serifs, and fonts designed for readers who find standard text hard going.
  ReaderFont("Lexend", variable("fonts/lexend.ttf", 100..900)),
  ReaderFont("Source Sans", variable("fonts/source-sans-3.ttf", 200..900, "fonts/source-sans-3-italic.ttf")),
  ReaderFont("Open Sans", variable("fonts/open-sans.ttf", 300..800, "fonts/open-sans-italic.ttf")),
  ReaderFont("Andika", static("andika")),
  // Readium bundles and declares OpenDyslexic itself.
  ReaderFont("OpenDyslexic", emptyList(), previewAsset = "readium/fonts/OpenDyslexic-Regular.otf"),
)
