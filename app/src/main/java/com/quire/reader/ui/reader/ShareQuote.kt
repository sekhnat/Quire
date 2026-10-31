package com.quire.reader.ui.reader

/** A quote for sharing: the passage in curly quotes, then where it comes from. */
fun shareQuote(text: String, title: String, author: String): String {
  val passage = text.trim().replace(Regex("\\s+"), " ")
  val source = listOf(title, author).filter { it.isNotBlank() && it != "Unknown author" }.joinToString(", ")
  return if (source.isEmpty()) "“$passage”" else "“$passage”\n— $source"
}
