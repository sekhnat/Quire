package com.quire.reader.data.index

/** A table-of-contents line reduced to what chapter labelling needs: the file it points into, the anchor in it, and its title. */
data class ChapterEntry(val href: String, val fragment: String?, val title: String)

/** A text element's chapter: its [label], and whether it is the first element under a chapter that began inside its resource. */
data class ChapterMark(val label: String, val startsChapter: Boolean)

/**
 * Tells which table-of-contents chapter each text element falls under while a book is read in order. [readingOrder] and the
 * entries' hrefs are resource URLs without fragments; [entries] are the contents in document order, children after parents.
 *
 * A file starts under the last chapter that begins in an earlier file or at the top of this one. A chapter that begins at
 * an anchor inside the file takes over from the first element whose text comes after the anchor, judged by the
 * [ResourceOrder] of the file (see there for why selector text is not enough). Without an order, or for an element the order
 * does not know, it takes over once an element under that anchor (its CSS selector starts with `#anchor`) arrives.
 * Elements before the first anchor of a file keep the previous chapter, which is empty before any chapter has begun.
 * Feed elements in reading order.
 */
class ChapterLabeler(readingOrder: List<String>, entries: List<ChapterEntry>) {
  private val order = readingOrder.withIndex().associate { it.value to it.index }
  private val entries = entries.filter { it.title.isNotBlank() }.map { it.copy(title = it.title.trim()) }
  private val anchoredByHref = this.entries.withIndex().filter { !it.value.fragment.isNullOrEmpty() && it.value.href in order }.groupBy({ it.value.href }, { it.index })
  private var href: String? = null
  private var fileOrder: ResourceOrder? = null
  private var anchorPositions: List<Pair<Int, Int>> = emptyList() // (position, entry index), by position then contents order
  private var activeEntry = -1
  private var label = ""

  /** True when [href] has chapters that begin at anchors, so its [ResourceOrder] is worth building for [labelFor]. */
  fun hasAnchors(href: String): Boolean = anchoredByHref.containsKey(href)

  /** The chapter label for an element in [href] whose CSS selector is [cssSelector] (null when unknown). */
  fun labelFor(href: String, cssSelector: String?, resourceOrder: ResourceOrder? = null): String = markFor(href, cssSelector, resourceOrder).label

  /**
   * The chapter of an element in [href] with CSS selector [cssSelector]. [resourceOrder] is the parsed [href]; pass the same
   * one for every element of the file (null when not available).
   */
  fun markFor(href: String, cssSelector: String?, resourceOrder: ResourceOrder? = null): ChapterMark {
    if (href != this.href) enter(href, resourceOrder)
    val before = activeEntry
    val position = if (cssSelector != null) fileOrder?.element(cssSelector) else null
    if (position != null) {
      // The last anchor that opens at or before the element's text; the order of the contents breaks ties.
      anchorPositions.lastOrNull { it.first <= position }?.let { activate(it.second) }
    } else if (cssSelector != null) {
      anchoredByHref[href]?.firstOrNull { selectsAnchor(cssSelector, entries[it].fragment.orEmpty()) }?.let { activate(it) }
    }
    return ChapterMark(label, startsChapter = activeEntry != before)
  }

  private fun enter(href: String, resourceOrder: ResourceOrder?) {
    this.href = href
    fileOrder = resourceOrder
    activeEntry = -1
    label = labelAtFileStart(href) ?: label
    anchorPositions = resourceOrder?.let { o ->
      anchoredByHref[href].orEmpty().mapNotNull { i -> o.anchor(entries[i].fragment.orEmpty())?.let { it to i } }.sortedWith(compareBy({ it.first }, { it.second }))
    }.orEmpty()
  }

  private fun activate(entry: Int) {
    activeEntry = entry
    label = entries[entry].title
  }

  /** The chapter that begins last in an earlier file or at the top of this one: the latest file wins, the later entry within a file. */
  private fun labelAtFileStart(href: String): String? {
    val here = order[href] ?: return null
    var found: String? = null
    var foundAt = -1
    for (e in entries) {
      val at = order[e.href] ?: continue
      if ((at < here || (at == here && e.fragment.isNullOrEmpty())) && at >= foundAt) { found = e.title; foundAt = at }
    }
    return found
  }

  private fun selectsAnchor(selector: String, fragment: String): Boolean {
    if (!selector.startsWith("#$fragment")) return false
    val next = selector.getOrNull(fragment.length + 1) ?: return true
    return !(next.isLetterOrDigit() || next == '-' || next == '_')
  }
}

/** True when the element's CSS selector ends in a heading tag (`h1`..`h6`): Readium reports every element with a body role. */
fun isHeadingSelector(cssSelector: String?): Boolean {
  val last = cssSelector?.substringAfterLast('>')?.trim()?.substringAfterLast(' ') ?: return false
  return last.length >= 2 && last[0] == 'h' && last[1] in '1'..'6' && (last.length == 2 || last[2] in ":.#[")
}
