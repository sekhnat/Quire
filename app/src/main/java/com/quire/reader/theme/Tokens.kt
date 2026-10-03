package com.quire.reader.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable

/** Nocturne design-system tokens (from the design's styles.css). */
object Nq {
  val bg = Color(0xFF161826)
  val surface = Color(0xFF232532)
  val text = Color(0xFFE9E9ED)
  val accent = Color(0xFF9184D9)
  val divider = Color(0xFFE9E9ED).copy(alpha = 0.16f)

  val neutral100 = Color(0xFFF3F5FE)
  val neutral200 = Color(0xFFE4E7F5)
  val neutral300 = Color(0xFFCFD3E5)
  val neutral400 = Color(0xFFB2B6CA)
  val neutral500 = Color(0xFF9397AB)
  val neutral600 = Color(0xFF75798C)
  val neutral700 = Color(0xFF595D6C)
  val neutral800 = Color(0xFF3F424D)
  val neutral900 = Color(0xFF292B31)

  val accent100 = Color(0xFFF5F4FF)
  val accent200 = Color(0xFFE7E5FE)
  val accent300 = Color(0xFFD2CEFD)
  val accent400 = Color(0xFFB5ABFC)
  val accent500 = Color(0xFF968AE0)
  val accent600 = Color(0xFF796CBF)
  val accent700 = Color(0xFF5D5294)
  val accent800 = Color(0xFF423A6A)
  val accent900 = Color(0xFF2B2741)

  val section = Color(0xFF262A60)
  val sectionGlow = Color(0xFF353B80)

  val scrim = Color(0xFF080910)

  /** For actions that delete something. The design has no destructive color; this one is muted to sit with the palette. */
  val danger = Color(0xFFE08A84)

  /** `color-mix(in srgb, accent N%, transparent)` */
  fun accentA(alpha: Float) = accent.copy(alpha = alpha)
  fun textA(alpha: Float) = text.copy(alpha = alpha)
}

/** Reader page themes. */
enum class ReaderTheme(val label: String, val bg: Color, val fg: Color, val muted: Color) {
  Night("Night", Nq.bg, Nq.neutral200, Nq.neutral600),
  Dusk("Dusk", Nq.neutral900, Nq.neutral300, Nq.neutral600),
  Sepia("Sepia", Color(0xFFF1E3CB), Color(0xFF3E2F23), Color(0xFF877768)),
  Paper("Paper", Nq.neutral100, Nq.neutral900, Nq.neutral500),
  /** True black for OLED screens: unlit pixels, with softened text so it doesn't glare. */
  Black("Black", Color(0xFF000000), Color(0xFFC9CBD6), Color(0xFF6B6E7B));

  val isLight get() = this == Sepia || this == Paper
}
