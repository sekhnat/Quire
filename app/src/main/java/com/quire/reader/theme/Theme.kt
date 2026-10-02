package com.quire.reader.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NocturneScheme =
  darkColorScheme(
    primary = Nq.accent,
    onPrimary = Nq.bg,
    secondary = Nq.accent400,
    background = Nq.bg,
    onBackground = Nq.text,
    surface = Nq.surface,
    onSurface = Nq.text,
    surfaceVariant = Nq.surface,
    onSurfaceVariant = Nq.neutral400,
    outline = Nq.neutral700,
    outlineVariant = Nq.neutral800,
  )

/** Quire is a single dark theme (Nocturne); the reader paints its own page colours. */
@Composable
fun QuireTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = NocturneScheme, typography = Typography, content = content)
}
