package com.quire.reader.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.quire.reader.data.scan.DiscoveryProgress
import com.quire.reader.data.scan.ScanPhase
import com.quire.reader.data.scan.StoragePaths
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.FolderPickerSheet
import com.quire.reader.ui.Ic
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.OnboardStep
import com.quire.reader.ui.Ph
import com.quire.reader.ui.ProgressLine
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText
import com.quire.reader.ui.QuireViewModel
import com.quire.reader.ui.Toggle
import com.quire.reader.ui.UiState
import com.quire.reader.ui.cornerGlow
import com.quire.reader.ui.settings.BACKUP_PICK_TYPES
import com.quire.reader.ui.settings.RestoreSheet
import java.text.NumberFormat
import java.util.Locale

private fun fmt(n: Int) = NumberFormat.getIntegerInstance(Locale.US).format(n)

@Composable
fun OnboardingScreen(s: UiState, vm: QuireViewModel) {
  var pickingFolder by remember { mutableStateOf(false) }
  // Guarding an existing library, the Access step has nothing to go back to.
  BackHandler(enabled = (s.onboardStep == OnboardStep.Access && !s.accessForLibrary) || s.onboardStep == OnboardStep.Folders) {
    vm.setStep(OnboardStep.Welcome)
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }
  Box(Modifier.fillMaxSize().background(Nq.bg).cornerGlow(500.dp, 360.dp, Nq.section)) {
    when (s.onboardStep) {
      OnboardStep.Welcome -> Welcome(vm)
      OnboardStep.Access -> Access(vm, forLibrary = s.accessForLibrary)
      OnboardStep.Folders -> PickFolders(s, vm, onAddFolder = { pickingFolder = true })
      OnboardStep.Scan -> Scanning(s, vm)
    }
    FolderPickerSheet(pickingFolder && s.onboardStep == OnboardStep.Folders, { pickingFolder = false }, vm::addPickedFolder)
    // A new install can start from a full backup instead: there is no library yet, so only "replace" makes sense.
    RestoreSheet(vm, allowMerge = false)
  }
}

@Composable
private fun Welcome(vm: QuireViewModel) {
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importFiles(uris) }
  val backupOpen = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.inspectBackup(uri) }
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 40.dp), verticalArrangement = Arrangement.SpaceBetween) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(28.dp).border(1.dp, Nq.accent, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) { Ph(Ic.BookOpen, 16.dp, Nq.accent) }
        QText("Quire", 15f, weight = 500)
      }
      QText("Every book on your phone, in one quiet place.", 34f, Modifier.padding(top = 60.dp), weight = 500, ls = -0.02f, lh = 1.1f, balance = true)
      QText(
        "Choose the folders where you keep your EPUBs. Quire reads your Calibre metadata, picks up new files as they arrive, and stays fast with ten thousand books.",
        14f, color = Nq.neutral400, lh = 1.6f,
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      QButton("Choose folders", vm::chooseFolders, Modifier.fillMaxWidth(), BtnKind.Primary, icon = Ic.FolderOpen, height = 46.dp)
      QButton("Import individual files instead", { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, Modifier.fillMaxWidth(), BtnKind.Ghost, size = 13f, height = 40.dp, color = Nq.neutral300)
      QButton("Restore from a Quire backup", { backupOpen.launch(BACKUP_PICK_TYPES) }, Modifier.fillMaxWidth(), BtnKind.Ghost, size = 13f, height = 40.dp, color = Nq.neutral300)
    }
  }
}

@Composable
private fun Access(vm: QuireViewModel, forLibrary: Boolean) {
  val context = LocalContext.current
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    if (forLibrary) Spacer(Modifier.height(40.dp)) else IconBtn(Ic.ArrowLeft, { vm.setStep(OnboardStep.Welcome) }, Modifier.offset(x = (-8).dp))
    Column(Modifier.padding(horizontal = 8.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Box(Modifier.size(44.dp).border(1.dp, Nq.accent, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Ph(Ic.FolderOpen, 22.dp, Nq.accent) }
      QText("Allow access to your files", 24f, Modifier.padding(top = 10.dp), weight = 500, ls = -0.01f)
      QText(
        if (forLibrary) "Your library is back, but its books live in your folders and Android doesn't carry file access over. Allow it again so Quire can open them."
        else "Quire opens books straight from your folders — your Calibre library, Books, Downloads — so it can keep up as files are added or removed.",
        14f, color = Nq.neutral400, lh = 1.6f,
      )
      QText("Android calls this “All files access”. It’s a single switch in Settings, and everything stays on your phone: Quire never uploads anything.", 14f, color = Nq.neutral400, lh = 1.6f)
    }
    QText("Turn the switch on, then come back here.", 12f, Modifier.fillMaxWidth(), color = Nq.neutral500, align = androidx.compose.ui.text.style.TextAlign.Center)
    QButton("Open Settings", { runCatching { context.startActivity(StoragePaths.allFilesAccessIntent(context)) } }, Modifier.fillMaxWidth(), BtnKind.Primary, icon = Ic.ArrowRight, height = 46.dp)
  }
}

@Composable
private fun PickFolders(s: UiState, vm: QuireViewModel, onAddFolder: () -> Unit) {
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    IconBtn(Ic.ArrowLeft, { vm.setStep(OnboardStep.Welcome) }, Modifier.offset(x = (-8).dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      QText("Where are your books?", 24f, weight = 500, ls = -0.01f)
      QText(
        if (s.candidates.isEmpty() && !s.discovering) "We didn’t find any EPUB files yet. Add the folder where you keep them." else "We found EPUB files in these folders. Quire will watch the ones you pick for new books.",
        13f, color = Nq.neutral400, lh = 1.5f,
      )
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shape = RoundedCornerShape(8.dp)
        s.discovery?.let { DiscoveryLine(it) }
        s.candidates.forEach { f ->
          val on = f.path in s.pickedFolders
          Row(
            Modifier.fillMaxWidth().clip(shape).background(Nq.surface).border(1.dp, if (on) Nq.accentA(0.45f) else Color.Transparent, shape).clickable { vm.toggleFolder(f.path) }.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(Modifier.size(20.dp).border(1.dp, if (on) Nq.accent else Nq.neutral600, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
              if (on) Ph(Ic.CheckBold, 13.dp, Nq.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              QText(f.name, 14f, weight = 500, maxLines = 1)
              QText(f.path, 11f, color = Nq.neutral500, family = QuireFonts.Mono, maxLines = 1)
            }
            QText(fmt(f.epubCount) + " EPUB", 12f, color = Nq.neutral400, tabular = true)
          }
        }
        // Locked while discovery runs; it would race the folders still being found.
        Row(
          Modifier.fillMaxWidth().alpha(if (s.discovering) 0.45f else 1f).clip(shape).border(1.dp, Nq.neutral700, shape)
            .clickable(enabled = !s.discovering, onClick = onAddFolder).padding(12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
          Ph(Ic.Plus, 18.dp, Nq.accent)
          QText("Add another folder", 13f, color = Nq.neutral300)
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Option("Use Calibre metadata", "Series, tags and ratings from metadata.opf", s.useCalibre) { vm.toggleCalibre() }
        Option("Watch for new books", "New files are added automatically", s.watchFolders) { vm.toggleWatch() }
      }
    }
    val picked = s.candidates.filter { it.path in s.pickedFolders }
    val total = picked.sumOf { it.epubCount }
    QButton(
      "Scan ${picked.size} ${if (picked.size == 1) "folder" else "folders"} · ${fmt(total)} books", vm::startScan,
      Modifier.fillMaxWidth(), BtnKind.Primary, icon = Ic.Search, height = 46.dp, enabled = picked.isNotEmpty() && !s.discovering,
    )
  }
}

/** The bar and caption shown while discovery walks the storage roots. */
@Composable
private fun DiscoveryLine(p: DiscoveryProgress) {
  val fraction by animateFloatAsState(p.fraction, label = "discovery")
  Column(Modifier.padding(top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    ProgressLine(fraction, Modifier.fillMaxWidth())
    QText(
      if (p.current.isEmpty()) "Looking for books…" else "Looking in ${p.current} · ${fmt(p.epubs)} EPUB found",
      11f, color = Nq.neutral500, family = QuireFonts.Mono, maxLines = 1,
    )
  }
}

@Composable
private fun Option(label: String, sub: String, on: Boolean, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      QText(label, 14f)
      QText(sub, 11.5f, color = Nq.neutral500)
    }
    Toggle(on, onClick)
  }
}

@Composable
private fun Scanning(s: UiState, vm: QuireViewModel) {
  val scan by vm.scan.collectAsState()
  val done = scan.phase == ScanPhase.Done
  val progress = if (scan.phase == ScanPhase.Finding) 0f else scan.fraction
  // Books already in the library from an earlier scan count as found from the start.
  val shown = when (scan.phase) {
    ScanPhase.Reading -> scan.found - (scan.total - scan.processed)
    ScanPhase.Idle -> 0
    else -> scan.found
  }
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, top = 38.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Kicker(if (done) "Library ready" else "Scanning", color = Nq.accent)
      QText(fmt(shown), 64f, weight = 500, ls = -0.03f, lh = 1f, tabular = true)
      QText("books found", 14f, color = Nq.neutral400)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp)).background(Nq.neutral800)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).shadow(10.dp, spotColor = Nq.accent, ambientColor = Nq.accent).background(Nq.accent))
      }
      QText(
        if (done) "Watching ${s.pickedFolders.size} ${if (s.pickedFolders.size == 1) "folder" else "folders"} for changes" else scan.currentFile.ifEmpty { "Looking through your folders…" },
        11f, color = Nq.neutral500, family = QuireFonts.Mono, maxLines = 1,
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      val steps = listOf("Finding EPUB files" to 0f, "Reading Calibre metadata" to .35f, "Extracting covers" to .65f, "Building author and series index" to .9f)
      steps.forEachIndexed { i, (label, at) ->
        val next = steps.getOrNull(i + 1)?.second ?: 1f
        val isDone = (scan.phase != ScanPhase.Finding && progress >= next) || done
        val active = !isDone && (scan.phase == ScanPhase.Finding || progress >= at) && scan.phase != ScanPhase.Idle
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
          Ph(if (isDone) Ic.CheckCircleFill else if (active) Ic.CircleNotch else Ic.Circle, 18.dp, if (isDone || active) Nq.accent else Nq.neutral700)
          QText(label, 13.5f, color = if (isDone) Nq.neutral300 else if (active) Nq.text else Nq.neutral600)
        }
      }
    }
    Spacer(Modifier.weight(1f))
    QText("You can start reading now. Covers and series fill in while the scan continues in the background.", 12f, color = Nq.neutral500, lh = 1.5f)
    QButton(if (done) "Open library" else "Start reading now", vm::openLibrary, Modifier.fillMaxWidth(), BtnKind.Primary, trailingIcon = Ic.ArrowRight, height = 46.dp)
  }
}
