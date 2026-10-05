package com.quire.reader.bench.legacy

import com.quire.reader.data.index.Tokenizer

/**
 * Turns what the user typed into an FTS4 MATCH expression, or says why it cannot be searched.
 *
 * The expression is built only from tokenised words, each in double quotes, so no raw input ever reaches FTS syntax:
 * `OR`, `NEAR`, `text:x`, a leading `-` and unbalanced quotes are all live syntax in a raw MATCH string.
 * - Unquoted words are ANDed (`"a" "b"`). Hyphens, colons, stars and operator-looking words are plain text.
 * - A closed quoted run is a consecutive-token phrase (`"a b"`). An unmatched opening quote is an unfinished phrase,
 *   treated exactly as if it were closed, so results do not change when the user closes it.
 * - Only a final unquoted word that touches the end of the input is a prefix, written `"pre*"` (the star inside the
 *   quotes; `"pre"*` would silently match nothing). A trailing space or punctuation mark means the word is finished.
 */
object FtsQuery {
  const val MIN_CHARS = 2

  /** Product limit, not SQLite's: [TextChunker] stores enough context after each chunk for any phrase this long. */
  const val MAX_TOKENS = 64

  sealed interface Result {
    /** Fewer than [MIN_CHARS] characters, or no word characters at all. Nothing is searched. */
    data object TooShort : Result

    /** More than [MAX_TOKENS] indexed tokens. Never truncated. */
    data object OverLimit : Result

    /**
     * [prefix] is the final word as typed when it is a prefix, null otherwise. [withoutPrefix] is the same query with that
     * word matched exactly, which costs far less when the prefix is very common.
     */
    data class Query(val match: String, val tokenCount: Int, val prefix: String? = null) : Result {
      fun withoutPrefix(): Query = if (prefix == null) this else copy(match = match.removeSuffix("*\"") + "\"", prefix = null)
    }
  }

  fun parse(input: String): Result {
    val trimmed = input.trim()
    if (trimmed.codePointCount(0, trimmed.length) < MIN_CHARS) return Result.TooShort

    // Splitting on quotes alternates unquoted (even index) and quoted (odd index) runs; an unclosed last one is still odd.
    val runs = input.split('"')
    val terms = ArrayList<String>()
    var prefix: String? = null
    var tokenCount = 0
    for ((index, run) in runs.withIndex()) {
      val tokens = Tokenizer.tokenize(run)
      if (tokens.isEmpty()) continue
      tokenCount += tokens.size
      val words = tokens.map { run.substring(it.startChar, it.endChar) }
      if (index % 2 == 1) {
        terms += words.joinToString(" ")
      } else {
        words.forEachIndexed { i, word ->
          val isPrefix = index == runs.lastIndex && i == words.lastIndex && tokens[i].endChar == run.length
          if (isPrefix) prefix = word
          terms += if (isPrefix) "$word*" else word
        }
      }
    }
    if (tokenCount == 0) return Result.TooShort
    if (tokenCount > MAX_TOKENS) return Result.OverLimit
    return Result.Query(terms.joinToString(" ") { "\"$it\"" }, tokenCount, prefix)
  }
}
