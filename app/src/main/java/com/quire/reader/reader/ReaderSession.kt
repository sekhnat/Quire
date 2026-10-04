package com.quire.reader.reader

import android.graphics.Color as AndroidColor
import com.quire.reader.data.Book
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.data.index.IndexTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.SelectableNavigator
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.Url
import org.json.JSONObject

/**
 * A table-of-contents line. [progression] is how far into its file the chapter starts (0..1), worked out from
 * the anchor's place in the HTML; books that put many chapters in one file need it to tell them apart.
 */
class TocEntry(val title: String, val depth: Int, val link: Link, val file: String, val position: Int, val fragment: String?, val progression: Double = 0.0)

class SearchHit(val locator: Locator, val before: String, val hit: String, val after: String, val chapter: String)

/** Accent tint used for highlights. */
private val HIGHLIGHT_TINT = AndroidColor.argb(255, 145, 132, 217)
private val SEARCH_TINT = AndroidColor.argb(255, 233, 233, 237)

/**
 * One open book: the parsed publication, the live navigator, and everything derived from them
 * (table of contents, positions, search). Created when a book is opened and closed when the reader leaves.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderSession(
  val book: Book,
  val publication: Publication,
  /** Readium's stable "locations" (about one per 1,000 characters); the closest thing to page numbers. */
  val positions: List<Locator>,
  initialLocator: Locator?,
) {
  /** Cancelled when the session closes; holds the navigator collectors. */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private val _current = MutableStateFlow(initialLocator ?: positions.firstOrNull())
  val current: StateFlow<Locator?> = _current

  private val navigatorFlow = MutableStateFlow<EpubNavigatorFragment?>(null)
  val navigator: EpubNavigatorFragment? get() = navigatorFlow.value

  private val _toc = MutableStateFlow(flatten(publication.tableOfContents))
  val toc: List<TocEntry> get() = _toc.value
  val tocFlow: StateFlow<List<TocEntry>> = _toc

  fun attach(nav: EpubNavigatorFragment) { navigatorFlow.value = nav }
  fun detach() { navigatorFlow.value = null }

  fun onLocator(locator: Locator) { _current.value = locator }

  // ── position ─────────────────────────────────────────────────────────────

  val totalProgress: Float get() = (_current.value?.locations?.totalProgression ?: 0.0).toFloat().coerceIn(0f, 1f)
  val position: Int get() = _current.value?.locations?.position ?: 1

  fun minutesLeft(): Int = ((1 - totalProgress) * positions.size * MINUTES_PER_POSITION).toInt().coerceAtLeast(0)

  /** The table-of-contents entry for the chapter being read: the last one whose file is at or before here. */
  fun chapterIndex(locator: Locator? = _current.value): Int {
    locator ?: return -1
    val order = publication.readingOrder.map { it.url().removeFragment().toString() }
    val here = order.indexOf(locator.href.removeFragment().toString())
    val inFile = locator.locations.progression ?: 0.0
    var found = -1
    toc.forEachIndexed { i, e ->
      val at = order.indexOf(e.file)
      // Earlier files always count; in the same file, only chapters that start at or before this point.
      if (at in 0 until here || (at == here && e.progression <= inFile + PROGRESSION_SLACK)) found = i
    }
    return found
  }

  /**
   * Looks up where each anchored chapter starts inside its file, so a book with every chapter in one
   * HTML file still shows the right chapter and sensible page numbers. Runs once, in the background.
   */
  suspend fun resolveChapterAnchors() = withContext(Dispatchers.IO) {
    val entries = _toc.value
    if (entries.none { it.fragment != null }) return@withContext
    val htmlByFile = HashMap<String, String?>()
    val countByFile = positions.groupingBy { it.href.removeFragment().toString() }.eachCount()
    val firstByFile = HashMap<String, Int>().also { m -> positions.forEachIndexed { i, p -> m.putIfAbsent(p.href.removeFragment().toString(), i) } }
    val resolved = entries.map { e ->
      val fragment = e.fragment ?: return@map e
      val html = htmlByFile.getOrPut(e.file) { readResource(e.link) }
      val progression = html?.let { anchorProgression(it, fragment) } ?: return@map e
      val first = firstByFile[e.file]
      val position = if (first != null) first + (progression * (countByFile[e.file] ?: 1)).toInt() + 1 else e.position
      TocEntry(e.title, e.depth, e.link, e.file, position, e.fragment, progression)
    }
    _toc.value = resolved
  }

  private suspend fun readResource(link: Link): String? = runCatching {
    val resource = publication.get(link) ?: return@runCatching null
    try { resource.read().getOrNull()?.let { String(it, Charsets.UTF_8) } } finally { resource.close() }
  }.getOrNull()

  fun chapterTitle(locator: Locator? = _current.value): String =
    toc.getOrNull(chapterIndex(locator))?.title ?: locator?.title.orEmpty()

  // ── navigation ───────────────────────────────────────────────────────────

  /** False when already at the start/end of the book. */
  fun goForward(): Boolean = navigator?.goForward(animated = true) ?: false
  fun goBackward(): Boolean = navigator?.goBackward(animated = true) ?: false
  fun go(locator: Locator) { navigator?.go(locator, animated = false) }
  fun go(link: Link) { navigator?.go(link, animated = false) }

  /** Jumps to a point in the book given as 0..1, via the nearest position. */
  fun goToProgress(p: Float) {
    if (positions.isEmpty()) return
    go(positions[(p.coerceIn(0f, 1f) * (positions.size - 1)).toInt()])
  }

  /**
   * Jumps to a library-search [target] and underlines it. Waits for the navigator to attach and the
   * whole book to be prepared — in scroll mode preparation is eager and can legitimately take longer
   * than the old five-second page wait, so loading duration alone never triggers the fallback — then
   * reads the underline back from the page: the navigator's own `go` answers "true" even when the text
   * is not there. If the passage cannot be found after readiness, moves to the target's nearest place
   * in the book and leaves nothing underlined.
   */
  suspend fun goToTarget(target: IndexTarget): TargetOutcome {
    val nav = awaitNavigator() ?: return TargetOutcome.NoNavigator
    val locator = parseTargetLocator(target)
    if (!awaitBookReady(nav)) return TargetOutcome.NoNavigator
    if (locator != null) {
      nav.go(locator, animated = false)
      applySearchHits(listOf(SearchHit(locator, "", target.highlight, "", chapterTitle(locator))))
      if (awaitUnderline(nav, locator.href.removeFragment())) {
        nav.scrollToDecoration(SEARCH, locator.href.removeFragment())
        return TargetOutcome.Arrived
      }
      applySearchHits(emptyList())
    }
    goToProgress(target.progression.toFloat())
    return TargetOutcome.Unresolved
  }

  private suspend fun awaitNavigator(): EpubNavigatorFragment? =
    withTimeoutOrNull(NAVIGATOR_TIMEOUT_MS) { navigatorFlow.filterNotNull().first() }

  /**
   * True once the whole book is prepared: in scroll mode the eager continuous surface's readiness
   * barrier (no deadline — a slow but healthy book resolves its targets after readiness, not despite
   * its loading time), in paged mode the first loaded page. False when the book failed or the
   * navigator was replaced before becoming ready.
   */
  private suspend fun awaitBookReady(nav: EpubNavigatorFragment): Boolean =
    runCatching { nav.awaitWholeBookReadiness() }.getOrNull() == true

  /** True once the page reports at least one underline in the search group, which only happens when the text was found. */
  private suspend fun awaitUnderline(nav: EpubNavigatorFragment, href: Url): Boolean = withTimeoutOrNull(UNDERLINE_TIMEOUT_MS) {
    while (underlineCount(nav, href) == 0) delay(POLL_MS)
    true
  } == true

  /** Underlines in the search group on the resource at [href], read from its page. */
  private suspend fun underlineCount(nav: EpubNavigatorFragment, href: Url): Int =
    runCatching { withTimeoutOrNull(UNDERLINE_TIMEOUT_MS) { nav.evaluateJavascript(UNDERLINE_COUNT_JS, href) } }.getOrNull()?.trim('"')?.toIntOrNull() ?: 0

  fun firstPage() = goToProgress(0f)
  fun lastPage() = goToProgress(1f)

  // ── selection and decorations ────────────────────────────────────────────

  suspend fun currentSelection(): Locator? = (navigator as? SelectableNavigator)?.currentSelection()?.locator
  fun clearSelection() { (navigator as? SelectableNavigator)?.clearSelection() }

  suspend fun applyHighlights(highlights: List<HighlightEntity>) {
    val nav = navigator as? DecorableNavigator ?: return
    val decorations = highlights.mapNotNull { h ->
      val locator = parseLocator(h.locatorJson) ?: return@mapNotNull null
      Decoration(id = h.id.toString(), locator = locator, style = Decoration.Style.Highlight(tint = HIGHLIGHT_TINT), extras = mapOf("id" to h.id))
    }
    nav.applyDecorations(decorations, HIGHLIGHTS)
  }

  suspend fun applySearchHits(hits: List<SearchHit>) {
    val nav = navigator as? DecorableNavigator ?: return
    nav.applyDecorations(hits.mapIndexed { i, h -> Decoration(id = i.toString(), locator = h.locator, style = Decoration.Style.Underline(tint = SEARCH_TINT)) }, SEARCH)
  }

  // ── search ───────────────────────────────────────────────────────────────

  /** Streams matches for [query] through [onHits] (everything found so far), up to [MAX_HITS]. */
  suspend fun search(query: String, onHits: (List<SearchHit>) -> Unit) = withContext(Dispatchers.IO) {
    val iterator = publication.search(query) ?: return@withContext
    val hits = mutableListOf<SearchHit>()
    try {
      while (hits.size < MAX_HITS) {
        val page = iterator.next().getOrNull() ?: break
        for (l in page.locators) {
          if (hits.size >= MAX_HITS) break
          hits += SearchHit(l, snippetBefore(l.text.before.orEmpty()), l.text.highlight.orEmpty(), snippetAfter(l.text.after.orEmpty()), chapterTitle(l))
        }
        onHits(hits.toList())
      }
    } finally { iterator.close() }
  }

  fun close() {
    scope.cancel()
    navigatorFlow.value = null
    publication.close()
  }

  private fun flatten(links: List<Link>, depth: Int = 0): List<TocEntry> = links.flatMap { link ->
    val file = link.url().removeFragment().toString()
    val position = positions.indexOfFirst { it.href.removeFragment().toString() == file } + 1
    val fragment = link.url().fragment?.takeIf { it.isNotEmpty() }
    listOf(TocEntry(link.title?.trim().orEmpty().ifEmpty { "Untitled" }, depth, link, file, position, fragment)) + flatten(link.children, depth + 1)
  }

  companion object {
    const val HIGHLIGHTS = "highlights"
    const val SEARCH = "search"
    const val MAX_HITS = 300
    private const val NAVIGATOR_TIMEOUT_MS = 5_000L
    private const val UNDERLINE_TIMEOUT_MS = 2_500L
    private const val POLL_MS = 100L
    private const val UNDERLINE_COUNT_JS = "(function(){try{return window.readium.getDecorations('$SEARCH').items.length}catch(e){return 0}})()"
    const val MINUTES_PER_POSITION = Book.MINUTES_PER_PAGE
    private const val PROGRESSION_SLACK = 0.0005
    private const val CONTEXT_BEFORE = 60
    private const val CONTEXT_AFTER = 80

    /** The text before a hit, cut at a word boundary (with an ellipsis) so it never starts mid-word. */
    fun snippetBefore(text: String): String {
      val t = text.replace(Regex("\\s+"), " ")
      if (t.length <= CONTEXT_BEFORE) return t
      val cut = t.takeLast(CONTEXT_BEFORE)
      return "…" + cut.substringAfter(' ', cut)
    }

    /** The text after a hit, cut at a word boundary (with an ellipsis) so it never ends mid-word. */
    fun snippetAfter(text: String): String {
      val t = text.replace(Regex("\\s+"), " ")
      if (t.length <= CONTEXT_AFTER) return t
      val cut = t.take(CONTEXT_AFTER)
      return cut.substringBeforeLast(' ', cut) + "…"
    }

    /** Where `id="fragment"` (or `name="fragment"`) sits in the HTML, as a share of its length. */
    fun anchorProgression(html: String, fragment: String): Double? {
      if (html.isEmpty()) return null
      val needles = listOf("id=\"$fragment\"", "id='$fragment'", "name=\"$fragment\"", "name='$fragment'")
      val at = needles.map { html.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null
      return at.toDouble() / html.length
    }

    /**
     * Where to open the book for a search [target]: its locator, completed with the place it sits in the book so the
     * footer and a quick close record a sensible position. Falls back to the nearest position if the locator is unreadable.
     */
    fun targetLocator(target: IndexTarget, positions: List<Locator>): Locator? {
      val progression = target.progression.coerceIn(0.0, 1.0)
      val near = positions.getOrNull((progression * (positions.size - 1)).toInt())
      val locator = parseTargetLocator(target) ?: return near
      return locator.copy(locations = locator.locations.copy(totalProgression = progression, position = near?.locations?.position))
    }

    /**
     * A target's locator without its CSS selector. Readium looks for the text only inside the element the selector
     * names, and a selector counted from the book's source can name the wrong element in the page (it did, by two
     * paragraphs, in a Project Gutenberg book), which makes the passage unfindable. The quoted text finds it on its own.
     */
    private fun parseTargetLocator(target: IndexTarget): Locator? = parseLocator(target.locatorJson)?.let { l ->
      l.copy(locations = l.locations.copy(otherLocations = l.locations.otherLocations - "cssSelector"))
    }

    fun parseLocator(json: String?): Locator? = json?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
    fun Locator.toJsonString(): String = toJSON().toString()
  }
}
