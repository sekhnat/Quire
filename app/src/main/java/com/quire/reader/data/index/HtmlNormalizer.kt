package com.quire.reader.data.index

/**
 * Elements whose content jsoup's HTML parser reads as raw text. Written self-closing (`<title/>`, legal XHTML), they are
 * not closed in HTML mode, so everything after them, the whole body included, becomes their text. Readium's content
 * iterator parses resources that way, so a book with `<title/>` in its head yields almost no text.
 */
private val SELF_CLOSING_RAW_TEXT = Regex("""<(title|script|style|textarea|xmp|iframe|noembed|noframes)(\s[^<>]*?)?\s*/>""", RegexOption.IGNORE_CASE)

/** [html] with every self-closing raw-text element written as an open/close pair; the same instance when there is none. */
fun normalizeHtml(html: String): String {
  if (SELF_CLOSING_RAW_TEXT.find(html) == null) return html
  return SELF_CLOSING_RAW_TEXT.replace(html) { m -> "<${m.groupValues[1]}${m.groupValues[2]}></${m.groupValues[1]}>" }
}
