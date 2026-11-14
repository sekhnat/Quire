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

  /**
   * Saves a cover the user chose for book [bookId], cropped to the 2:3 shape of a book cover around its centre, under a
   * new name each time (`user-<id>-<time>.webp`) so no image cache shows the old one. Null when [bytes] is no image.
   */
  fun saveUserCover(bytes: ByteArray, bookId: Long, now: Long): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    // Big enough to stay sharp on the book page, which shows the cover larger than the library grid.
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= USER_COVER_WIDTH) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val (x, y, w, h) = coverCrop(bmp.width, bmp.height)
    val cropped = Bitmap.createBitmap(bmp, x, y, w, h)
    val scaled = if (cropped.width > USER_COVER_WIDTH) cropped.scale(USER_COVER_WIDTH, USER_COVER_WIDTH * 3 / 2) else cropped
    val out = File(dir, "user-$bookId-$now.webp")
    val tmp = File(out.path + ".tmp")
    tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
    if (!tmp.renameTo(out)) { tmp.delete(); return null }
    out.path
  }.getOrNull()

  /** Whether [path] is a cover the user chose (kept in backups), not one read from a book. */
  fun isUserCover(path: String): Boolean = File(path).name.startsWith("user-")

  fun delete(path: String?) { if (path != null) File(path).delete() }
  fun deleteAll(paths: List<String>) = paths.forEach(::delete)

  private fun sampleSize(width: Int): Int { var s = 1; while (width / (s * 2) >= TARGET_WIDTH) s *= 2; return s }

  private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

  companion object {
    const val TARGET_WIDTH = 360
    const val USER_COVER_WIDTH = 720

    /** The largest 2:3 (portrait) rectangle in a [width] × [height] image, centred: x, y, width, height. */
    fun coverCrop(width: Int, height: Int): List<Int> {
      val w = minOf(width, height * 2 / 3).coerceAtLeast(1)
      val h = minOf(height, w * 3 / 2).coerceAtLeast(1)
      return listOf((width - w) / 2, (height - h) / 2, w, h)
    }
  }
}
