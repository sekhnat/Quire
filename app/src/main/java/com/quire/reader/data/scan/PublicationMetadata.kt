package com.quire.reader.data.scan

import com.quire.reader.data.mobi.MobiMetadata
import org.readium.r2.shared.publication.Publication

/** Reads the same fields a Calibre OPF would carry from the EPUB's own metadata. */
fun Publication.toOpfMetadata(): OpfMetadata {
  val m = metadata
  val series = m.belongsToSeries.firstOrNull()
  return OpfMetadata(
    title = m.title?.takeIf { it.isNotBlank() } ?: "Untitled",
    titleSort = null,
    authors = m.authors.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } },
    authorSort = m.authors.firstOrNull()?.sortAs?.takeIf { it.isNotBlank() },
    series = series?.name?.takeIf { it.isNotBlank() },
    seriesIndex = series?.position,
    tags = m.subjects.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } },
    rating = 0,
    description = m.description?.let(OpfParser::stripHtml)?.takeIf { it.isNotBlank() },
    year = m.published?.toString()?.let(OpfParser::parseYear),
    language = m.languages.firstOrNull(),
    addedAtMillis = null,
  )
}

/** The same fields from a MOBI's EXTH header. MOBI has no series; Calibre keeps that in its `metadata.opf`. */
fun MobiMetadata.toOpfMetadata(): OpfMetadata = OpfMetadata(
  title = title.takeIf { it.isNotBlank() } ?: "Untitled",
  titleSort = null,
  authors = authors,
  authorSort = null,
  series = null,
  seriesIndex = null,
  tags = subjects,
  rating = 0,
  description = description?.let(OpfParser::stripHtml)?.takeIf { it.isNotBlank() },
  year = OpfParser.parseYear(published),
  language = language,
  addedAtMillis = null,
)
