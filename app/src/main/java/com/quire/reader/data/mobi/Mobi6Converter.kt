package com.quire.reader.data.mobi

import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Converts a MOBI 6 book. Its text is one HTML stream: links and the table of contents point at byte offsets in it
 * (`filepos`), images are numbered records (`recindex`), and `<mbp:pagebreak/>` separates the chapters, where it is split.
 */
internal class Mobi6Converter(private val book: MobiBook, private val db: PalmDatabase, private val header: MobiHeader) {
  private val title = book.metadata.title.ifEmpty { "Untitled" }

  fun convert(): EpubContent {
    val raw = readText(db, header)
    val ncx = MobiIndex.read(db, header.ncxIndex, header.charset)
    // Work on the bytes as Latin-1, one char per byte, so offsets stay byte offsets until the text is decoded.
    val bytes = String(raw, Charsets.ISO_8859_1)
    val targets = sortedSetOf<Int>()
    FILEPOS.findAll(bytes).forEach { m -> m.groupValues[1].toIntOrNull()?.let(targets::add) }
    ncx.entries.forEach { e -> e.value(1)?.takeIf { it < Int.MAX_VALUE }?.let { targets += it.toInt() } }
    val anchored = insertAnchors(bytes, targets, header.charset == Charsets.UTF_8)
    val html = Xhtml.clean(String(anchored.toByteArray(Charsets.ISO_8859_1), header.charset))
      .replace(GUIDE, "")
      .replace(PAGEBREAK_OPEN, "<$BREAK></$BREAK>")
      .replace(PAGEBREAK_CLOSE, "")

    val doc = Xhtml.parse(html)
    rewrite(doc)
    val styles = doc.head().getElementsByTag("style").map { it.data() }
    val parts = split(doc.body())
    if (parts.isEmpty()) throw MobiException("no text")
    val names = parts.indices.map { "part%04d.xhtml".format(it) }
    val idPart = HashMap<String, Int>()
    parts.forEachIndexed { i, nodes -> nodes.forEach { n -> (n as? Element)?.getAllElements()?.forEach { el -> el.id().takeIf { it.isNotEmpty() }?.let { idPart.putIfAbsent(it, i) } } } }
    parts.forEachIndexed { i, nodes ->
      for (n in nodes) {
        val links = (n as? Element)?.getElementsByAttributeValueStarting("href", "#") ?: continue
        for (a in links) {
          val target = idPart[a.attr("href").substring(1)] ?: continue
          if (target != i) a.attr("href", names[target] + a.attr("href"))
        }
      }
    }

    val documents = parts.mapIndexed { i, nodes -> EpubFile(names[i], MEDIA_XHTML, serialize(nodes, styles).toByteArray(Charsets.UTF_8)) }
    val toc = buildToc(ncx) { e -> e.value(1)?.let { pos -> idPart["filepos$pos"]?.let { names[it] + "#filepos$pos" } } }
      .ifEmpty { listOf(TocNode(title, names.first(), emptyList())) }
    return EpubContent(book, documents, emptyList(), toc)
  }

  /** MOBI markup into plain HTML: images get sources, `filepos` links become fragment links, Mobipocket tags go. */
  private fun rewrite(doc: Document) {
    for (el in doc.getAllElements().toList()) {
      if (el.tagName().startsWith("mbp:")) { if (el.parent() != null) el.unwrap(); continue }
      when (el.tagName()) {
        "img" -> {
          val n = listOf("hirecindex", "recindex", "lorecindex").firstNotNullOfOrNull { el.attr(it).trim().toIntOrNull() }
          val image = n?.let(book.resources::image)
          if (image == null) { el.remove(); continue }
          listOf("hirecindex", "recindex", "lorecindex", "src", "align").forEach(el::removeAttr)
          el.attr("src", image.href)
          if (!el.hasAttr("alt")) el.attr("alt", "")
        }
        "a" -> {
          val pos = FILEPOS_VALUE.find(el.attr("filepos"))?.value?.toIntOrNull()
          el.removeAttr("filepos")
          if (pos != null) el.attr("href", "#filepos$pos")
        }
        "p", "div", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "td", "th" -> {
          // MOBI 6 carries paragraph spacing as attributes: width is the first-line indent, height the space above.
          val style = buildList {
            if (el.tagName() == "p" || el.tagName() == "div" || el.tagName() == "blockquote") {
              cssLength(el.attr("width"))?.let { add("text-indent: $it") }
              cssLength(el.attr("height"))?.let { add("margin-top: $it") }
              el.removeAttr("width")
              el.removeAttr("height")
            }
            el.attr("align").lowercase().takeIf { it in ALIGNMENTS }?.let { add("text-align: $it") }
            el.removeAttr("align")
          }
          addStyle(el, style)
        }
        "font" -> {
          // Readium CSS scales and themes text through CSS; <font> sizes, colours and faces become a styled span.
          val style = buildList {
            fontSize(el.attr("size"))?.let { add("font-size: $it") }
            el.attr("color").takeIf { COLOR.matches(it) }?.let { add("color: $it") }
            el.attr("face").takeIf { it.isNotBlank() && !it.contains(Regex("[;{}\"<>]")) }?.let { add("font-family: $it") }
          }
          el.tagName("span")
          listOf("size", "color", "face").forEach(el::removeAttr)
          addStyle(el, style)
        }
      }
    }
  }

  private fun addStyle(el: Element, style: List<String>) {
    if (style.isEmpty()) return
    el.attr("style", (style + el.attr("style").trim().removeSuffix(";")).filter { it.isNotEmpty() }.joinToString("; "))
  }

  /** The body's content split at every page break, hoisting each break out of the elements around it. */
  private fun split(body: Element): List<List<Node>> {
    for (brk in body.getElementsByTag(BREAK).toList()) {
      var parent = brk.parent()
      while (parent != null && parent !== body) {
        val tail = parent.shallowClone().removeAttr("id")
        while (brk.nextSibling() != null) tail.appendChild(brk.nextSibling()!!)
        parent.after(brk)
        if (tail.childNodeSize() > 0) brk.after(tail)
        parent = brk.parent()
      }
    }
    val pieces = mutableListOf(mutableListOf<Node>())
    for (node in body.childNodes().toList()) {
      if (node is Element && node.tagName() == BREAK) pieces += mutableListOf<Node>() else pieces.last() += node
    }
    // A piece with nothing to show (only anchors or whitespace) joins the next, so its anchors land where they point.
    val parts = ArrayList<List<Node>>()
    var carry = ArrayList<Node>()
    for (piece in pieces) {
      carry.addAll(piece)
      if (piece.any(::hasContent)) { parts += carry; carry = ArrayList() }
    }
    if (carry.isNotEmpty()) { if (parts.isEmpty()) parts += carry else parts[parts.lastIndex] = parts.last() + carry }
    return parts
  }

  private fun hasContent(node: Node): Boolean = when (node) {
    is TextNode -> !node.isBlank
    is Element -> node.text().isNotBlank() || node.getAllElements().any { it.tagName() in VISIBLE }
    else -> false
  }

  private fun serialize(nodes: List<Node>, styles: List<String>): String {
    val doc = Xhtml.settings(Document.createShell(""))
    doc.head().appendElement("title").text(title)
    styles.forEach { doc.head().appendElement("style").attr("type", "text/css").appendChild(DataNode(it)) }
    nodes.forEach(doc.body()::appendChild)
    Xhtml.fix(doc, title)
    return Xhtml.DECLARATION + doc.outerHtml()
  }

  companion object {
    private const val BREAK = "quire-break"
    const val MEDIA_XHTML = "application/xhtml+xml"
    private val FILEPOS = Regex("""filepos\s*=\s*["']?0*(\d+)""", RegexOption.IGNORE_CASE)
    private val FILEPOS_VALUE = Regex("""\d+""")
    private val GUIDE = Regex("""<guide>.*?</guide>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val PAGEBREAK_OPEN = Regex("""<mbp:pagebreak[^>]*>""", RegexOption.IGNORE_CASE)
    private val PAGEBREAK_CLOSE = Regex("""</mbp:pagebreak\s*>""", RegexOption.IGNORE_CASE)
    private val LENGTH = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*(em|ex|pt|px|%)?\s*$""", RegexOption.IGNORE_CASE)
    private val ALIGNMENTS = setOf("left", "right", "center", "justify")
    private val COLOR = Regex("""#[0-9A-Fa-f]{3,8}|[A-Za-z]{3,20}""")
    /** What browsers make of `<font size>` 1 to 7, in ems so Readium's text size still scales it. */
    private val FONT_SIZES = listOf("0.63em", "0.82em", "1em", "1.13em", "1.5em", "2em", "3em")
    private val VISIBLE = setOf("img", "svg", "image", "object", "video", "audio", "hr", "table", "iframe")

    /** `<font size>`: 1 to 7, or relative to 3 (`+1`, `-2`). */
    fun fontSize(value: String): String? {
      val v = value.trim()
      val n = when {
        v.startsWith("+") || v.startsWith("-") -> v.toIntOrNull()?.let { 3 + it }
        else -> v.toIntOrNull()
      } ?: return null
      return FONT_SIZES[n.coerceIn(1, 7) - 1]
    }

    /** `1em`, `-27pt` and `0` as CSS lengths; a bare number counts as points, as Kindle reads it. Null for anything else. */
    fun cssLength(value: String): String? {
      val m = LENGTH.find(value) ?: return null
      val unit = m.groupValues[2].lowercase()
      return if (m.groupValues[1].toDouble() == 0.0) "0" else m.groupValues[1] + unit.ifEmpty { "pt" }
    }

    /**
     * [text] (one char per byte) with an empty anchor `filepos<N>` at each byte offset N in [targets]. An offset inside a
     * tag moves to the tag's start, one inside a UTF-8 character to the character's start, and one at a page break to
     * just after it, so the anchor lands in the chapter the break opens.
     */
    fun insertAnchors(text: String, targets: Collection<Int>, utf8: Boolean): String {
      val out = StringBuilder(text.length + targets.size * 24)
      var last = 0
      for (t in targets.sorted()) {
        var p = t.coerceIn(0, text.length)
        if (utf8) while (p in 1 until text.length && (text[p].code and 0xC0) == 0x80) p--
        val lt = text.lastIndexOf('<', p - 1)
        if (lt >= 0 && lt > text.lastIndexOf('>', p - 1)) p = lt
        if (text.regionMatches(p, "<mbp:pagebreak", 0, 14, ignoreCase = true)) text.indexOf('>', p).takeIf { it >= 0 }?.let { p = it + 1 }
        p = maxOf(p, last)
        out.append(text, last, p).append("<a id=\"filepos").append(t).append("\"></a>")
        last = p
      }
      return out.append(text, last, text.length).toString()
    }
  }
}
