package com.quire.reader.data.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File
import java.security.MessageDigest

/** Keeps small cover thumbnails in app storage, one file per book, named by a hash of the book's path. */
class CoverStore(context: Context) {
  private val dir = File(context.applicationContext.filesDir, "covers").apply { mkdirs() }

  fun fileFor(bookPath: String) = File(dir, md5(bookPath) + ".webp")

  fun saveFromFile(source: File, bookPath: String): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.path, bounds)
    if (bounds.outWidth <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth) }
    val bmp = BitmapFactory.decodeFile(source.path, opts) ?: return null
    saveFromBitmap(bmp, bookPath)
  }.getOrNull()

  /** Saves an encoded image (JPEG, PNG…) held in memory, such as a MOBI's cover record. */
  fun saveFromBytes(bytes: ByteArray, bookPath: String): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth) }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    saveFromBitmap(bmp, bookPath)
  }.getOrNull()

  fun saveFromBitmap(bitmap: Bitmap, bookPath: String): String? = runCatching {
    val scaled = if (bitmap.width > TARGET_WIDTH) bitmap.scale(TARGET_WIDTH, (bitmap.height * TARGET_WIDTH.toFloat() / bitmap.width).toInt().coerceAtLeast(1)) else bitmap
    val out = fileFor(bookPath)
    val tmp = File(out.path + ".tmp")
    tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
    if (!tmp.renameTo(out)) { tmp.delete(); return null }
    if (scaled !== bitmap) scaled.recycle()
    out.path
  }.getOrNull()

  fun delete(path: String?) { if (path != null) File(path).delete() }
  fun deleteAll(paths: List<String>) = paths.forEach(::delete)

  private fun sampleSize(width: Int): Int { var s = 1; while (width / (s * 2) >= TARGET_WIDTH) s *= 2; return s }

  private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

  companion object { const val TARGET_WIDTH = 360 }
}
