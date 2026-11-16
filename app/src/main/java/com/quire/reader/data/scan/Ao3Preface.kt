package com.quire.reader.data.scan

import com.quire.reader.data.Ao3Vocabulary
import com.quire.reader.data.TagKind
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * What an Archive of Our Own download says about its work beyond the flat `dc:subject` list: which tag is a fandom, a
 * relationship, a character and so on, the work's series part, collections and stats.
 *
 * An AO3 EPUB's package document lists every tag as an untyped subject; its first page (the "Preface") has them as a
 * `<dl class="tags">` of `<dt>Label:</dt><dd>tags</dd>` pairs. AO3 builds the file with Calibre, which renames every
 * class, so only the label text is relied on.
 */
data class Ao3Work(
  /** Each tag with its kind, in the order the preface lists them; empty when the book has no structured preface. */
  val tags: List<Pair<String, TagKind>> = emptyList(),
  val series: String? = null,
  /** "Part 2 of …": the work's place in [series]. */
  val seriesPart: Double? = null,
  /** ISO dates (`2020-07-11`) as AO3 prints them. */
  val published: String? = null,
  val updated: String? = null,
  val completed: String? = null,
  /** AO3's own word count. */
  val words: Int? = null,
  val chaptersPosted: Int? = null,
  /** Null when the author has not said how many chapters there will be (`3/?`). */
  val chaptersTotal: Int? = null,
) {
  /** True when the preface had any stats worth storing. */
  val hasStats: Boolean get() = published != null || updated != null || completed != null || words != null || chaptersPosted != null
}

object Ao3Preface {
  /** The preface is one of the first pages; looking further only costs time on books that have none. */
  private const val PAGES_TO_SEARCH = 3
  /** A preface page is a few kilobytes; a book whose first page is huge has its preface, if any, at the start. */
  private const val MAX_PAGE_BYTES = 512 * 1024
  private const val MAX_OPF_BYTES = 1024 * 1024

  /**
   * The AO3 details of [epub]; null when the file is not an AO3 download (its publisher is not AO3 and no identifier or
   * source points at archiveofourown.org) or cannot be read. An AO3 book without a structured preface gives an empty [Ao3Work].
   */
  fun read(epub: File): Ao3Work? = runCatching {
    ZipFile(epub).use { zip ->
      val container = zip.getEntry("META-INF/container.xml") ?: return null
      val containerRoot = zip.getInputStream(container).use(OpfParser::xmlRoot) ?: return null
      val rootfiles = containerRoot.getElementsByTagNameNS("*", "rootfile")
      val opfPath = (0 until rootfiles.length).asSequence()
        .map { (rootfiles.item(it) as org.w3c.dom.Element).getAttribute("full-path") }
        .firstOrNull { it.isNotEmpty() } ?: return null
      val opfEntry = zip.getEntry(opfPath) ?: return null
      if (opfEntry.size > MAX_OPF_BYTES) return null
      val opf = zip.getInputStream(opfEntry).use(OpfParser::xmlRoot) ?: return null
      if (!isAo3(opf)) return null
      val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
      for (href in spineHrefs(opf).take(PAGES_TO_SEARCH)) {
        val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
        val entry = listOf(base + href, base + decoded, normalize(base + decoded)).firstNotNullOfOrNull(zip::getEntry) ?: continue
        val html = zip.getInputStream(entry).use { String(readAtMost(it, MAX_PAGE_BYTES), Charsets.UTF_8) }
        parsePage(html)?.let { return it }
      }
      Ao3Work()
    }
  }.getOrNull()

  /** The AO3 details in one page of HTML, or null when the page has no AO3 tag list. */
  fun parsePage(html: String): Ao3Work? {
    val doc = Jsoup.parse(html)
    val list = doc.select("dl").firstOrNull { dl -> dl.children().any { it.tagName() == "dt" && labelKey(it.text()) in TAG_LABELS } } ?: return null
    val tags = ArrayList<Pair<String, TagKind>>()
    var series: String? = null
    var seriesPart: Double? = null
    var stats: Map<String, String> = emptyMap()
    var label: String? = null
    for (child in list.children()) {
      when (child.tagName()) {
        "dt" -> label = labelKey(child.text())
        "dd" -> {
          when (val key = label) {
            null -> {}
            "series" -> if (series == null) {
              val link = child.selectFirst("a")
              series = MetadataText.plain(link?.text() ?: child.text().substringAfter(" of ", ""))?.takeIf { it.isNotBlank() }
              seriesPart = PART.find(child.text())?.groupValues?.get(1)?.toDoubleOrNull()
            }
            "stat" -> stats = parseStats(child.text())
            else -> TAG_LABELS[key]?.let { kind -> values(child).forEach { tags += it to kind } }
          }
          label = null
        }
      }
    }
    val chapters = stats["chapters"]?.let(CHAPTERS::find)
    return Ao3Work(
      tags = tags.distinct(),
      series = series,
      seriesPart = seriesPart,
      published = stats["published"],
      updated = stats["updated"],
      completed = stats["completed"],
      words = stats["words"]?.filter(Char::isDigit)?.toIntOrNull(),
      chaptersPosted = chapters?.groupValues?.get(1)?.toIntOrNull(),
      chaptersTotal = chapters?.groupValues?.get(2)?.toIntOrNull(),
    )
  }

  /** `Archive Warnings:` → `archive warning`: lowercased, without the colon or a plural `s`. */
  internal fun labelKey(text: String): String {
    val key = text.trim().removeSuffix(":").trim().lowercase()
    return when {
      key == "series" -> key
      key.endsWith("ies") -> key.dropLast(3) + "y"
      key.endsWith("s") -> key.dropLast(1)
      else -> key
    }
  }

  private val TAG_LABELS: Map<String, TagKind> = mapOf(
    "rating" to TagKind.Rating,
    "archive warning" to TagKind.Warning,
    "warning" to TagKind.Warning,
    "category" to TagKind.Category,
    "fandom" to TagKind.Fandom,
    "relationship" to TagKind.Relationship,
    "character" to TagKind.Character,
    "additional tag" to TagKind.Freeform,
    "freeform" to TagKind.Freeform,
    "collection" to TagKind.Collection,
  )

  private val PART = Regex("""Part\s+(\d+(?:\.\d+)?)\s+of""", RegexOption.IGNORE_CASE)
  private val CHAPTERS = Regex("""(\d+)\s*/\s*(\d+|\?)""")
  private val STAT = Regex("""(Published|Updated|Completed|Words|Chapters)\s*:\s*(\S+)""", RegexOption.IGNORE_CASE)

  /** `Published: 2020-07-11 Completed: 2020-08-17 Words: 59,904 Chapters: 6/6` as a map of lowercased names to values. */
  internal fun parseStats(text: String): Map<String, String> =
    STAT.findAll(text).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

  /** The tags of one `<dd>`: its links' text, or its text split on commas when it has no links. */
  private fun values(dd: Element): List<String> {
    val links = dd.select("a").map { it.text() }
    val raw = links.ifEmpty { dd.text().split(',') }
    return raw.mapNotNull { MetadataText.plain(it)?.trim()?.takeIf(String::isNotEmpty) }.distinct()
  }

  /** True when the package document says the book came from AO3. */
  private fun isAo3(opf: org.w3c.dom.Element): Boolean {
    fun texts(name: String): List<String> {
      val nodes = opf.getElementsByTagNameNS("*", name)
      return (0 until nodes.length).map { nodes.item(it).textContent.orEmpty().trim() }
    }
    if (texts("publisher").any { it.equals(Ao3Vocabulary.PUBLISHER, ignoreCase = true) }) return true
    return (texts("identifier") + texts("source")).any { it.contains("archiveofourown.org", ignoreCase = true) }
  }

  /** The hrefs of the spine's items, in reading order, relative to the package document and without any fragment. */
  private fun spineHrefs(opf: org.w3c.dom.Element): List<String> {
    val items = opf.getElementsByTagNameNS("*", "item")
    val hrefs = (0 until items.length).associate {
      val el = items.item(it) as org.w3c.dom.Element
      el.getAttribute("id") to el.getAttribute("href")
    }
    val refs = opf.getElementsByTagNameNS("*", "itemref")
    return (0 until refs.length).mapNotNull { hrefs[(refs.item(it) as org.w3c.dom.Element).getAttribute("idref")] }
      .map { it.substringBefore('#') }
      .filter { it.isNotEmpty() }
  }

  /** Up to [limit] bytes of [input]; `InputStream.readNBytes` needs API 33. */
  private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < limit) {
      val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
      if (n < 0) break
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  /** Resolves `.` and `..` segments, for spine hrefs that climb out of the package document's folder. */
  private fun normalize(path: String): String {
    val out = ArrayDeque<String>()
    for (part in path.split('/')) when (part) {
      "", "." -> {}
      ".." -> out.removeLastOrNull()
      else -> out.addLast(part)
    }
    return out.joinToString("/")
  }
}
