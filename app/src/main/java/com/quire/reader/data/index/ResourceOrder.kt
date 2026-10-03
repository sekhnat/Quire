package com.quire.reader.data.index

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Where the elements and the anchors of one HTML resource sit in document order, so that a table-of-contents anchor can be
 * compared with the text elements Readium reports.
 *
 * Readium reports each text element with a `cssSelector` made by jsoup's `Element.cssSelector()` over `Jsoup.parse(html)`.
 * That selector names the nearest element with an id, which is rarely the anchor the contents point at (`<a id="ch2">`
 * inside a heading, an empty `<a id="x"/>` before it, an `id` on a wrapper). So the resource is parsed the same way here,
 * every element gets the same selector string, and positions are compared instead of selector text.
 *
 * A position is a counter over elements and non-blank text nodes in document order. An element sits at the position of
 * its first text, because that is where its text starts; an anchor sits where its element opens, so an element whose
 * first text comes after the anchor is under it.
 */
class ResourceOrder private constructor(
  private val anchors: Map<String, Int>,
  private val elements: Map<String, Int>,
) {
  /** Where the anchor `id` or `name` [fragment] opens, or null when the resource has none. */
  fun anchor(fragment: String): Int? = anchors[fragment]

  /** Where the text of the element selected by [cssSelector] starts, or null when no element with text has that selector. */
  fun element(cssSelector: String): Int? = elements[cssSelector]

  companion object {
    /** Resources larger than this are not parsed a second time; their chapters fall back to matching selector text. */
    const val MAX_CHARS = 8_000_000

    /** Null when [html] cannot be handled (too large or unparsable). */
    fun parse(html: String): ResourceOrder? {
      if (html.length > MAX_CHARS) return null
      return try {
        val body = Jsoup.parse(html).body()
        Builder().also { NodeTraversor.traverse(it, body) }.build()
      } catch (e: RuntimeException) {
        null
      }
    }
  }

  private class Open(val element: Element, val opens: Int) {
    var firstText = -1
  }

  private class Builder : NodeVisitor {
    private var counter = 0
    private val stack = ArrayList<Open>()
    private val anchors = HashMap<String, Int>()
    private val finished = ArrayList<Open>()

    override fun head(node: Node, depth: Int) {
      when (node) {
        is Element -> {
          val open = Open(node, counter++)
          stack += open
          node.id().takeIf { it.isNotEmpty() }?.let { anchors.putIfAbsent(it, open.opens) }
          if (node.normalName() == "a") node.attr("name").takeIf { it.isNotEmpty() }?.let { anchors.putIfAbsent(it, open.opens) }
        }
        is TextNode -> if (!node.isBlank) {
          val at = counter++
          for (i in stack.indices.reversed()) {
            if (stack[i].firstText >= 0) break
            stack[i].firstText = at
          }
        }
      }
    }

    override fun tail(node: Node, depth: Int) {
      if (node is Element) finished += stack.removeAt(stack.lastIndex)
    }

    fun build(): ResourceOrder {
      val elements = HashMap<String, Int>()
      for (open in finished) {
        if (open.firstText < 0) continue
        val selector = open.element.cssSelector()
        elements.putIfAbsent(selector, open.firstText)
      }
      return ResourceOrder(anchors, elements)
    }
  }
}
