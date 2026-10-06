package com.quire.reader.data.index

/**
 * The CJK side index. `unicode61` keeps a run of Chinese, Japanese or Korean characters as one token (there are no spaces
 * to split on), so a word inside it can never be found. For every chunk that has CJK text, `cjk_fts` stores overlapping
 * two-character grams of each run instead, and a CJK query becomes a phrase of grams, which matches exactly that substring.
 *
 * Characters are code points, so supplementary ideographs count as one. A run never crosses a chunk boundary: chunks are
 * cut between tokens, and to the tokenizer a run is (part of) one token.
 */
object CjkGrams {
  /** Han, Hiragana, Katakana and Hangul, the scripts written without spaces between words (Korean uses spaces, but attaches particles). */
  fun isCjk(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
    // The prolonged sound mark (コーヒー) is "common" script but part of the word.
    else -> codePoint == 0x30FC || codePoint == 0xFF70
  }

  fun hasCjk(text: String): Boolean {
    var i = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      if (isCjk(cp)) return true
      i += Character.charCount(cp)
    }
    return false
  }

  /** The CJK runs of [text] in order, as code point strings. */
  fun runs(text: String): List<String> {
    val out = ArrayList<String>()
    val run = StringBuilder()
    var i = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      if (isCjk(cp)) run.appendCodePoint(cp) else if (run.isNotEmpty()) { out += run.toString(); run.setLength(0) }
      i += Character.charCount(cp)
    }
    if (run.isNotEmpty()) out += run.toString()
    return out
  }

  /**
   * What `cjk_fts` indexes for [text], or null when it has no CJK: for each run, every overlapping two-character gram and
   * then the run's last character alone, so that every character starts at least one token; space separated.
   */
  fun grams(text: String): String? {
    val runs = runs(text)
    if (runs.isEmpty()) return null
    val sb = StringBuilder()
    for (run in runs) {
      val cps = run.codePoints().toArray()
      for (k in 0 until cps.size - 1) {
        if (sb.isNotEmpty()) sb.append(' ')
        sb.appendCodePoint(cps[k]).appendCodePoint(cps[k + 1])
      }
      if (sb.isNotEmpty()) sb.append(' ')
      sb.appendCodePoint(cps.last())
    }
    return sb.toString()
  }

  /**
   * The `cjk_fts` MATCH term for one query run: a phrase of its n − 1 grams, which only matches where the grams are
   * adjacent, i.e. where the run itself occurs; a single character is a prefix, which matches every gram it starts.
   */
  fun queryTerm(run: String): String {
    val cps = run.codePoints().toArray()
    require(cps.isNotEmpty()) { "empty run" }
    if (cps.size == 1) return "\"$run\"*"
    return (0 until cps.size - 1).joinToString(" ", "\"", "\"") { k -> String(cps, k, 2) }
  }
}
