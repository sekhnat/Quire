package com.quire.reader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.quire.reader.data.index.ExcerptSpan
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts

/** Search excerpts are set in the reading face, as a page would be. */
internal val SnippetStyle = TextStyle(fontFamily = QuireFonts.Literata, fontSize = 14.sp, lineHeight = 21.sp, color = Nq.neutral300, fontWeight = FontWeight.Normal)

/** An excerpt with its matches highlighted. The text is appended as is, never parsed as markup. */
internal fun snippetText(spans: List<ExcerptSpan>): AnnotatedString = buildAnnotatedString {
  val h = highlightedText(spans)
  append(h.text)
  h.hits.forEach { addStyle(SpanStyle(color = Nq.text, background = Nq.accentA(0.30f)), it.first, it.last + 1) }
}
