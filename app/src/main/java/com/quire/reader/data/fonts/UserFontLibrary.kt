package com.quire.reader.data.fonts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.QuireDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * The fonts the user added: their files in `files/fonts/user` (named by Quire, never by the user) and the list in the
 * settings. A font whose files are missing (a snapshot restored without them) is left out, so books that chose it fall
 * back to the default font.
 */
class UserFontLibrary(context: Context, private val settings: SettingsStore, private val db: QuireDatabase) {
  private val app = context.applicationContext
  val dir: File = File(app.filesDir, DIR)
  private val mutex = Mutex()

  val fonts: Flow<List<UserFont>> = settings.userFonts.map { list -> list.fonts.filter { f -> f.files.isNotEmpty() && f.files.all { File(dir, it.name).isFile } } }

  /** Reads, checks and adds the picked files; returns the message to show. */
  suspend fun add(uris: List<Uri>): String = withContext(Dispatchers.IO) {
    val rejected = ArrayList<Pair<String, FontRejection>>()
    val parsed = uris.mapNotNull { uri ->
      val name = displayName(uri) ?: "A file"
      val bytes = runCatching { readCapped(uri) }.getOrNull()
      when {
        bytes == null -> { rejected += name to FontRejection.Unreadable; null }
        bytes.size > OpenTypeReader.MAX_BYTES -> { rejected += name to FontRejection.TooLarge; null }
        else -> OpenTypeReader.read(bytes).fold(
          onSuccess = { ParsedFont(name, bytes, it) },
          onFailure = { e -> rejected += name to ((e as? OpenTypeReader.FontException)?.rejection ?: FontRejection.Unreadable); null },
        )
      }
    }
    val families = mutex.withLock {
      val (merged, writes) = mergeFonts(settings.userFonts.first(), parsed)
      dir.mkdirs()
      writes.forEach { (file, font) -> File(dir, file.name).writeBytes(font.bytes) }
      settings.setUserFonts(merged)
      writes.map { it.first.name.substringBefore('-') }.distinct().size
    }
    addFontsSummary(families, rejected)
  }

  /** Removes a font and its files; books that chose it fall back to the default font. */
  suspend fun remove(id: Int) = withContext(Dispatchers.IO) {
    mutex.withLock {
      val list = settings.userFonts.first()
      val font = list.fonts.firstOrNull { it.id == id } ?: return@withLock
      settings.setUserFonts(list.copy(fonts = list.fonts - font))
      font.files.forEach { File(dir, it.name).delete() }
    }
  }

  /** How many books chose this font in their own settings (the default counts as one more when it uses it). */
  suspend fun booksUsing(font: UserFont): Int = withContext(Dispatchers.IO) { db.states().countUsingFont(font.index) }

  private fun displayName(uri: Uri): String? = runCatching {
    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
  }.onFailure { Timber.w(it, "no name for $uri") }.getOrNull()

  /** The file's bytes, or one byte more than the limit when it is too large (never reading more than that). */
  private fun readCapped(uri: Uri): ByteArray? = app.contentResolver.openInputStream(uri)?.use { input ->
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      out.write(buf, 0, n)
      total += n
      if (total > OpenTypeReader.MAX_BYTES) break
    }
    out.toByteArray()
  }

  companion object {
    const val DIR = "fonts/user"
  }
}
