package com.quire.reader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quire.reader.theme.Nq
import com.quire.reader.ui.Ic
import com.quire.reader.ui.QText
import com.quire.reader.ui.QTextField
import com.quire.reader.ui.SheetHost
import com.quire.reader.ui.Toggle

/**
 * The tags to offer for [query] (case-insensitive substring) out of [all]: the [hidden] ones first, so they can be shown
 * again without searching, then the rest in [all]'s order. A hidden tag no book has any more is still offered.
 */
internal fun hiddenTagChoices(all: List<String>, hidden: Set<String>, query: String): List<String> {
  val q = query.trim().lowercase()
  fun matches(tag: String) = q.isEmpty() || tag.lowercase().contains(q)
  val shownHidden = (hidden.sortedBy { it.lowercase() }).filter(::matches)
  return shownHidden + all.filter { it !in hidden && matches(it) }
}

/** "No tags hidden", "1 tag hidden", "3 tags hidden". */
internal fun hiddenTagsSummary(n: Int): String = when (n) {
  0 -> "No tags hidden"
  1 -> "1 tag hidden"
  else -> "$n tags hidden"
}

/** Picks the tags whose books the library hides: a search over every tag, each with a switch. */
@Composable
internal fun HiddenTagsSheet(visible: Boolean, all: List<String>, hidden: Set<String>, onSet: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
  var query by remember(visible) { mutableStateOf("") }
  val choices = remember(all, hidden, query) { hiddenTagChoices(all, hidden, query) }
  SheetHost(visible, onDismiss, Modifier.imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      QText("Hidden tags", 17f, weight = 500, lh = 1.3f)
      QText(
        "Books with a hidden tag stay out of the library, its shelves and searches. Their reading stats still count, and nothing is deleted.",
        12.5f, color = Nq.neutral400, lh = 1.5f,
      )
      QTextField(query, { query = it }, "Search tags", leadingIcon = Ic.Search, onClear = if (query.isNotEmpty()) ({ query = "" }) else null)
      if (choices.isEmpty()) {
        QText(if (all.isEmpty() && hidden.isEmpty()) "No tags yet." else "No tag matches that.", 12f, Modifier.padding(vertical = 8.dp), color = Nq.neutral500)
      }
      LazyColumn(Modifier.heightIn(max = 380.dp)) {
        items(choices, key = { it }) { tag ->
          val on = tag in hidden
          Row(
            Modifier.fillMaxWidth().clickable { onSet(tag, !on) }.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            QText(tag, 14f, Modifier.weight(1f), color = if (on) Nq.accent300 else Nq.neutral200, maxLines = 1)
            Toggle(on, { onSet(tag, !on) })
          }
        }
      }
    }
  }
}
