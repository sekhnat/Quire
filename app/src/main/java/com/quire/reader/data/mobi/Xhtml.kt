package com.quire.reader.data.mobi

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.parser.ParseSettings
import org.jsoup.parser.Parser
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Turning book markup into XHTML a WebView can parse: the converted EPUB's documents are served as
 * `application/xhtml+xml`, where a single unclosed tag or undeclared prefix shows an error page instead of the chapter.
 */
internal object Xhtml {
  const val NS = "http://www.w3.org/1999/xhtml"
  private const val NS_SVG = "http://www.w3.org/2000/svg"
  private const val NS_XLINK = "http://www.w3.org/1999/xlink"
  private const val NS_EPUB = "http://www.idpf.org/2007/ops"
  const val DECLARATION = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"

  private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr", "basefont", "frame", "isindex", "keygen")
  private val SELF_CLOSING = Regex("""<([A-Za-z][\w:.-]*)(\s[^<>]*?)?\s*/>""")
  private val DOCTYPE = Regex("""<!DOCTYPE[^>\[]*(\[[^\]]*])?\s*>""", RegexOption.IGNORE_CASE)
  private val XML_DECLARATION = Regex("""^\s*<\?xml[^>]*\?>""")
  private val INVALID_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFE\\uFFFF]")
  private val KNOWN_PREFIXES = setOf("xml", "xmlns", "xlink", "epub")

  /** HTML parsing that keeps attribute case: SVG needs `viewBox`, not `viewbox`. */
  private fun parser(): Parser = Parser.htmlParser().settings(ParseSettings(false, true))

  /** [text] without the characters XML 1.0 forbids. */
  fun clean(text: String): String = INVALID_CHARS.replace(text, "")

  /** `<div/>` read as HTML opens a div that swallows the rest of the page; write non-void elements as open/close pairs. */
  fun expandSelfClosing(html: String): String = SELF_CLOSING.replace(html) { m ->
    val name = m.groupValues[1]
    if (name.lowercase() in VOID) m.value else "<$name${m.groupValues[2]}></$name>"
  }

  fun parse(html: String): Document = Jsoup.parse(expandSelfClosing(html), "", parser())

  fun settings(doc: Document): Document = doc.apply {
    outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml).charset(Charsets.UTF_8).prettyPrint(false)
  }

  /**
   * A document that is already well-formed XHTML is kept as it is, but for its self-closing elements, which are written
   * open/close (the same in XML): Readium's content iterator, and with it the text index and search in the book, parses
   * documents as HTML, where `<div/>` or `<a id="x"/>` would wrap everything after it. Anything else is reparsed as HTML
   * and rewritten.
   */
  fun document(markup: String, title: String): String {
    val text = clean(DOCTYPE.replace(markup, "")).trimStart()
    if (Regex("<html[\\s>]").containsMatchIn(text) && text.contains("xmlns=\"$NS\"") && isWellFormed(text)) {
      val kept = expandSelfClosing(text)
      return if (XML_DECLARATION.containsMatchIn(kept)) kept else DECLARATION + kept
    }
    val doc = parse(XML_DECLARATION.replace(text, ""))
    fix(doc, title)
    return DECLARATION + settings(doc).outerHtml()
  }

  /** Namespaces and names that a reparsed HTML document needs before it can be written as XML. */
  fun fix(doc: Document, title: String) {
    val html = doc.selectFirst("html") ?: doc.appendElement("html")
    html.attr("xmlns", NS)
    for (el in doc.getAllElements().toList()) {
      val prefix = el.tagName().substringBefore(':', "")
      when {
        prefix == "svg" -> el.tagName(el.tagName().substringAfter(':'))
        prefix.isNotEmpty() -> if (el.parent() != null) el.unwrap()
      }
    }
    val all = doc.getAllElements()
    val declared = KNOWN_PREFIXES + all.flatMap { el -> el.attributes().asList().map { it.key }.filter { it.startsWith("xmlns:") }.map { it.substringAfter(':') } }
    for (el in all) {
      for (key in el.attributes().asList().map { it.key }) {
        if (key.substringBefore(':', "").let { it.isNotEmpty() && it !in declared }) el.removeAttr(key)
      }
    }
    fun uses(prefix: String) = all.any { el -> el.attributes().any { it.key.startsWith("$prefix:") } }
    if (uses("epub")) html.attr("xmlns:epub", NS_EPUB)
    if (uses("xlink")) html.attr("xmlns:xlink", NS_XLINK)
    for (svg in doc.getElementsByTag("svg")) svg.attr("xmlns", NS_SVG)
    val head = doc.head()
    if (head.getElementsByTag("title").isEmpty()) head.prependElement("title").text(title)
    head.getElementsByTag("meta").filter { it.hasAttr("http-equiv") || it.hasAttr("charset") }.forEach(Element::remove)
  }

  fun isWellFormed(xml: String): Boolean = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      isExpandEntityReferences = false
      runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }
    factory.newDocumentBuilder().apply {
      setErrorHandler(object : ErrorHandler {
        override fun warning(e: SAXParseException) = Unit
        override fun error(e: SAXParseException) = throw e
        override fun fatalError(e: SAXParseException) = throw e
      })
    }.parse(InputSource(StringReader(xml)))
    true
  }.getOrDefault(false)

  /** [text] escaped for XML character data and attribute values. */
  fun escape(text: String): String = buildString(text.length) {
    for (c in clean(text)) when (c) {
      '&' -> append("&amp;")
      '<' -> append("&lt;")
      '>' -> append("&gt;")
      '"' -> append("&quot;")
      else -> append(c)
    }
  }
}
