package com.quire.reader.ui.settings

import android.content.res.AssetManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.quire.reader.theme.Nq
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Ic
import com.quire.reader.ui.Ph
import com.quire.reader.ui.QText
import com.quire.reader.ui.SheetHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the shipped third-party notices live in the app assets. */
internal const val LICENCES_DIR = "licenses"

/**
 * The open-source notices Quire ships (`assets/licenses`): the list, or the one in [open]. [onOpen] with null goes back to
 * the list.
 */
@Composable
internal fun LicencesSheet(visible: Boolean, open: String?, onOpen: (String?) -> Unit, onDismiss: () -> Unit) {
  val assets = LocalContext.current.assets
  val notices by produceState(emptyList<String>(), visible) {
    if (visible) value = withContext(Dispatchers.IO) { licenceFiles(assets) }
  }
  val text by produceState<String?>(null, open) {
    value = open?.let { path -> withContext(Dispatchers.IO) { runCatching { assets.open(path).bufferedReader().use { reflowLicence(it.readText()) } }.getOrNull() } }
  }
  SheetHost(visible, onDismiss, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      if (open == null) {
        QText("Open-source licences", 17f, weight = 500, lh = 1.3f)
        QText("The fonts, icons and reader code Quire is built on, and the licences they come with.", 12.5f, color = Nq.neutral400, lh = 1.5f)
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
          notices.forEach { path ->
            Row(
              Modifier.fillMaxWidth().clickable { onOpen(path) }.padding(vertical = 10.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                QText(licenceTitle(path), 14f)
                QText(licenceKind(path), 11.5f, color = Nq.neutral500)
              }
              Ph(Ic.CaretRight, 16.dp, Nq.neutral500)
            }
          }
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconBtn(Ic.ArrowLeft, { onOpen(null) })
          QText(licenceTitle(open), 17f, Modifier.padding(start = 4.dp), weight = 500)
        }
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
          QText(text ?: "", 12f, color = Nq.neutral300, lh = 1.5f)
        }
      }
    }
  }
}

/** Every notice file under [dir], depth first, sorted by name. */
internal fun licenceFiles(assets: AssetManager, dir: String = LICENCES_DIR): List<String> =
  assets.list(dir).orEmpty().sorted().flatMap { name ->
    val path = "$dir/$name"
    if (assets.list(path).isNullOrEmpty()) listOf(path) else licenceFiles(assets, path)
  }

/** "licenses/fonts/source-serif-OFL.txt" → "Source Serif"; "licenses/readium-LICENSE.txt" → "Readium". */
internal fun licenceTitle(path: String): String =
  path.substringAfterLast('/').substringBeforeLast('.').removeSuffix("-OFL").removeSuffix("-LICENSE")
    .split('-').joinToString(" ") { part -> if (part.length <= 3 && part != "pro") part.uppercase() else part.replaceFirstChar(Char::uppercase) }

/** What a notice is, from how its file is named. */
internal fun licenceKind(path: String): String = when {
  path.endsWith("-OFL.txt") -> "Font · SIL Open Font License 1.1"
  "readium" in path -> "Reader engine · BSD 3-Clause"
  "phosphor" in path -> "Icons · MIT"
  else -> "Licence"
}

/** Joins the hard-wrapped lines of each paragraph so the text wraps to the screen; blank lines stay paragraph breaks. */
internal fun reflowLicence(text: String): String =
  text.replace("\r\n", "\n").trim().split(Regex("\n[ \t]*\n+"))
    .joinToString("\n\n") { para -> para.lines().joinToString(" ") { it.trim() } }
