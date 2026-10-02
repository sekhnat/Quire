package com.quire.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.quire.reader.R
import com.quire.reader.data.scan.FolderDiscovery
import com.quire.reader.data.scan.StoragePaths
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The in-app folder picker, replacing the system document picker: Android's picker refuses to
 * grant access to the storage root and the Download folder, but Quire holds all-files access
 * and reads every folder with plain file APIs, so it can browse shared storage itself — and
 * watch any folder, including the root and Download.
 */
@Composable
fun FolderPickerSheet(visible: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
  var current by remember { mutableStateOf<File?>(null) } // null = the storage-volume list
  LaunchedEffect(visible) { if (visible) current = null }

  // System back walks up the tree; once at the volume list it closes the sheet.
  BackHandler(enabled = visible) {
    val here = current
    when {
      here == null -> onDismiss()
      else -> current = parentOf(here) // null returns to the volume list
    }
  }

  val entries by produceState<List<File>?>(null, current) {
    value = null
    withContext(Dispatchers.IO) { value = current?.let(::listDirs) ?: FolderDiscovery.storageRoots() }
  }

  SheetHost(visible, onDismiss, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (current != null) IconBtn(Ic.ArrowLeft, { current = current?.let(::parentOf) }, Modifier.offset(x = (-8).dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
          Kicker("Choose a folder")
          QText(current?.let { StoragePaths.displayName(it.path) } ?: "Storage", 17f, weight = 500, maxLines = 1)
          QText(current?.path ?: "Internal storage and any SD cards", 11f, color = Nq.neutral500, family = QuireFonts.Mono, maxLines = 1)
        }
      }
      LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val list = entries
        when {
          list == null -> item { QText("Looking…", 12f, Modifier.padding(vertical = 12.dp), color = Nq.neutral500) }
          list.isEmpty() && current != null -> item {
            QText("This folder has no subfolders. Use it as it is.", 12f, Modifier.padding(vertical = 12.dp), color = Nq.neutral500)
          }
          else -> {
            val removable = list.count { it.path != StoragePaths.PRIMARY_ROOT }
            items(list, key = { it.path }) { dir ->
              val isVolume = current == null
              Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { current = dir }
                  .padding(horizontal = 6.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Ph(if (isVolume) R.drawable.ph_hard_drives else Ic.FolderSimple, 18.dp, if (isVolume) Nq.accent else Nq.neutral400)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                  QText(if (isVolume) volumeLabel(dir, removable) else StoragePaths.displayName(dir.path), 14f, maxLines = 1)
                  if (isVolume) QText(dir.path, 11f, color = Nq.neutral500, family = QuireFonts.Mono, maxLines = 1)
                }
                Ph(Ic.CaretRight, 12.dp, Nq.neutral500)
              }
            }
          }
        }
      }
      QButton(
        "Use this folder",
        {
          val here = current
          if (here != null) {
            onPick(here.absolutePath)
            onDismiss()
          }
        },
        Modifier.fillMaxWidth(), BtnKind.Primary, icon = Ic.CheckBold, height = 42.dp, enabled = current != null,
      )
    }
  }
}

/** Directories of [dir], hidden ones excluded, A→Z. */
private fun listDirs(dir: File): List<File> =
  dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
    .orEmpty()
    .sortedWith(compareBy { it.name.lowercase() })

/** One level up, or null when there is no browsable parent (volume roots return to the volume list). */
private fun parentOf(dir: File): File? {
  val parent = dir.parentFile ?: return null
  return if (parent.path == "/storage" || parent.path == "/storage/emulated") null else parent
}

private fun volumeLabel(volume: File, removableCount: Int): String = when {
  volume.path == StoragePaths.PRIMARY_ROOT -> "Internal storage"
  removableCount > 1 -> "SD card (${volume.name})"
  else -> "SD card"
}
