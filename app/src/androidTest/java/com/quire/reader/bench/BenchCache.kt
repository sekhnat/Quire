package com.quire.reader.bench

import com.quire.reader.data.index.SourceElement
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One book of the benchmark fixture as extracted once: its file and where its elements are cached. */
data class BenchBook(val idx: Int, val path: String, val mtime: Long, val sizeBytes: Long, val title: String, val ok: Boolean, val unreadable: Int, val elements: Int) {
  val id: Long get() = idx + 1L

  fun line() = listOf(idx, path, mtime, sizeBytes, title.replace('\t', ' '), ok, unreadable, elements).joinToString("\t")

  companion object {
    fun parse(line: String): BenchBook = line.split('\t').let { BenchBook(it[0].toInt(), it[1], it[2].toLong(), it[3].toLong(), it[4], it[5].toBoolean(), it[6].toInt(), it[7].toInt()) }
  }
}

/**
 * The extracted [SourceElement] stream of each fixture book, so that every index variant is built from exactly the same
 * text and the build times measure the index alone, not Readium.
 */
class BenchCache(private val dir: File) {
  private val manifest = File(dir, "books.tsv")

  fun file(idx: Int) = File(dir, "$idx.bin.gz")

  fun books(): List<BenchBook> = if (manifest.isFile) manifest.readLines().filter { it.isNotBlank() }.map(BenchBook::parse).sortedBy { it.idx } else emptyList()

  fun writeBooks(books: List<BenchBook>) = manifest.writeText(books.sortedBy { it.idx }.joinToString("\n") { it.line() } + "\n")

  fun write(idx: Int, elements: List<SourceElement>) {
    DataOutputStream(GZIPOutputStream(file(idx).outputStream().buffered(1 shl 16))).use { out ->
      for (e in elements) {
        out.writeBoolean(true)
        out.str(e.href); out.str(e.text); out.writeBoolean(e.headingStart); out.str(e.mediaType ?: "")
        out.writeDouble(e.resourceProgression ?: -1.0); out.writeDouble(e.progression); out.str(e.chapter); out.writeBoolean(e.chapterStart)
      }
      out.writeBoolean(false)
    }
  }

  fun read(idx: Int): List<SourceElement> = DataInputStream(GZIPInputStream(file(idx).inputStream().buffered(1 shl 16))).use { inp ->
    buildList {
      while (try { inp.readBoolean() } catch (e: EOFException) { false }) {
        val href = inp.str(); val text = inp.str(); val heading = inp.readBoolean(); val mediaType = inp.str().ifEmpty { null }
        val rp = inp.readDouble(); val p = inp.readDouble(); val chapter = inp.str(); val chapterStart = inp.readBoolean()
        add(SourceElement(href, text, heading, mediaType, rp.takeIf { it >= 0 }, p, chapter, chapterStart))
      }
    }
  }

  private fun DataOutputStream.str(s: String) { val b = s.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
  private fun DataInputStream.str(): String { val b = ByteArray(readInt()); readFully(b); return String(b, Charsets.UTF_8) }
}
