package com.quire.reader.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.quire.reader.R

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int, style: FontStyle = FontStyle.Normal, opsz: Float? = null) =
  weights.map { w ->
    val settings = if (opsz != null) FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", opsz)) else FontVariation.Settings(FontVariation.weight(w))
    Font(res, FontWeight(w), style, variationSettings = settings)
  }

object QuireFonts {
  val Inter = FontFamily(variable(R.font.inter, 400, 500, 600))
  val Literata = FontFamily(variable(R.font.literata, 400, 500, opsz = 14f) + variable(R.font.literata_italic, 400, style = FontStyle.Italic, opsz = 14f))
  val SourceSerif = FontFamily(variable(R.font.source_serif, 400, 500, opsz = 16f))
  val Atkinson = FontFamily(Font(R.font.atkinson_regular, FontWeight.Normal), Font(R.font.atkinson_bold, FontWeight.Bold))
  val Mono = FontFamily.Monospace
}

/** Every Material style resolves to Inter so un-styled text still matches the design. */
val Typography: Typography = Typography().let { t ->
  fun TextStyle.inter() = copy(fontFamily = QuireFonts.Inter, letterSpacing = 0.sp)
  Typography(
    displayLarge = t.displayLarge.inter(), displayMedium = t.displayMedium.inter(), displaySmall = t.displaySmall.inter(),
    headlineLarge = t.headlineLarge.inter(), headlineMedium = t.headlineMedium.inter(), headlineSmall = t.headlineSmall.inter(),
    titleLarge = t.titleLarge.inter(), titleMedium = t.titleMedium.inter(), titleSmall = t.titleSmall.inter(),
    bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter(), bodySmall = t.bodySmall.inter(),
    labelLarge = t.labelLarge.inter(), labelMedium = t.labelMedium.inter(), labelSmall = t.labelSmall.inter(),
  )
}
