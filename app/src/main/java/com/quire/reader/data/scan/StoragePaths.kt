package com.quire.reader.data.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Environment
import android.provider.Settings
import java.io.File

/** Shared-storage helpers: all-files access, and mapping a picked folder to a plain path. */
object StoragePaths {
  const val PRIMARY_ROOT = "/storage/emulated/0"

  fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

  /** The Settings screen where the user grants "All files access" to this app. */
  fun allFilesAccessIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())

  /**
   * Maps a Storage Access Framework document id (the part after `tree/` in a picked folder's URI) to a path:
   * `primary:Books` → `/storage/emulated/0/Books`, `1A2B-3C4D:Novels` → `/storage/1A2B-3C4D/Novels`.
   * Returns null for providers that are not plain storage volumes (Drive, Downloads UI…).
   */
  fun docIdToPath(docId: String): String? {
    val colon = docId.indexOf(':')
    if (colon < 0) return null
    val volume = docId.substring(0, colon)
    val relative = docId.substring(colon + 1).trim('/')
    if (relative.split('/').any { it == ".." }) return null
    val root = when {
      volume == "primary" -> PRIMARY_ROOT
      Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(volume) -> "/storage/$volume"
      else -> return null
    }
    return if (relative.isEmpty()) root else "$root/$relative"
  }

  fun treeUriToPath(uri: Uri): String? = runCatching { docIdToPath(android.provider.DocumentsContract.getTreeDocumentId(uri)) }.getOrNull()

  fun isUsableDirectory(path: String): Boolean = File(path).let { it.isDirectory && it.canRead() }
}
