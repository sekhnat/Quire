package com.quire.reader.data

import com.quire.reader.reader.PublicationLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The pictures inside a book, to choose a cover from: every image its manifest lists (for a MOBI or AZW3, the images of
 * its converted copy), in the book's order, the cover first when it has one. Icons and rules are left out, and the
 * whole set is kept small enough to hold while the picker is open.
 */
object BookImages {
  /** Smaller files are ornaments, bullets and rules, not pictures. */
  const val MIN_BYTES = 4 * 1024
  const val MAX_IMAGES = 60
  const val MAX_TOTAL_BYTES = 48L * 1024 * 1024

  suspend fun read(loader: PublicationLoader, file: File): List<ByteArray> = withContext(Dispatchers.IO) {
    val opened = loader.openOnce(file).getOrNull() ?: return@withContext emptyList()
    opened.use { book ->
      val publication = book.publication
      val links = publication.resources.filter { it.mediaType?.isBitmap == true }
        .sortedByDescending { "cover" in it.rels }
      val out = ArrayList<ByteArray>()
      var total = 0L
      for (link in links) {
        if (out.size >= MAX_IMAGES || total >= MAX_TOTAL_BYTES) break
        val resource = publication.get(link) ?: continue
        val bytes = try { resource.read().getOrNull() } finally { resource.close() } ?: continue
        if (bytes.size < MIN_BYTES || total + bytes.size > MAX_TOTAL_BYTES) continue
        out += bytes
        total += bytes.size
      }
      out
    }
  }
}
