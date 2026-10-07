package com.quire.reader.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quire.reader.data.backup.BackupFiles
import com.quire.reader.data.backup.BackupInterval
import com.quire.reader.data.backup.FullBackupManifest
import com.quire.reader.data.db.IndexDatabase
import com.quire.reader.theme.Nq
import com.quire.reader.ui.BackupSizes
import com.quire.reader.ui.BackupUi
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.Ic
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.ProgressLine
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText
import com.quire.reader.ui.RestoreUi
import com.quire.reader.ui.SegOption
import com.quire.reader.ui.Segmented
import com.quire.reader.ui.SheetHost
import com.quire.reader.ui.formatBytes
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** File types a backup can arrive as from the document picker; some providers do not know `.zip`. */
val BACKUP_PICK_TYPES = arrayOf(BackupFiles.MIME, "application/x-zip-compressed", "application/octet-stream")

/**
 * Settings → Full backup: back up now, restore, and scheduled backups. The sheets it opens are drawn by [BackupSheets]
 * over the whole screen; this is only the section in the scrolling list.
 */
@Composable
internal fun BackupSection(settings: SettingsState, onBackUp: () -> Unit, onRestore: () -> Unit, onPickFolder: () -> Unit) {
  val backup by settings.backup.backup.collectAsStateWithLifecycle()
  val askNotifications = rememberNotificationRequest()
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Kicker("Full backup")
    QText(
      "Everything in one file: your library and its folders, reading data and settings, and if you like the search index, covers and imported books. Restore it here or on a new phone without indexing again.",
      12.5f, color = Nq.neutral400, lh = 1.5f,
    )
    BackupStatus(backup)
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      QButton("Back up now", onBackUp, Modifier.fillMaxWidth(), icon = Ic.FileDown, size = 12.5f, enabled = !backup.running)
      QButton("Restore from backup", onRestore, Modifier.fillMaxWidth(), icon = Ic.Refresh, size = 12.5f)
    }
    Toggle("Back up automatically", "A new backup in a folder on this phone; older ones are deleted", backup.auto) {
      if (!backup.auto) askNotifications()
      settings.backup.setAutoBackup(!backup.auto)
    }
    if (backup.auto) {
      Choice("How often") {
        Segmented(BackupInterval.entries.map { SegOption(it.name, backup.interval == it, { settings.backup.setInterval(it) }) })
      }
      Choice("Backups to keep") {
        Segmented(listOf(3, 5, 10).map { n -> SegOption("$n", backup.keep == n, { settings.backup.setKeep(n) }) })
      }
      Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          QText("Folder", 14f)
          QText(backup.folder, 11.5f, color = Nq.neutral500, lh = 1.4f)
        }
        QButton("Change", onPickFolder, size = 12.5f)
      }
    }
  }
}

@Composable
private fun BackupStatus(backup: BackupUi) {
  when {
    backup.running -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      QText(backup.progress?.let { "Backing up… ${(it * 100).toInt()}%" } ?: "Backing up…", 12.5f, color = Nq.neutral300)
      ProgressLine(backup.progress ?: 0f)
    }
    backup.lastError != null -> QText("The last backup failed: ${backup.lastError}", 12.5f, Modifier.padding(vertical = 6.dp), color = Nq.danger, lh = 1.45f)
    backup.lastAt > 0 -> QText("Last backup ${dateTime(backup.lastAt)}", 12.5f, Modifier.padding(vertical = 6.dp), color = Nq.neutral300)
  }
}

@Composable
private fun Choice(label: String, control: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    QText(label, 14f, Modifier.weight(1f))
    control()
  }
}

/**
 * The sheets of the backup section, drawn over the whole screen: what to include before backing up, and the restore
 * choice once a backup was picked. [choosing] is the "Back up now" sheet's visibility.
 */
@Composable
internal fun BackupSheets(settings: SettingsState, choosing: Boolean, onDismissChoosing: () -> Unit) {
  val backup by settings.backup.backup.collectAsStateWithLifecycle()
  val sizes by settings.backup.sizes.collectAsStateWithLifecycle()
  val storage by settings.storageBytes.collectAsStateWithLifecycle()
  val askNotifications = rememberNotificationRequest()
  val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFiles.MIME)) { uri ->
    if (uri != null) settings.backup.start(uri)
  }
  BackHandler(enabled = choosing, onBack = onDismissChoosing)
  SheetHost(choosing, onDismissChoosing, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      QText("Back up now", 17f, weight = 500)
      QText("Your library, reading data and settings are always included. Choose what else goes in; automatic backups use the same choice.", 12.5f, color = Nq.neutral400, lh = 1.5f)
      val c = backup.contents
      Toggle("Search index", storage?.let { "${formatBytes(it.index)} · saves indexing the library again" } ?: "Saves indexing the library again", c.index) { settings.backup.setContents(c.copy(index = !c.index)) }
      Toggle("Covers", sizes?.let { "${count(it.covers, "cover")} · ${formatBytes(it.coverBytes)}" } ?: "Thumbnails; rebuilt by a rescan if left out", c.covers) { settings.backup.setContents(c.copy(covers = !c.covers)) }
      Toggle("Imported books", importedSub(sizes), c.imported) { settings.backup.setContents(c.copy(imported = !c.imported)) }
      QButton(
        "Choose where to save",
        {
          onDismissChoosing()
          askNotifications()
          create.launch(BackupFiles.nameFor(System.currentTimeMillis()))
        },
        Modifier.fillMaxWidth().padding(top = 10.dp), BtnKind.Primary, icon = Ic.FileDown, size = 13f, height = 42.dp,
      )
    }
  }
  RestoreSheet(settings.restore)
}

private fun importedSub(sizes: BackupSizes?): String = when {
  sizes == null -> "Books added with Import, which live only inside Quire"
  sizes.imported == 0 -> "None yet: books added with Import live only inside Quire"
  else -> "${sizes.imported} ${if (sizes.imported == 1) "book" else "books"} · ${formatBytes(sizes.importedBytes)} · they live only inside Quire"
}

/**
 * Shows what a picked backup holds and offers the two ways to restore it; then its progress. [allowMerge] is false during
 * onboarding, where there is no library to merge into yet.
 */
@Composable
fun RestoreSheet(restore: RestoreState, allowMerge: Boolean = true) {
  val ui by restore.ui.collectAsStateWithLifecycle()
  var confirming by remember { mutableStateOf(false) }
  // Keep showing the last backup while the sheet animates out.
  var shown by remember { mutableStateOf<RestoreUi.Ready?>(null) }
  (ui as? RestoreUi.Ready)?.let { shown = it }
  val visible = ui is RestoreUi.Ready || ui is RestoreUi.Working
  LaunchedEffect(visible) { if (!visible) confirming = false }
  BackHandler(enabled = ui is RestoreUi.Ready) { if (confirming) confirming = false else restore.dismiss() }
  SheetHost(visible, restore::dismiss, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      when (val r = ui) {
        is RestoreUi.Working -> {
          QText(r.label, 17f, weight = 500)
          ProgressLine(r.progress ?: 0f)
          QText("Keep Quire open until it restarts.", 12.5f, color = Nq.neutral400)
        }
        else -> shown?.manifest?.let { manifest ->
          QText("Backup from ${dateTime(manifest.createdAt)}", 17f, weight = 500, lh = 1.3f)
          QText(describe(manifest), 13f, color = Nq.neutral400, lh = 1.5f)
          if (confirming) {
            QText(
              "Your library, reading data, search index and settings will be replaced by this backup. Books you imported since are kept. Quire will restart.",
              13f, color = Nq.neutral300, lh = 1.5f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              QButton("Cancel", { confirming = false }, Modifier.weight(1f), size = 13f, height = 42.dp)
              QButton("Replace", restore::replace, Modifier.weight(1f), size = 13f, height = 42.dp, color = Nq.danger)
            }
          } else {
            if (allowMerge) {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                QButton("Merge reading data", restore::merge, Modifier.fillMaxWidth(), BtnKind.Primary, size = 13f, height = 42.dp)
                QText("Adds its highlights, notes, bookmarks, positions and tags to your library. Nothing here is replaced.", 11.5f, color = Nq.neutral500, lh = 1.45f)
              }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              QButton(
                "Replace everything", { confirming = true }, Modifier.fillMaxWidth(), if (allowMerge) BtnKind.Secondary else BtnKind.Primary,
                size = 13f, height = 42.dp, color = if (allowMerge) Nq.danger else null,
              )
              QText("Makes Quire exactly as it was when the backup was made, search index included.", 11.5f, color = Nq.neutral500, lh = 1.45f)
            }
          }
        }
      }
    }
  }
}

/** "1,521 books · 342 highlights · 12 bookmarks. Includes the search index (1,480 books), covers and 3 imported books." */
private fun describe(m: FullBackupManifest): String {
  val counts = listOf(count(m.counts.books, "book"), count(m.counts.highlights, "highlight"), count(m.counts.bookmarks, "bookmark")).joinToString(" · ")
  val parts = listOfNotNull(
    if (m.contents.index) "the search index (${count(m.counts.indexedBooks, "book")})" else null,
    if (m.contents.covers && m.counts.covers > 0) count(m.counts.covers, "cover") else null,
    if (m.contents.imported && m.counts.importedBooks > 0) count(m.counts.importedBooks, "imported book") else null,
  )
  val includes = if (parts.isEmpty()) "" else " Includes ${parts.joinToString(", ").replaceLast(", ", " and ")}."
  val rebuild = when {
    !m.contents.index -> " The search index is not in it; the library is indexed again after restoring."
    !m.indexUsable(IndexDatabase.VERSION) -> " Its search index is from another version of Quire and will be rebuilt."
    else -> ""
  }
  return "$counts.$includes$rebuild"
}

private fun String.replaceLast(old: String, new: String): String =
  lastIndexOf(old).let { if (it < 0) this else substring(0, it) + new + substring(it + old.length) }

private fun count(n: Int, noun: String) = "${NumberFormat.getIntegerInstance().format(n)} $noun${if (n == 1) "" else "s"}"

private fun dateTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

/** Asks once for the notification permission backups show progress with (Android 13+); a refusal changes nothing else. */
@Composable
private fun rememberNotificationRequest(): () -> Unit {
  val context = LocalContext.current
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  return {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
}
