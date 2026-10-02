package com.quire.reader.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.TextAlignPref
import com.quire.reader.reader.ReaderFontList
import com.quire.reader.theme.Nq
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.Ic
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText
import com.quire.reader.ui.QuireViewModel
import com.quire.reader.ui.Toggle
import com.quire.reader.ui.reader.ReadingControls

@Composable
fun SettingsScreen(vm: QuireViewModel) {
  BackHandler { vm.closeSettings() }
  val defaults by vm.defaults.collectAsStateWithLifecycle()
  val useCalibre by vm.useCalibreSetting.collectAsStateWithLifecycle()
  val watch by vm.watchSetting.collectAsStateWithLifecycle()

  Column(Modifier.fillMaxSize().background(Nq.bg).statusBarsPadding()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconBtn(Ic.ArrowLeft, vm::closeSettings)
      QText("Settings", 20f, Modifier.padding(start = 4.dp), weight = 500)
    }
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Kicker("Reading defaults")
        QText("How a book looks the first time you open it. You can still change a single book from its Display menu.", 12.5f, color = Nq.neutral400, lh = 1.5f)
        Preview(defaults)
        ReadingControls(defaults) { change -> vm.updateDefaults(change) }
        QButton("Reset every book to these defaults", vm::resetAllBookPrefs, Modifier.fillMaxWidth(), icon = Ic.Refresh, size = 12.5f)
        QText("Books you have given their own settings keep them until you do this.", 11.5f, color = Nq.neutral500)
      }

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Kicker("Library")
        Toggle("Watch for new books", "Rescan when you open Quire and every few hours", watch) { vm.setWatchSetting(!watch) }
        Toggle("Use Calibre metadata", "Series, tags and ratings from metadata.opf (applies on a full rescan)", useCalibre) { vm.setUseCalibreSetting(!useCalibre) }
      }
    }
  }
}

@Composable
private fun Toggle(label: String, sub: String, on: Boolean, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      QText(label, 14f)
      QText(sub, 11.5f, color = Nq.neutral500)
    }
    com.quire.reader.ui.Toggle(on, onClick)
  }
}

/** A few lines of sample text in the chosen theme, font, size, spacing and alignment. */
@Composable
private fun Preview(p: ReaderPrefs) {
  val shape = RoundedCornerShape(12.dp)
  val font = ReaderFontList[p.font.coerceIn(0, ReaderFontList.lastIndex)]
  Box(Modifier.fillMaxWidth().clip(shape).background(p.theme.bg).border(1.dp, Nq.neutral800, shape).padding(horizontal = (p.margin * 0.6f).dp, vertical = 16.dp)) {
    Text(
      "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife. However little known the feelings or views of such a man may be on his first entering a neighbourhood…",
      style = TextStyle(
        fontFamily = font.preview, fontSize = p.fontSize.sp, lineHeight = (p.fontSize * p.lineHeight).sp, color = p.theme.fg,
        textAlign = if (p.align == TextAlignPref.Justify) TextAlign.Justify else TextAlign.Start, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph,
      ),
      maxLines = 6,
    )
  }
}
