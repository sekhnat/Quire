package com.quire.reader.data.index

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.PublicationServicesHolder
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.iterators.HtmlResourceContentIterator
import org.readium.r2.shared.publication.services.content.iterators.PublicationContentIterator
import org.readium.r2.shared.publication.services.content.iterators.ResourceContentIteratorFactory
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingResource

/**
 * The text of a publication for indexing: Readium's own content iterator, reading every HTML resource through
 * [normalizeHtml] and keeping a [ResourceTally] per resource. Readium alone parses `<title/>` so that the body disappears,
 * and skips a resource it cannot read without saying so; the tallies are how the indexer learns of both.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class IndexContent(publication: Publication) {
  private val factory = TrackingHtmlFactory()

  /** Every element of the reading order, from the start. */
  val iterator: Content.Iterator =
    PublicationContentIterator(publication.manifest, PublicationResources(publication), publication, null, listOf(factory))

  /** One per HTML resource opened so far, in reading order. */
  val tallies: List<ResourceTally> get() = factory.tallies
}

/** What one HTML resource came to: its size once read ([bytes], 0 until then), whether reading it failed, and the characters of text it yielded. */
class ResourceTally(val href: String) {
  var bytes = 0L
  var readFailed = false
  var yieldedChars = 0L
}

/** Readium's HTML iterator over normalised resources, tallying each one it accepts. */
@OptIn(ExperimentalReadiumApi::class)
private class TrackingHtmlFactory : ResourceContentIteratorFactory {
  private val html = HtmlResourceContentIterator.Factory()
  private val byIndex = sortedMapOf<Int, ResourceTally>()

  val tallies: List<ResourceTally> get() = byIndex.values.toList()

  override suspend fun create(
    manifest: Manifest,
    servicesHolder: PublicationServicesHolder,
    readingOrderIndex: Int,
    resource: Resource,
    mediaType: MediaType,
    locator: Locator,
  ): Content.Iterator? {
    val tally = ResourceTally(manifest.readingOrder[readingOrderIndex].url().toString())
    val iterator = html.create(manifest, servicesHolder, readingOrderIndex, NormalizingResource(resource, tally), mediaType, locator) ?: return null
    byIndex[readingOrderIndex] = tally
    return CountingIterator(iterator, tally)
  }
}

/** [resource] as Readium will parse it: repaired by [normalizeHtml], and the original bytes when nothing needed repair. */
private class NormalizingResource(resource: Resource, private val tally: ResourceTally) : TransformingResource(resource) {
  override suspend fun transform(data: Try<ByteArray, ReadError>): Try<ByteArray, ReadError> {
    val bytes = data.getOrElse { tally.readFailed = true; return data }
    tally.bytes = bytes.size.toLong()
    // Decoded as UTF-8 because that is how Readium decodes a resource before parsing it.
    val html = String(bytes, Charsets.UTF_8)
    val normalized = normalizeHtml(html)
    return if (normalized === html) data else Try.success(normalized.toByteArray(Charsets.UTF_8))
  }
}

/** Passes [inner]'s elements through, adding the text each one carries to [tally]. */
@OptIn(ExperimentalReadiumApi::class)
private class CountingIterator(private val inner: Content.Iterator, private val tally: ResourceTally) : Content.Iterator {
  override suspend fun hasNext(): Boolean = inner.hasNext()

  override fun next(): Content.Element = inner.next().also { element ->
    (element as? Content.TextElement)?.let { text -> tally.yieldedChars += text.segments.sumOf { it.text.length } }
  }

  override suspend fun hasPrevious(): Boolean = inner.hasPrevious()

  override fun previous(): Content.Element = inner.previous()
}

/** The publication's resources by URL, through the public [Publication.get]; `Publication.container` is internal to Readium. */
private class PublicationResources(private val publication: Publication) : Container<Resource> {
  override val entries: Set<Url> = (publication.readingOrder + publication.resources).map { it.url() }.toSet()

  override fun get(url: Url): Resource? = publication.get(url)

  override fun close() = Unit
}
