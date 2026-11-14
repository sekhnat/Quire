package com.quire.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.quire.reader.data.StatusItem
import com.quire.reader.data.StatusLine
import com.quire.reader.data.StatusSlot
import com.quire.reader.data.StatusSlots
import com.quire.reader.theme.Nq
import com.quire.reader.theme.ReaderTheme
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.QText
import com.quire.reader.ui.SheetHost
import com.quire.reader.ui.Tag
import com.quire.reader.ui.Toggle
import com.quire.reader.ui.dashedBorder

/** Which slot is being chosen for: the header's or the footer's, and where on the line. */
private data class SlotRef(val header: Boolean, val slot: StatusSlot)

/**
 * Choosing what the lines above and below the page show: a small page in the reader's theme with the six slots on it;
 * tapping a slot picks its item from the list under the page. Opened from Settings and from the reader's Display sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusLineSheet(
  visible: Boolean,
  line: StatusLine,
  facts: StatusFacts,
  theme: ReaderTheme,
  onDismiss: () -> Unit,
  onChange: ((StatusLine) -> StatusLine) -> Unit,
) {
  val maxH = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
  var picking by remember { mutableStateOf(SlotRef(header = false, slot = StatusSlot.Left)) }
  SheetHost(visible, onDismiss, Modifier.heightIn(max = maxH), backdrop = 0.5f) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      QText("Status line", 17f, weight = 500)
      QText("Tap a place on the page to choose what it shows.", 12.5f, color = Nq.neutral400)

      // The mock page, in the reader's theme.
      val shape = RoundedCornerShape(10.dp)
      Column(Modifier.fillMaxWidth().clip(shape).background(theme.bg).border(1.dp, Nq.neutral800, shape).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SlotLine(line.header, line.showHeader, header = true, picking, facts, theme) { picking = it }
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
          listOf(1f, 0.94f, 0.97f, 0.9f, 0.96f, 0.6f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(5.dp).clip(RoundedCornerShape(3.dp)).background(theme.fg.copy(alpha = 0.18f)))
          }
        }
        SlotLine(line.footer, line.showFooter, header = false, picking, facts, theme) { picking = it }
      }

      val where = (if (picking.header) "Header" else "Footer") + ", " + picking.slot.name.lowercase()
      Kicker(where)
      val current = (if (picking.header) line.header else line.footer)[picking.slot]
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusItem.entries.forEach { item ->
          Tag(item.label, {
            onChange { l -> if (picking.header) l.copy(header = l.header.with(picking.slot, item)) else l.copy(footer = l.footer.with(picking.slot, item)) }
          }, outline = item != current, accent = item == current)
        }
      }

      LineToggle("Show header", line.showHeader) { onChange { it.copy(showHeader = !it.showHeader) } }
      LineToggle("Show footer", line.showFooter) { onChange { it.copy(showFooter = !it.showFooter) } }
      QText("Hiding a line gives its room to the page.", 11.5f, color = Nq.neutral500)
    }
  }
}

@Composable
private fun SlotLine(slots: StatusSlots, shown: Boolean, header: Boolean, picking: SlotRef, facts: StatusFacts, theme: ReaderTheme, onPick: (SlotRef) -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    StatusSlot.entries.forEach { slot ->
      val selected = picking == SlotRef(header, slot)
      val item = slots[slot]
      val text = StatusLineText.of(item, facts) ?: "—"
      Box(
        Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(6.dp))
          .then(if (selected) Modifier.border(1.5.dp, Nq.accent, RoundedCornerShape(6.dp)) else Modifier.dashedBorder(theme.muted.copy(alpha = 0.6f), 6.dp))
          .clickable { onPick(SlotRef(header, slot)) }
          .padding(horizontal = 4.dp),
        contentAlignment = when (slot) { StatusSlot.Left -> Alignment.CenterStart; StatusSlot.Centre -> Alignment.Center; StatusSlot.Right -> Alignment.CenterEnd },
      ) {
        QText(text, 9.5f, color = if (shown) theme.muted else theme.muted.copy(alpha = 0.35f), maxLines = 1, tabular = true)
      }
    }
  }
}

@Composable
private fun LineToggle(label: String, on: Boolean, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    QText(label, 13f, Modifier.weight(1f), color = Nq.neutral300)
    Toggle(on, onClick)
  }
}

/** Example values for the status line's preview where no book is open (Settings). */
internal val SampleStatusFacts = StatusFacts(
  bookTitle = "The Name of the Wind", chapterTitle = "Chapter 12: A Piece of Sky", clock = "21:47",
  battery = BatteryState(64, charging = false), position = 212, total = 840, pagesLeftInChapter = 9,
  minutesLeftInBook = 312, progress = 0.25f,
)
