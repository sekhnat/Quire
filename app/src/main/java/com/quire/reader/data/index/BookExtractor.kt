package com.quire.reader.data.index

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse

/**
 * A publication's text elements in reading order, as the index sees them: whitespace-normalised text with its resource,
 * position and table-of-contents chapter. Pull them with [next]; [tallies] report on the HTML resources read so far.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class BookExtractor(private val publication: Publication) {
  private val content = IndexContent(publication)
  private val iterator = content.iterator
  private val chapters = chapterLabeler(publication)
  private var currentHref = ""
  private var currentOrder: ResourceOrder? = null

  /** One per HTML resource opened so far, in reading order. */
  val tallies: List<ResourceTally> get() = content.tallies

  /** The next element that has text, or null at the end of the book. */
  suspend fun next(): SourceElement? {
    while (true) {
      val element = iterator.nextOrNull() ?: return null
      val text = element as? Content.TextElement ?: continue
      val href = text.locator.href.removeFragment()
      if (href.toString() != currentHref) {
        currentHref = href.toString()
        currentOrder = if (chapters.hasAnchors(currentHref)) resourceOrder(href) else null
      }
      return text.toSource(currentOrder) ?: continue
    }
  }

  private fun chapterLabeler(publication: Publication): ChapterLabeler {
    fun Link.entries(): List<ChapterEntry> =
      listOf(ChapterEntry(url().removeFragment().toString(), url().fragment?.takeIf { it.isNotEmpty() }, title.orEmpty())) + children.flatMap { it.entries() }
    return ChapterLabeler(
      readingOrder = publication.readingOrder.map { it.url().removeFragment().toString() },
      entries = publication.tableOfContents.flatMap { it.entries() },
    )
  }

  /**
   * The document order of a resource whose chapters begin at anchors, read and parsed the way Readium's content iterator does,
   * or null when it cannot be read (the chapters then fall back to matching selector text).
   */
  private suspend fun resourceOrder(href: Url): ResourceOrder? {
    val resource = publication.get(href) ?: return null
    try {
      val bytes = resource.read().getOrElse { return null }
      return ResourceOrder.parse(String(bytes, Charsets.UTF_8))
    } finally {
      resource.close()
    }
  }

  private fun Content.TextElement.toSource(resourceOrder: ResourceOrder?): SourceElement? {
    val text = segments.joinToString("") { it.text }
    if (text.isBlank()) return null
    val css = locator.locations.otherLocations["cssSelector"] as? String
    val href = locator.href.removeFragment().toString()
    val chapter = chapters.markFor(href, css, resourceOrder)
    // Only what finds the element again is kept: its resource and progression. The stored text is the source of the
    // highlight, and navigation finds the passage by that text.
    return SourceElement(
      href = href,
      text = text,
      headingStart = isHeadingSelector(css),
      mediaType = locator.mediaType.toString(),
      resourceProgression = locator.locations.progression,
      progression = locator.locations.totalProgression ?: 0.0,
      chapter = chapter.label,
      chapterStart = chapter.startsChapter,
    )
  }
}
