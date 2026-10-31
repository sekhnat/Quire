package com.quire.reader.navigator.epub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Where text-to-speech starts reading in a chapter. Readium's content iterator only starts mid-chapter from a locator that
 * carries a `cssSelector` (a bare progression is ignored and the chapter is read from its start), so these find the block a
 * place is in and name it by its position among its siblings, from `body` down, which the iterator's HTML parser resolves
 * to the same element. Anything that goes wrong gives null, and reading then starts at the top of the chapter.
 *
 * Kept apart from the selection locators that highlights are saved from: a selector there would change what is stored.
 */
internal object SpeechStart {
  private const val HELPERS = """
    function block(el) {
      while (el && el !== document.body) {
        var d = getComputedStyle(el).display;
        if (d !== 'inline' && d !== 'inline-block' && d !== 'contents') return el;
        el = el.parentElement;
      }
      return el;
    }
    function path(el) {
      var parts = [];
      while (el && el !== document.body && el.nodeType === 1) {
        var i = 1, s = el;
        while ((s = s.previousElementSibling)) i++;
        parts.unshift(el.localName + ':nth-child(' + i + ')');
        el = el.parentElement;
      }
      return 'body' + (parts.length ? ' > ' + parts.join(' > ') : '');
    }
  """

  /** The block the selection starts in, in the frame the selection is in. */
  val SELECTION = """(function() {
    $HELPERS
    var s = window.getSelection();
    if (!s || s.rangeCount === 0) return null;
    var n = s.getRangeAt(0).startContainer;
    var el = n.nodeType === 1 ? n : n.parentElement;
    if (!el || !document.body.contains(el)) return null;
    return path(block(el));
  })()"""

  /** The first block with text whose bottom is below [top], in CSS px from the top of the document. */
  fun firstBelow(top: Double): String = """(function(top) {
    $HELPERS
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), n;
    while ((n = w.nextNode())) {
      if (!/\S/.test(n.nodeValue) || !n.parentElement) continue;
      var b = block(n.parentElement);
      if (b.getBoundingClientRect().bottom > top) return path(b);
    }
    return null;
  })($top)"""

  /** What `evaluateJavascript` answers for a script that returned a string, or null for anything else. */
  fun selectorFrom(result: String?): String? = runCatching {
    (Json.parseToJsonElement(result ?: return null) as? JsonPrimitive)?.takeIf { it.isString }?.content
  }.getOrNull()?.takeIf { it.startsWith("body") }

  fun locator(href: Url, mediaType: MediaType?, selector: String, progression: Double? = null) = Locator(
    href = href,
    mediaType = mediaType ?: MediaType.XHTML,
    locations = Locator.Locations(progression = progression, otherLocations = mapOf("cssSelector" to selector)),
  )
}
