package com.quire.reader.ui.reader

/** How a passage fits a quote card: the text to set (shortened or not) at [size]. */
data class QuoteFit(val text: String, val size: Int, val shortened: Boolean)

/**
 * Fits a passage onto a quote card. Tries each of [sizes] (largest first) with the whole passage; when even the smallest
 * is too big, keeps as much of the passage as fits at that size, cut at a word boundary with "…". [fits] measures: does
 * this text fit the card at this size? A passage with no spaces (CJK, or one very long word) is cut between characters.
 */
fun fitQuote(text: String, sizes: List<Int>, fits: (String, Int) -> Boolean): QuoteFit {
  require(sizes.isNotEmpty())
  val passage = text.trim().replace(Regex("\\s+"), " ")
  sizes.firstOrNull { fits(passage, it) }?.let { return QuoteFit(passage, it, shortened = false) }
  val size = sizes.last()
  val words = passage.split(' ')
  fun cut(n: Int): String = words.take(n).joinToString(" ").trimEnd(',', ';', ':', '—', '–', '-') + "…"
  // The most words that fit, by halving: fitting only shrinks as words are added.
  var lo = 0
  var hi = words.size - 1
  while (lo < hi) {
    val mid = (lo + hi + 1) / 2
    if (fits(cut(mid), size)) lo = mid else hi = mid - 1
  }
  if (lo > 0) return QuoteFit(cut(lo), size, shortened = true)
  // Not even one word fits: cut the first word (or an unspaced passage) between characters.
  val chars = words.first()
  var c = 0
  var d = chars.length - 1
  while (c < d) {
    val mid = (c + d + 1) / 2
    if (fits(chars.take(mid) + "…", size)) c = mid else d = mid - 1
  }
  return QuoteFit(chars.take(c.coerceAtLeast(1)) + "…", size, shortened = true)
}

object QuoteCardSpec {
  /** Text sizes tried, in the card's own sp (it is designed 411 dp wide), largest first. */
  val SIZES = (34 downTo 22 step 2).toList()

  /** Longer selections are shared only as text. */
  const val MAX_IMAGE_CHARS = 1200

  const val WIDTH_PX = 1080
  const val MIN_HEIGHT_PX = 1080
  const val MAX_HEIGHT_PX = 1350
  const val STORY_HEIGHT_PX = 1920
}
