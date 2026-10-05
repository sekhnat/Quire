package com.quire.reader.data.index

/**
 * Turns what the user typed into FTS5 MATCH expressions, or says why it cannot be searched.
 *
 * The expressions are built only from tokenised words, each in double quotes, so no raw input ever reaches FTS syntax:
 * `OR`, `NEAR`, `text:x`, a leading `-` and unbalanced quotes are all live syntax in a raw MATCH string.
 * - Unquoted words are ANDed (`"a" "b"`). Hyphens, colons, stars and operator-looking words are plain text.
 * - A closed quoted run is a consecutive-token phrase (`"a b"`). An unmatched opening quote is an unfinished phrase,
 *   treated exactly as if it were closed, so results do not change when the user closes it.
 * - Only a final unquoted word that touches the end of the input is a prefix, written `"pre"*`. A trailing space or
 *   punctuation mark means the word is finished.
 * - CJK text goes to the bigram index instead ([CjkGrams]): each run of CJK characters is matched as a substring, and the
 *   runs are ANDed with the rest. A token that mixes CJK and other letters is searched by its CJK runs only.
 */
object FtsQuery {
  const val MIN_CHARS = 2

  /** Product limit, not SQLite's: [TextChunker] writes enough text around a split element for any phrase this long. */
  const val MAX_TOKENS = 64

  sealed interface Result {
    /** Fewer than [MIN_CHARS] characters (one CJK character is enough), or no word characters at all. Nothing is searched. */
    data object TooShort : Result

    /** More than [MAX_TOKENS] indexed tokens. Never truncated. */
    data object OverLimit : Result

    /**
     * [match] is the expression for the main index (`chunk_fts`), null when the query is all CJK; [cjk] the one for the
     * bigram index (`cjk_fts`), null when it has no CJK. [prefix] is the final word as typed when it is a prefix, null
     * otherwise. [phrase] is true when the query is exactly one quoted phrase of two or more tokens: the only shape that is
     * also looked for across the split of a long element.
     */
    data class Query(
      val match: String?,
      val tokenCount: Int,
      val prefix: String? = null,
      val cjk: String? = null,
      val phrase: Boolean = false,
    ) : Result {
      /** The same query with the prefix matched exactly, which costs far less when the prefix is very common. */
      fun withoutPrefix(): Query = if (prefix == null) this else copy(match = match?.removeSuffix("*"), prefix = null)
    }
  }

  fun parse(input: String): Result {
    val trimmed = input.trim()
    val anyCjk = CjkGrams.hasCjk(trimmed)
    if (trimmed.codePointCount(0, trimmed.length) < MIN_CHARS && !anyCjk) return Result.TooShort

    // Splitting on quotes alternates unquoted (even index) and quoted (odd index) runs; an unclosed last one is still odd.
    val runs = input.split('"')
    val terms = ArrayList<String>()
    val cjkTerms = ArrayList<String>()
    var prefix: String? = null
    var tokenCount = 0
    var quotedRuns = 0
    var phraseTokens = 0
    for ((index, run) in runs.withIndex()) {
      val tokens = Tokenizer.tokenize(run)
      if (tokens.isEmpty()) continue
      tokenCount += tokens.size
      val words = ArrayList<String>()
      val ends = ArrayList<Int>()
      for (t in tokens) {
        val word = run.substring(t.startChar, t.endChar)
        if (CjkGrams.hasCjk(word)) {
          CjkGrams.runs(word).forEach { cjkTerms += CjkGrams.queryTerm(it) }
        } else {
          words += word
          ends += t.endChar
        }
      }
      if (index % 2 == 1) {
        quotedRuns++
        if (words.isNotEmpty()) {
          terms += "\"" + words.joinToString(" ") + "\""
          phraseTokens = words.size
        }
      } else {
        words.forEachIndexed { i, word ->
          val isPrefix = index == runs.lastIndex && i == words.lastIndex && ends[i] == run.length
          if (isPrefix) prefix = word
          terms += if (isPrefix) "\"$word\"*" else "\"$word\""
        }
      }
    }
    if (tokenCount == 0) return Result.TooShort
    if (tokenCount > MAX_TOKENS) return Result.OverLimit
    val phrase = quotedRuns == 1 && terms.size == 1 && cjkTerms.isEmpty() && phraseTokens >= 2
    return Result.Query(
      match = terms.takeIf { it.isNotEmpty() }?.joinToString(" "),
      tokenCount = tokenCount,
      prefix = prefix,
      cjk = cjkTerms.takeIf { it.isNotEmpty() }?.joinToString(" "),
      phrase = phrase,
    )
  }
}
