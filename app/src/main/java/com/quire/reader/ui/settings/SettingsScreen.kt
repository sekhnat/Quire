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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quire.reader.data.ReadMode
import com.quire.reader.data.db.IndexCoverage
import com.quire.reader.data.db.MissingBookRow
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.TextAlignPref
import com.quire.reader.reader.ReaderFontList
import com.quire.reader.theme.Nq
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.Ic
import com.quire.reader.ui.reader.AdvancedReadingControls
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.IndexStatusText
import com.quire.reader.ui.ProgressLine
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText
import com.quire.reader.ui.QuireViewModel
import com.quire.reader.ui.SheetHost
import com.quire.reader.ui.Toggle
import com.quire.reader.ui.coverageFraction
import com.quire.reader.ui.coverageIssues
import com.quire.reader.ui.FolderPickerSheet
import com.quire.reader.ui.formatBytes
import com.quire.reader.ui.indexStatusText
import com.quire.reader.ui.reader.ReadingControls
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: QuireViewModel) {
  BackHandler { vm.closeSettings() }
  /** The neutral reflowable availability the globals are authored against (C9). */
  val neutralAvailability = remember { com.quire.reader.reader.ReaderPreferenceContext.neutralReflowable() }
  val defaults by vm.defaults.collectAsStateWithLifecycle()
  val useCalibre by vm.useCalibreSetting.collectAsStateWithLifecycle()
  val watch by vm.watchSetting.collectAsStateWithLifecycle()
  val indexing by vm.indexingEnabledSetting.collectAsStateWithLifecycle()
  val chargingOnly by vm.indexChargingOnlySetting.collectAsStateWithLifecycle()
  val coverage by vm.indexCoverage.collectAsStateWithLifecycle()
  val activity by vm.indexActivity.collectAsStateWithLifecycle()
  val textBytes by vm.indexedTextBytes.collectAsStateWithLifecycle()
  val storage by vm.storageBytes.collectAsStateWithLifecycle()
  val missing by vm.missingBooks.collectAsStateWithLifecycle()
  var confirmingDelete by remember { mutableStateOf(false) }
  BackHandler(enabled = confirmingDelete) { confirmingDelete = false }
  var choosingBackup by remember { mutableStateOf(false) }
  var pickingBackupFolder by remember { mutableStateOf(false) }
  /** Missing books the user asked to forget, waiting for confirmation; null when nothing is being confirmed. */
  var forgetting by remember { mutableStateOf<List<MissingBookRow>?>(null) }
  BackHandler(enabled = forgetting != null) { forgetting = null }
  // The database grows as books are indexed, so measure again whenever the searchable count changes.
  LaunchedEffect(coverage?.searchable) { vm.refreshDatabaseBytes() }

  // Document pickers. The picked book is remembered across the picker round trip, and a cancelled
  // picker (null uri) changes nothing.
  var pendingNotesFor by rememberSaveable { mutableStateOf<Long?>(null) }
  val notesExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
    val bookId = pendingNotesFor
    pendingNotesFor = null
    if (uri != null && bookId != null) vm.exportNotes(bookId, uri)
  }
  val readingDataExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    if (uri != null) vm.exportReadingData(uri)
  }
  val readingDataImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) vm.importReadingData(uri)
  }
  val backupOpen = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) vm.inspectBackup(uri)
  }

  Box(Modifier.fillMaxSize()) {
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
        ReadingControls(defaults, neutralAvailability) { change -> vm.updateDefaults(change) }
        QButton("Reset every book to these defaults", vm::resetAllBookPrefs, Modifier.fillMaxWidth(), icon = Ic.Refresh, size = 12.5f)
        QText("Books you have given their own settings keep them until you do this.", 11.5f, color = Nq.neutral500)

        val advancedEnabled by vm.advancedReadingEnabled.collectAsStateWithLifecycle()
        val advancedCustomized by vm.advancedDefaultsCustomized.collectAsStateWithLifecycle()
        var confirmingAdvancedRestore by remember { mutableStateOf(false) }
        BackHandler(enabled = confirmingAdvancedRestore) { confirmingAdvancedRestore = false }
        Toggle("Advanced reading", "Show extra typography and page-layout controls.", advancedEnabled) { vm.setAdvancedReadingEnabled(!advancedEnabled) }
        if (!advancedEnabled && advancedCustomized) QText("Advanced reading settings are customized", 11.5f, color = Nq.neutral500)
        AnimatedVisibility(advancedEnabled) {
          Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AdvancedReadingControls(
              defaults.advanced,
              neutralAvailability,
              onAdvanced = { change -> vm.updateDefaults { it.copy(advanced = change(it.advanced)) } },
              onPreset = { preset -> vm.updateDefaults { it.copy(advanced = it.advanced.withPreset(preset) ?: it.advanced) } },
            )
            QText("The preview above shows the basic settings; the advanced options apply when reading, and each book can still hold a control back.", 11.5f, color = Nq.neutral500)
            QButton("Restore advanced defaults", { confirmingAdvancedRestore = true }, Modifier.fillMaxWidth(), icon = Ic.Refresh, size = 12.5f, enabled = advancedCustomized)
          }
        }
        if (confirmingAdvancedRestore) SheetHost(true, { confirmingAdvancedRestore = false }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
          Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            QText("Restore the advanced reading settings?", 17f, weight = 500, lh = 1.3f)
            QText(
              "Every book without its own settings goes back to the factory typography and page layout. Books with their own settings keep them.",
              13f, color = Nq.neutral400, lh = 1.5f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              QButton("Cancel", { confirmingAdvancedRestore = false }, Modifier.weight(1f), size = 13f, height = 42.dp)
              QButton("Restore", { confirmingAdvancedRestore = false; vm.restoreGlobalAdvanced() }, Modifier.weight(1f), size = 13f, height = 42.dp)
            }
          }
        }
      }

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Kicker("Library")
        Toggle("Watch for new books", "Rescan when you open Quire and every few hours", watch) { vm.setWatchSetting(!watch) }
        Toggle("Use Calibre metadata", "Series, tags and ratings from metadata.opf (applies on a full rescan)", useCalibre) { vm.setUseCalibreSetting(!useCalibre) }
      }

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Kicker("Reading data")
        QButton("Export reading data", { readingDataExport.launch("quire-reading-data.json") }, Modifier.fillMaxWidth(), icon = Ic.FileDown, size = 12.5f)
        QButton(
          "Import reading data",
          { readingDataImport.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
          Modifier.fillMaxWidth(), icon = Ic.Plus, size = 12.5f,
        )
        QText(
          "Your highlights, notes, bookmarks, reading positions, ratings, tags and settings as one file. Importing merges them into what you have; nothing here is overwritten.",
          11.5f, color = Nq.neutral500, lh = 1.5f,
        )
      }

      BackupSection(
        vm,
        onBackUp = { vm.refreshBackupSizes(); choosingBackup = true },
        onRestore = { backupOpen.launch(BACKUP_PICK_TYPES) },
        onPickFolder = { pickingBackupFolder = true },
      )

      if (missing.isNotEmpty()) {
        MissingBooks(
          missing,
          onForget = { forgetting = it },
          onExportNotes = { row ->
            pendingNotesFor = row.id
            notesExport.launch("${row.title} — notes.md")
          },
        )
      }

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Kicker("Library search")
        Toggle("Index book text", "Lets you search inside books. Runs in the background and steps aside while you read.", indexing) { vm.setIndexingEnabledSetting(!indexing) }
        Toggle("Index only while charging", "Applies to all indexing, including updates for new and changed books.", chargingOnly) { vm.setIndexChargingOnlySetting(!chargingOnly) }
        LibrarySearchStatus(indexStatusText(coverage, activity, inSettings = true), coverage)
        Value("Library storage", storage?.let { formatBytes(it.library) } ?: "…", "Books, reading state, highlights and notes. The search index lives in its own file, below.")
        Value("Search index storage", storage?.let { formatBytes(it.index) } ?: "…", "The search index file: book text and the full-text tables built on it. Deleting the index or moving a book removes it, and nothing else.")
        Value("Indexed text", formatBytes(textBytes), "How much of that is book text stored for searching.")
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          QButton("Rebuild index", vm::rebuildIndex, Modifier.fillMaxWidth(), icon = Ic.Refresh, size = 12.5f, enabled = indexing)
          QText(
            if (indexing) "Reads every book again. Still follows the charging and reading rules above." else "Turn on indexing to rebuild the index.",
            11.5f, color = Nq.neutral500,
          )
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          QButton("Turn off and delete index", { confirmingDelete = true }, Modifier.fillMaxWidth(), icon = Ic.X, size = 12.5f, color = Nq.danger)
          QText("Removes the search index and stops indexing. Your books, library details and reading progress stay.", 11.5f, color = Nq.neutral500)
        }
      }
    }
  }
  BackupSheets(vm, choosingBackup, onDismissChoosing = { choosingBackup = false })
  FolderPickerSheet(pickingBackupFolder, { pickingBackupFolder = false }) { path -> pickingBackupFolder = false; vm.setAutoBackupFolder(path) }
  DeleteIndexSheet(confirmingDelete, textBytes, onDismiss = { confirmingDelete = false }) { confirmingDelete = false; vm.deleteSearchIndex() }
  ForgetMissingSheet(forgetting, onDismiss = { forgetting = null }) { rows ->
    forgetting = null
    if (rows.size == 1) vm.forgetMissing(rows.single().id) else vm.forgetAllMissing()
  }
  }
}

/** Books whose file is gone but whose reading history Quire keeps, each of which can be forgotten. */
@Composable
private fun MissingBooks(rows: List<MissingBookRow>, onForget: (List<MissingBookRow>) -> Unit, onExportNotes: (MissingBookRow) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Kicker("Missing books")
    QText(
      "Their files are gone, but their reading history is kept. A book comes back with it when its file turns up again, even renamed or in another folder of your library.",
      12.5f, color = Nq.neutral400, lh = 1.5f,
    )
    rows.forEach { row ->
      Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          QText(row.title, 14f, maxLines = 1)
          QText("${row.author} · ${missingDetail(row)}", 11.5f, color = Nq.neutral500, maxLines = 2, lh = 1.4f)
        }
        IconBtn(Ic.NotePencil, { onExportNotes(row) }, tint = Nq.neutral500, size = 32.dp, iconSize = 16.dp)
        IconBtn(Ic.X, { onForget(listOf(row)) }, tint = Nq.neutral500, size = 32.dp, iconSize = 16.dp)
      }
    }
    if (rows.size > 1) QButton("Forget all missing books", { onForget(rows) }, Modifier.fillMaxWidth().padding(top = 6.dp), icon = Ic.X, size = 12.5f, color = Nq.danger)
  }
}

/** "Missing since 4 Oct 2026 · 42% read · 3 highlights · 1 bookmark". */
private fun missingDetail(row: MissingBookRow): String = listOfNotNull(
  "Missing since " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(row.missingSince)),
  row.progress?.takeIf { it > 0f }?.let { "${(it * 100).roundToInt()}% read" },
  row.highlights.takeIf { it > 0 }?.let { "$it ${if (it == 1) "highlight" else "highlights"}" },
  row.bookmarks.takeIf { it > 0 }?.let { "$it ${if (it == 1) "bookmark" else "bookmarks"}" },
).joinToString(" · ")

/** The step before forgetting missing books: their history goes for good. */
@Composable
private fun ForgetMissingSheet(rows: List<MissingBookRow>?, onDismiss: () -> Unit, onConfirm: (List<MissingBookRow>) -> Unit) {
  // Keep showing the last rows while the sheet animates out.
  var shown by remember { mutableStateOf(rows.orEmpty()) }
  if (rows != null) shown = rows
  SheetHost(rows != null, onDismiss, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      QText(if (shown.size == 1) "Forget “${shown.single().title}”?" else "Forget ${shown.size} missing books?", 17f, weight = 500, lh = 1.3f)
      QText(
        if (shown.size == 1) "Its reading position, bookmarks, highlights and notes are deleted. If the file turns up again, the book returns without them. Your files are not touched."
        else "Their reading positions, bookmarks, highlights and notes are deleted. If the files turn up again, the books return without them. Your files are not touched.",
        13f, color = Nq.neutral400, lh = 1.5f,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QButton("Cancel", onDismiss, Modifier.weight(1f), size = 13f, height = 42.dp)
        QButton("Forget", { onConfirm(shown) }, Modifier.weight(1f), size = 13f, height = 42.dp, color = Nq.danger)
      }
    }
  }
}

/** Why books may be missing from search (when there is a reason), and how many can be searched. */
@Composable
private fun LibrarySearchStatus(index: IndexStatusText, coverage: IndexCoverage?) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (index.headline != null || index.progress != null) {
      Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        index.headline?.let { QText(it, 12.5f, color = Nq.neutral300, lh = 1.45f) }
        index.progress?.let { ProgressLine(it) }
      }
    }
    Value("Searchable books", coverage?.takeIf { it.eligible > 0 }?.let(::coverageFraction) ?: "None yet", coverage?.let(::coverageIssues))
  }
}

@Composable
internal fun Value(label: String, value: String, sub: String?) {
  Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      QText(label, 14f)
      sub?.let { QText(it, 11.5f, color = Nq.neutral500, lh = 1.4f) }
    }
    QText(value, 14f, color = Nq.accent300, tabular = true, maxLines = 1)
  }
}

/** The step before deleting the index: what goes, and what does not. */
@Composable
private fun DeleteIndexSheet(visible: Boolean, textBytes: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  SheetHost(visible, onDismiss, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      QText("Turn off and delete the search index?", 17f, weight = 500, lh = 1.3f)
      QText(
        "Searching inside books stops working, and the ${formatBytes(textBytes)} of indexed text is removed. This does not delete your books, their details or your reading progress. You can turn indexing back on later; books are then indexed again from scratch.",
        13f, color = Nq.neutral400, lh = 1.5f,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QButton("Cancel", onDismiss, Modifier.weight(1f), size = 13f, height = 42.dp)
        QButton("Turn off and delete", onConfirm, Modifier.weight(1f), size = 13f, height = 42.dp, color = Nq.danger)
      }
    }
  }
}

@Composable
internal fun Toggle(label: String, sub: String, on: Boolean, onClick: () -> Unit) {
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
