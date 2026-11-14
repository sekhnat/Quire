package com.quire.reader.data.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Environment
import android.provider.DocumentsContract
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

  /**
   * The file path behind a URI another app handed over, when one can be found: a `file:` URI, a storage document
   * (`primary:Books/x.epub`), a Downloads document that names its file (`raw:/storage/…`), or a media row that still
   * records its path. Null otherwise; the caller then reads the URI through the content resolver. The path is only a
   * hint to match a library book: it is never trusted to exist or to be readable.
   */
  fun pathOf(context: Context, uri: Uri): String? = runCatching {
    when (uri.scheme) {
      "file" -> uri.path
      "content" -> {
        val docId = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else null
        when {
          docId == null -> dataColumn(context, uri)
          docId.startsWith("raw:") -> docId.removePrefix("raw:").takeIf { it.startsWith("/") && ".." !in it.split('/') }
          else -> docIdToPath(docId) ?: dataColumn(context, uri)
        }
      }
      else -> null
    }
  }.getOrNull()

  /** MediaStore's deprecated `_data` column, still filled in by many providers. */
  @Suppress("DEPRECATION")
  private fun dataColumn(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use {
      if (it.moveToFirst()) it.getString(0)?.takeIf { p -> p.startsWith("/") } else null
    }

  fun treeUriToPath(uri: Uri): String? = runCatching { docIdToPath(android.provider.DocumentsContract.getTreeDocumentId(uri)) }.getOrNull()


  fun isUsableDirectory(path: String): Boolean = File(path).let { it.isDirectory && it.canRead() }

  /** Friendly name for a folder: the storage root shows as "Internal storage", not "0". */
  fun displayName(path: String): String {
    val file = File(path)
    return if (file.absolutePath == PRIMARY_ROOT) "Internal storage" else file.name.ifEmpty { path }
  }
}
