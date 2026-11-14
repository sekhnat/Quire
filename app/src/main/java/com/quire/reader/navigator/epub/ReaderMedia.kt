/*
 * Copyright 2024 Quire.
 */
package com.quire.reader.navigator.epub

import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.AbsoluteUrl

/** Something on the page the reader asked to see full screen (a long press on an image, a table's expand button). */
sealed interface ReaderMedia {
  /** An image file of the book; [link] is its manifest entry. */
  data class Image(val link: Link, val alt: String?) : ReaderMedia

  /** A fragment of markup (a table, an inline SVG, an SVG image file) to show in a zoomable view of its own. */
  data class Markup(val html: String, val baseUrl: AbsoluteUrl, val styles: List<AbsoluteUrl>, val caption: String?, val table: Boolean) : ReaderMedia
}

internal object ReaderMediaParser {
  /** Larger fragments are not shown; they are not tables anyone reads on a phone. */
  const val MAX_HTML = 2 * 1024 * 1024

  /**
   * Parses a report from ContentScripts. Book content is untrusted: every URL must be one the reader serves (the
   * publication's origin), and [linkOf] must find it in the manifest. Null for anything else.
   */
  fun parse(json: String, linkOf: (AbsoluteUrl) -> Link?, isReaderUrl: (AbsoluteUrl) -> Boolean): ReaderMedia? {
    if (json.length > MAX_HTML + 64 * 1024) return null
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
    fun url(key: String) = o.optString(key).takeIf { it.startsWith("https://") }?.let { AbsoluteUrl(it) }?.takeIf(isReaderUrl)
    fun text(key: String) = o.optString(key).trim().take(500).takeIf { it.isNotEmpty() }
    val styles = o.optJSONArray("styles")?.let { a -> (0 until minOf(a.length(), 20)).mapNotNull { i -> a.optString(i).let(AbsoluteUrl::invoke)?.takeIf(isReaderUrl) } }.orEmpty()
    return when (o.optString("kind")) {
      "image" -> {
        val src = url("src") ?: return null
        val link = linkOf(src) ?: return null
        val alt = text("alt")
        if (link.mediaType?.toString()?.startsWith("image/svg") == true) {
          // An SVG file is drawn by a WebView, like inline SVG.
          ReaderMedia.Markup("""<img src="$src" alt="" style="width:100%;height:auto">""", src, emptyList(), alt, table = false)
        } else ReaderMedia.Image(link, alt)
      }
      "svg", "table" -> {
        val html = o.optString("html").takeIf { it.isNotEmpty() && it.length <= MAX_HTML } ?: return null
        val base = url("base") ?: return null
        ReaderMedia.Markup(html, base, styles, text("caption"), table = o.optString("kind") == "table")
      }
      else -> null
    }
  }
}
