package com.quire.reader.data.scan

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
