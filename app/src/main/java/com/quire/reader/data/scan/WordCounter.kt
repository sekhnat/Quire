package com.quire.reader.data.scan

import com.quire.reader.data.mobi.MobiBook
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * Counts the words in a book's text, the way a word processor would: runs of letters and digits, with an apostrophe or
 * hyphen inside a word keeping it one word ("don't", "well-known") and a point or comma inside a number keeping it one
 * ("3.5", "1,000"). Each Chinese character and kana counts as a word, the usual convention for text without spaces.
 *
 * Feed it text with [text] or markup with [html]; markup is stripped as it goes: tags, comments, and everything inside
 * `head`, `script` and `style`. Block-level tags end a word, inline ones do not, so a drop cap (`<span>T</span>he`) stays
 * one word.
 */
class WordTally {
  var count: Int = 0
    private set
  private var inWord = false
  /** What the previous character joined: nothing, a word (an apostrophe or hyphen) or a number (a point or comma). */
  private var joined = NONE
  private var lastDigit = false

  fun text(s: CharSequence): WordTally {
    var i = 0
    while (i < s.length) {
      val cp = Character.codePointAt(s, i)
      char(cp)
      i += Character.charCount(cp)
    }
    return this
  }

  /** Ends the current word, as a block boundary or the end of a document does. */
  fun breakWord() { inWord = false; joined = NONE; lastDigit = false }

  fun char(cp: Int) {
    when {
      isIdeograph(cp) -> { count++; breakWord() }
      Character.isLetterOrDigit(cp) -> {
        val digit = Character.isDigit(cp)
        if (!inWord) { count++; inWord = true } else if (joined == NUMBER && !digit) count++ // "3.a": a point joins digits only
        joined = NONE
        lastDigit = digit
      }
      cp in IGNORED -> Unit
      isMark(cp) -> if (!inWord || joined != NONE) breakWord()
      inWord && joined == NONE && cp in JOINERS -> joined = WORD
      inWord && joined == NONE && lastDigit && cp in NUMBER_JOINERS -> joined = NUMBER
      else -> breakWord()
    }
  }

  fun html(s: String): WordTally {
    var i = 0
    val n = s.length
    while (i < n) {
      val c = s[i]
      when (c) {
        '<' -> i = tag(s, i)
        '&' -> i = entity(s, i)
        else -> {
          val cp = Character.codePointAt(s, i)
          char(cp)
          i += Character.charCount(cp)
        }
      }
    }
    breakWord()
    return this
  }

  /** Skips the markup at [start] (a `<`) and returns where the text goes on. */
  private fun tag(s: String, start: Int): Int {
    if (s.startsWith("<!--", start)) return skipPast(s, "-->", start + 4)
    if (s.startsWith("<![CDATA[", start)) {
      val end = s.indexOf("]]>", start + 9).let { if (it < 0) s.length else it }
      text(s.subSequence(start + 9, end))
      return minOf(s.length, end + 3)
    }
    if (s.startsWith("<!", start) || s.startsWith("<?", start)) return skipPast(s, ">", start + 2)
    var i = start + 1
    val closing = i < s.length && s[i] == '/'
    if (closing) i++
    val nameStart = i
    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == ':' || s[i] == '-' || s[i] == '_')) i++
    if (i == nameStart) { char('<'.code); return start + 1 } // a stray "<" in text
    val name = s.substring(nameStart, i).substringAfter(':').lowercase()
    // The end of the tag, past any quoted attribute values.
    var quote = 0.toChar()
    while (i < s.length) {
      val c = s[i]
      if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() } else if (c == '"' || c == '\'') quote = c else if (c == '>') break
      i++
    }
    val selfClosing = i > start && i - 1 < s.length && s[i - 1] == '/'
    val after = minOf(s.length, i + 1)
    if (name in BLOCK) breakWord()
    if (!closing && !selfClosing && name in SKIPPED) {
      val end = indexOfClosing(s, name, after)
      breakWord()
      return if (end < 0) s.length else skipPast(s, ">", end)
    }
    return after
  }

  /** Where `</name` starts at or after [from], ignoring case; -1 when it never does. */
  private fun indexOfClosing(s: String, name: String, from: Int): Int {
    var i = from
    while (true) {
      i = s.indexOf("</", i)
      if (i < 0) return -1
      var j = i + 2
      while (j < s.length && s[j] != '>' && !s[j].isWhitespace()) j++
      if (s.substring(i + 2, j).substringAfter(':').equals(name, ignoreCase = true)) return i
      i += 2
    }
  }

  private fun skipPast(s: String, end: String, from: Int): Int = s.indexOf(end, from).let { if (it < 0) s.length else it + end.length }

  /** Feeds the character reference at [start] (a `&`) and returns where the text goes on. */
  private fun entity(s: String, start: Int): Int {
    val semi = s.indexOf(';', start + 1)
    if (semi < 0 || semi - start > MAX_ENTITY) { char('&'.code); return start + 1 }
    val body = s.substring(start + 1, semi)
    val cp: Int? = when {
      body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)
      body.startsWith("#") -> body.substring(1).toIntOrNull()
      else -> NAMED[body] ?: if (body.isNotEmpty() && body.all { it.isLetterOrDigit() }) 'x'.code else null
    }
    if (cp == null || !Character.isValidCodePoint(cp)) { char('&'.code); return start + 1 }
    char(cp)
    return semi + 1
  }

  companion object {
    /** Words in plain [text]. */
    fun ofText(text: CharSequence): Int = WordTally().text(text).count

    /** Words in the text of [html]. */
    fun ofHtml(html: String): Int = WordTally().html(html).count

    private const val NONE = 0
    private const val WORD = 1
    private const val NUMBER = 2
    private const val MAX_ENTITY = 12
    private val JOINERS = setOf('\''.code, '’'.code, '-'.code, '‐'.code, '‑'.code)
    private val NUMBER_JOINERS = setOf('.'.code, ','.code)
    /** Soft hyphens and zero-width joiners sit inside words without splitting them. */
    private val IGNORED = setOf(0x00AD, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF)
    private val SKIPPED = setOf("head", "script", "style", "title")
    private val BLOCK = setOf(
      "address", "article", "aside", "blockquote", "body", "br", "caption", "dd", "div", "dl", "dt", "figcaption", "figure",
      "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "html", "img", "li", "main", "nav", "ol", "p", "pre",
      "section", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul", "pagebreak", "svg",
    )
    private val NAMED = mapOf(
      "amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code, "apos" to '\''.code,
      "rsquo" to '’'.code, "lsquo" to '‘'.code, "rdquo" to '”'.code, "ldquo" to '“'.code, "shy" to 0x00AD,
      "nbsp" to ' '.code, "ensp" to ' '.code, "emsp" to ' '.code, "thinsp" to ' '.code, "hellip" to '…'.code,
      "mdash" to '—'.code, "ndash" to '–'.code, "zwj" to 0x200D, "zwnj" to 0x200C,
    )

    private fun isIdeograph(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
      Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
      else -> false
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
      Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
      else -> false
    }
  }
}

/** Counts the words in a book file. */
object WordCounter {
  /**
   * The words in [file]'s text: an EPUB's spine documents in reading order, or a MOBI's or AZW3's text (read straight from
   * the file, no conversion). Null when the book cannot be read.
   */
  fun count(file: File): Int? = runCatching {
    if (BookFormats.isMobi(file)) MobiBook.open(file).use { it.countWords() } else countEpub(file)
  }.getOrNull()

  private fun countEpub(file: File): Int = ZipFile(file).use { zip ->
    val documents = spine(zip)?.takeIf { it.isNotEmpty() }
      ?: zip.entries().asSequence().filter { !it.isDirectory && it.name.lowercase().let { n -> n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm") } }
        .map { it.name }.sorted().toList()
    val tally = WordTally()
    for (name in documents) {
      val entry = zip.getEntry(name) ?: continue
      tally.html(decode(zip.getInputStream(entry).use { it.readBytes() }))
    }
    tally.count
  }

  /** Paths in the ZIP of the spine's HTML documents, in reading order; null when the package document cannot be found. */
  internal fun spine(zip: ZipFile): List<String>? {
    val container = zip.getEntry("META-INF/container.xml") ?: return null
    val root = zip.getInputStream(container).use(OpfParser::xmlRoot) ?: return null
    val rootfiles = root.getElementsByTagNameNS("*", "rootfile")
    val opfPath = (0 until rootfiles.length).asSequence().map { (rootfiles.item(it) as org.w3c.dom.Element).getAttribute("full-path") }
      .firstOrNull { it.isNotEmpty() } ?: return null
    val opf = zip.getEntry(opfPath)?.let { e -> zip.getInputStream(e).use(OpfParser::xmlRoot) } ?: return null
    val base = opfPath.substringBeforeLast('/', "")
    val items = opf.getElementsByTagNameNS("*", "item")
    val manifest = HashMap<String, String>()
    for (i in 0 until items.length) {
      val item = items.item(i) as org.w3c.dom.Element
      val type = item.getAttribute("media-type")
      if (type != "application/xhtml+xml" && type != "text/html") continue
      manifest[item.getAttribute("id")] = resolve(base, item.getAttribute("href"))
    }
    val refs = opf.getElementsByTagNameNS("*", "itemref")
    return (0 until refs.length).mapNotNull { manifest[(refs.item(it) as org.w3c.dom.Element).getAttribute("idref")] }.distinct()
  }

  /** [href] (relative to the package document's folder [base], percent-encoded) as a path in the ZIP. */
  internal fun resolve(base: String, href: String): String {
    val path = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
    val parts = ArrayList<String>()
    for (p in (if (base.isEmpty()) path else "$base/$path").split('/')) {
      when (p) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
        else -> parts += p
      }
    }
    return parts.joinToString("/")
  }

  /** A content document's text: UTF-8 unless it starts with a UTF-16 byte order mark. */
  private fun decode(bytes: ByteArray): String = when {
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    else -> String(bytes, Charsets.UTF_8)
  }
}
