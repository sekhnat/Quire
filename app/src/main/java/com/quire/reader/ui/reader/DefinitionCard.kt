package com.quire.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quire.qdict.LookupResult
import com.quire.qdict.Pos
import com.quire.qdict.Sense
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Ic
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText

/** What the card's buttons do. [openInApp] is null when no dictionary app is installed. */
internal class DefinitionCardActions(
  val close: () -> Unit,
  val back: () -> Unit,
  val more: () -> Unit,
  val lookUp: (String) -> Unit,
  val highlight: () -> Unit,
  val copy: () -> Unit,
  val openInApp: (() -> Unit)?,
)

/** How many senses the card shows before *More*. */
internal const val FIRST_SENSES = 3

/**
 * The senses to show, numbered across the whole entry and grouped by part of speech: the first [FIRST_SENSES] unless
 * [expanded]. Each item is (part of speech, number, sense).
 */
internal fun visibleSenses(entry: LookupResult.Entry, expanded: Boolean): List<Triple<Pos, Int, Sense>> {
  val all = entry.senses.flatMap { (pos, list) -> list.map { pos to it } }.mapIndexed { i, (pos, sense) -> Triple(pos, i + 1, sense) }
  return if (expanded) all else all.take(FIRST_SENSES)
}

/**
 * The definition of the word a long press selected: the headword (and the form it came from), numbered senses by part of
 * speech with an example each, synonyms of the first sense to look up in turn, and Highlight, Copy, Open in app and close.
 * It sits over the page without covering the selection: at the top when the word is low on the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DefinitionCard(
  ui: DefinitionUi?,
  atTop: Boolean,
  maxExpanded: Dp,
  actions: DefinitionCardActions,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    ui != null, modifier,
    enter = fadeIn() + slideInVertically { if (atTop) -it / 3 else it / 3 },
    exit = fadeOut() + slideOutVertically { if (atTop) -it / 3 else it / 3 },
  ) {
    val d = ui ?: return@AnimatedVisibility
    val shape = RoundedCornerShape(16.dp)
    Column(
      Modifier.padding(horizontal = 10.dp).fillMaxWidth().shadow(10.dp, shape).clip(shape).background(Nq.surface)
        .heightIn(max = if (d.expanded) maxExpanded else 340.dp).padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
      val result = d.result
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (d.history.isNotEmpty()) IconBtn(Ic.ArrowLeft, actions.back, size = 32.dp, iconSize = 18.dp)
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
          when (result) {
            null -> QText(d.query, 20f, family = QuireFonts.Literata, weight = 500)
            is LookupResult.Entry -> {
              QText(result.headword, 20f, family = QuireFonts.Literata, weight = 500)
              result.fromForm?.let { QText("from $it", 12f, color = Nq.neutral400) }
            }
            is LookupResult.NotFound -> QText("No entry for ‘${result.text}’", 15f, color = Nq.neutral300)
          }
        }
        IconBtn(Ic.X, actions.close, size = 32.dp, iconSize = 18.dp)
      }
      Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(end = 10.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (result) {
          null -> QText("Looking it up…", 13f, color = Nq.neutral500)
          is LookupResult.NotFound -> Unit
          is LookupResult.Entry -> {
            var lastPos: Pos? = null
            visibleSenses(result, d.expanded).forEach { (pos, n, sense) ->
              if (pos != lastPos) { QText(pos.label, 11f, Modifier.padding(top = 2.dp), color = Nq.accent200, ls = 0.08f, upper = true); lastPos = pos }
              Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                QText("$n. ${sense.gloss}", 14f, color = Nq.neutral100, lh = 1.4f)
                sense.example?.let {
                  androidx.compose.material3.Text(
                    it, Modifier.padding(start = 14.dp), color = Nq.neutral400,
                    style = androidx.compose.ui.text.TextStyle(fontFamily = QuireFonts.Literata, fontStyle = FontStyle.Italic, fontSize = 13.sp, lineHeight = 17.5.sp),
                  )
                }
              }
            }
            val synonyms = result.senses.values.firstOrNull()?.firstOrNull()?.synonyms.orEmpty()
            if (synonyms.isNotEmpty()) {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                QText("Similar:", 12.5f, Modifier.padding(vertical = 4.dp), color = Nq.neutral500)
                synonyms.take(8).forEach { word ->
                  QText(word, 12.5f, Modifier.clip(RoundedCornerShape(6.dp)).clickable { actions.lookUp(word) }.padding(horizontal = 4.dp, vertical = 4.dp), color = Nq.accent200)
                }
              }
            }
            val total = result.senses.values.sumOf { it.size }
            if (!d.expanded && total > FIRST_SENSES) {
              QText("More (${total - FIRST_SENSES})", 13f, Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = actions.more).padding(vertical = 4.dp), color = Nq.accent)
            }
          }
        }
      }
      Row(Modifier.padding(top = 8.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QButton("Highlight", actions.highlight, Modifier.weight(1f), icon = Ic.Highlighter, size = 12f, height = 36.dp)
        QButton("Copy", actions.copy, Modifier.weight(1f), icon = Ic.Copy, size = 12f, height = 36.dp)
        actions.openInApp?.let { QButton("Open in app", it, Modifier.weight(1.3f), icon = Ic.BookOpen, size = 12f, height = 36.dp) }
      }
    }
  }
}
