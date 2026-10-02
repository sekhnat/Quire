package com.quire.reader.data.scan

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** The parts of a Calibre `metadata.opf` that Quire uses. */
data class OpfMetadata(
  val title: String,
  val titleSort: String?,
  val authors: List<String>,
  /** `opf:file-as` of the first author, e.g. "Austen, Jane". */
  val authorSort: String?,
  val series: String?,
  val seriesIndex: Double?,
  val tags: List<String>,
  /** 0–5 (Calibre stores 0–10). */
  val rating: Int,
  val description: String?,
  val year: Int?,
  val language: String?,
  /** `calibre:timestamp`: when the book was added to the Calibre library, in epoch millis. */
  val addedAtMillis: Long?,
)

object OpfParser {
  private const val NS_OPF = "http://www.idpf.org/2007/opf"

  /** Returns null when the stream is not a usable OPF (no title, not XML). */
  fun parse(input: InputStream): OpfMetadata? {
    val doc = runCatching {
      val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        // Hardening: OPFs never need a DOCTYPE. Not every parser supports the feature, so ignore failures.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
      }
      factory.newDocumentBuilder().parse(input)
    }.getOrNull() ?: return null

    val root = doc.documentElement ?: return null
    val metadata = children(root).firstOrNull { it.localName == "metadata" } ?: return null

    var title: String? = null
    val authors = mutableListOf<String>()
    var authorSort: String? = null
    val tags = mutableListOf<String>()
    var description: String? = null
    var date: String? = null
    var language: String? = null
    val metas = mutableMapOf<String, String>()
    // OPF 3 collections: <meta property="belongs-to-collection" id="c1">Series</meta> + refines group-position.
    var collection: String? = null
    var collectionId: String? = null
    val refines = mutableMapOf<String, MutableMap<String, String>>()

    for (el in children(metadata)) {
      val text = el.textContent?.trim().orEmpty()
      when (el.localName) {
        "title" -> if (title == null && text.isNotEmpty()) title = text
        "creator" -> if (text.isNotEmpty()) {
          val role = attr(el, "role")
          if (role == null || role == "aut") {
            if (authors.isEmpty()) authorSort = attr(el, "file-as")?.takeIf { it.isNotBlank() }
            authors += text
          }
        }
        "subject" -> if (text.isNotEmpty()) tags += text
        "description" -> if (description == null && text.isNotEmpty()) description = text
        "date" -> if (date == null) date = text
        "language" -> if (language == null && text.isNotEmpty()) language = text
        "meta" -> {
          val name = el.getAttribute("name")
          if (name.isNotEmpty()) metas[name] = el.getAttribute("content")
          val property = el.getAttribute("property")
          if (property == "belongs-to-collection" && collection == null) { collection = text; collectionId = el.getAttribute("id").ifEmpty { null } }
          val ref = el.getAttribute("refines").removePrefix("#")
          if (property.isNotEmpty() && ref.isNotEmpty()) refines.getOrPut(ref) { mutableMapOf() }[property] = text
        }
      }
    }

    val finalTitle = title ?: return null
    val series = metas["calibre:series"]?.takeIf { it.isNotBlank() } ?: collection?.takeIf { it.isNotBlank() }
    val seriesIndex = metas["calibre:series_index"]?.toDoubleOrNull()
      ?: collectionId?.let { refines[it]?.get("group-position")?.toDoubleOrNull() }
    val rating = ((metas["calibre:rating"]?.toDoubleOrNull() ?: 0.0) / 2.0).let { Math.round(it).toInt() }.coerceIn(0, 5)

    return OpfMetadata(
      title = finalTitle,
      titleSort = metas["calibre:title_sort"]?.takeIf { it.isNotBlank() },
      authors = authors,
      authorSort = authorSort,
      series = series,
      seriesIndex = if (series != null) seriesIndex else null,
      tags = tags.distinct(),
      rating = rating,
      description = description?.let(::stripHtml)?.takeIf { it.isNotBlank() },
      year = parseYear(date),
      language = language,
      addedAtMillis = metas["calibre:timestamp"]?.let(::parseIsoMillis),
    )
  }

  /** Calibre writes `0101-01-01` for "no date"; anything outside a sane range is ignored. */
  internal fun parseYear(date: String?): Int? {
    val year = Regex("""^\s*(\d{4})""").find(date ?: return null)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return year.takeIf { it in 500..2200 }
  }

  internal fun parseIsoMillis(value: String): Long? = runCatching { java.time.OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli() }.getOrNull()

  /** Description fields are escaped HTML; keep just the text. */
  internal fun stripHtml(html: String): String =
    html.replace(Regex("""<\s*(br|/p|/div|/li)\s*/?>""", RegexOption.IGNORE_CASE), "\n")
      .replace(Regex("<[^>]*>"), "")
      .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
      .lines().joinToString("\n") { it.trim() }
      .replace(Regex("\n{3,}"), "\n\n")
      .trim()

  private fun attr(el: Element, name: String): String? =
    el.getAttributeNS(NS_OPF, name).takeIf { it.isNotEmpty() } ?: el.getAttribute("opf:$name").takeIf { it.isNotEmpty() } ?: el.getAttribute(name).takeIf { it.isNotEmpty() }

  private fun children(parent: Element): List<Element> {
    val out = mutableListOf<Element>()
    var node: Node? = parent.firstChild
    while (node != null) { if (node is Element) out += node; node = node.nextSibling }
    return out
  }
}
