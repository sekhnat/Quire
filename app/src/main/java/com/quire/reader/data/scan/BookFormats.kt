package com.quire.reader.data.scan

import java.io.File

/**
 * The book files Quire reads: EPUB, and MOBI and AZW3, which are converted to EPUB to be read (see
 * [com.quire.reader.data.mobi.ConvertedBooks]). Calibre keeps every format of a book in one folder under one name, so
 * files that differ only in extension are one book, read from the best format there.
 */
object BookFormats {
  /** Extensions, best first: EPUB renders as published; AZW3 (KF8) keeps more of the layout than MOBI 6. */
  val EXTENSIONS = listOf("epub", "azw3", "mobi")

  /** What the system file picker is asked for. Pickers type MOBI files inconsistently, so any file is offered too. */
  val MIME_TYPES = arrayOf(
    "application/epub+zip", "application/x-mobipocket-ebook", "application/vnd.amazon.mobi8-ebook", "application/vnd.amazon.ebook", "application/octet-stream",
  )

  private fun rank(name: String): Int = EXTENSIONS.indexOf(name.substringAfterLast('.', "").lowercase())

  fun isBook(name: String): Boolean = rank(name) >= 0

  /** Whether [file] is a MOBI-family book, which Readium cannot open without conversion. */
  fun isMobi(file: File): Boolean = rank(file.name) > 0

  /** The format's display name: "EPUB", "AZW3" or "MOBI". */
  fun label(path: String): String = path.substringAfterLast('.', "").uppercase().takeIf { isBook(path) } ?: "EPUB"

  /** The book files among [files] (files, not folders), one per name: the best format when a book is there in several. */
  fun preferred(files: List<File>): List<File> =
    files.filter { !it.name.startsWith(".") && isBook(it.name) }
      .groupBy { it.name.substringBeforeLast('.').lowercase() }
      .values.map { formats -> formats.minBy { rank(it.name) } }
}
