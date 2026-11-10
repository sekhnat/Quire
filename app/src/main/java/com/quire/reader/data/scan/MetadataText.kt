package com.quire.reader.data.scan

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Turns the text of book metadata into what is shown. Metadata is often escaped once too often (an AO3 title stored as
 * `Silks &amp;amp; Scourges`, a description as `&amp;lt;p&amp;gt;…`), and descriptions are HTML with any HTML entity.
 */
object MetadataText {
  /** Any tag: the text is HTML. */
  private val TAG = Regex("""<\s*/?[a-zA-Z][^>]*>""")

  /** Tags descriptions are written with: still there after rendering, the HTML was escaped twice. */
  private val ESCAPED_TAG = Regex("""<\s*/?(p|br|div|span|i|b|em|strong|h[1-6]|blockquote|ul|ol|li|font|hr)\b[^>]*>""", RegexOption.IGNORE_CASE)

  private val SPACES = Regex("""[ \t \u000b\f]+""")

  private val BLOCKS = setOf(
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "hr", "ul", "ol", "tr", "table", "section",
    "article", "header", "footer", "pre", "dd", "dt", "dl",
  )
  private val SKIPPED = setOf("script", "style", "head", "title")

  /** A one-line field (title, author, series, tag): entities decoded, whitespace collapsed. Null when blank. */
  fun plain(text: String?): String? =
    text?.let { Parser.unescapeEntities(it, false) }?.replace(Regex("""\s+"""), " ")?.replace(' ', ' ')?.trim()?.takeIf { it.isNotEmpty() }

  /**
   * A description as plain text: one paragraph or line break per line, a blank line where the HTML had one (`<br><br>`).
   * Text without tags keeps its own line breaks. Null when nothing is left.
   */
  fun description(text: String?): String? {
    if (text.isNullOrBlank()) return null
    var out = render(text)
    if (ESCAPED_TAG.containsMatchIn(out)) out = render(out)
    return out.replace("\r\n", "\n").replace('\r', '\n')
      .lines().joinToString("\n") { it.replace(SPACES, " ").trim() }
      .replace(Regex("\n{3,}"), "\n\n")
      .trim()
      .takeIf { it.isNotEmpty() }
  }

  private fun render(text: String): String = if (TAG.containsMatchIn(text)) fromHtml(text) else Parser.unescapeEntities(text, false)

  /** The text of [html] with its whitespace collapsed as a browser would, and a line break around every block. */
  private fun fromHtml(html: String): String {
    val out = StringBuilder()
    fun breakLine() { if (out.isNotEmpty() && out.last() != '\n') out.append('\n') }
    var skipping: Node? = null
    NodeTraversor.traverse(object : NodeVisitor {
      override fun head(node: Node, depth: Int) {
        if (skipping != null) return
        when (node) {
          is TextNode -> when {
            (node.parent() as? Element)?.normalName() == "pre" -> out.append(node.wholeText)
            // Whitespace at the start of a line (the indentation between blocks) is not shown.
            out.isEmpty() || out.last() == '\n' -> out.append(node.text().trimStart())
            else -> out.append(node.text())
          }
          is Element -> when (node.normalName()) {
            in SKIPPED -> skipping = node
            "br" -> out.append('\n')
            in BLOCKS -> breakLine()
            else -> {}
          }
        }
      }

      override fun tail(node: Node, depth: Int) {
        if (skipping === node) { skipping = null; return }
        if (skipping == null && node is Element && node.normalName() in BLOCKS) breakLine()
      }
    }, Jsoup.parseBodyFragment(html).body())
    return out.toString()
  }
}

/** [this] with every text field passed through [MetadataText]. */
fun OpfMetadata.tidy(): OpfMetadata = copy(
  title = MetadataText.plain(title) ?: title,
  titleSort = MetadataText.plain(titleSort),
  authors = authors.mapNotNull(MetadataText::plain),
  authorSort = MetadataText.plain(authorSort),
  series = MetadataText.plain(series),
  tags = tags.mapNotNull(MetadataText::plain).distinct(),
  description = MetadataText.description(description),
)
