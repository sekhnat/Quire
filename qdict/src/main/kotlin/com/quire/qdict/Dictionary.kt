package com.quire.qdict

/**
 * WordNet's Morphy: the dictionary forms a word on the page may be. Irregular forms come from the exception table
 * ("ran" → run, "geese" → goose); regular ones from taking a known ending off, each candidate kept only when the
 * dictionary lists it ("boxes" → box, "hoping" → hope, "studies" → study).
 */
class Morphy(private val has: (String, Pos) -> Boolean, private val exceptions: (String) -> List<Pair<String, Pos>>) {

  /** Every (headword, part of speech) [word] can be, in the order noun, verb, adjective, adverb. */
  fun lemmas(word: String): List<Pair<String, Pos>> {
    val found = LinkedHashSet<Pair<String, Pos>>()
    val candidates = listOf(word, word.lowercase()).distinct()
    for (pos in Pos.entries) {
      for (w in candidates) {
        if (has(w, pos)) found += w to pos
        exceptions(w).filter { it.second == pos }.forEach { found += it }
        for ((suffix, ending) in RULES.getValue(pos)) {
          if (!w.endsWith(suffix) || w.length <= suffix.length) continue
          val base = w.dropLast(suffix.length) + ending
          if (has(base, pos)) found += base to pos
        }
      }
    }
    if (found.isEmpty() && '-' in word && !word.startsWith('-') && !word.endsWith('-')) {
      // A hyphenated word the dictionary does not list: its parts, in order.
      word.split('-').filter { it.isNotEmpty() }.forEach { part -> found += lemmas(part) }
    }
    return found.toList()
  }

  companion object {
    /** WordNet's detachment rules: an ending and what replaces it. */
    val RULES: Map<Pos, List<Pair<String, String>>> = mapOf(
      Pos.Noun to listOf("s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y"),
      Pos.Verb to listOf("s" to "", "ies" to "y", "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to ""),
      Pos.Adjective to listOf("er" to "", "est" to "", "er" to "e", "est" to "e"),
      Pos.Adverb to emptyList(),
    )
  }
}

/** One sense on the card: its definition, an example, and its synonyms (not the word itself). */
data class Sense(val gloss: String, val example: String?, val synonyms: List<String>)

/** What looking up some text found. */
sealed interface LookupResult {
  /**
   * [headword] as the dictionary lists it; [fromForm] is the text looked up when it differs ("ran" for run).
   * [senses] are grouped by part of speech, in the order noun, verb, adjective, adverb.
   */
  data class Entry(val headword: String, val fromForm: String?, val senses: Map<Pos, List<Sense>>) : LookupResult
  data class NotFound(val text: String) : LookupResult
}

/** The selection rules: what a long press's selection is looked up as, if anything. */
object Selections {
  /** Longer selections get no card. */
  const val MAX_CHARS = 48

  private val EDGE = Regex("""^[\p{P}\p{S}\s]+|[\p{P}\p{S}\s]+$""")

  /**
   * The words to look up in [selection]: curly apostrophes folded, quotes and punctuation trimmed, a possessive taken off,
   * runs of space collapsed. Null when there is nothing to look up: too long, more than five words, or no letters.
   */
  fun normalise(selection: String): String? {
    if (selection.length > MAX_CHARS) return null
    var s = selection.replace('’', '\'').replace('‘', '\'').replace(Regex("\\s+"), " ")
    s = s.replace(EDGE, "")
    s = s.removeSuffix("'s").removeSuffix("'S")
    if (s.endsWith("s'")) s = s.dropLast(1)
    if (s.isEmpty() || s.none { it.isLetter() }) return null
    if (s.split(' ').size > 5) return null
    return s
  }

  /**
   * Whether a book in [language] (a BCP 47 tag, or null when the book does not say) gets the English card. Books with no
   * language are treated as English.
   */
  fun isEnglish(language: String?): Boolean = language.isNullOrBlank() || language.substringBefore('-').substringBefore('_').equals("en", ignoreCase = true)
}

/**
 * Looks words up in a [QdictReader]: a word as it appears in a book, a collocation ("give up"), or the last word of a
 * phrase the dictionary does not list.
 */
class Dictionary(private val reader: QdictReader, private val maxSensesPerPos: Int = 40) {
  private val morphy = Morphy(
    has = { w, pos -> reader.has(w.lowercase(), pos) },
    exceptions = { form -> reader.exceptionsOf(form.lowercase()).map { (id, pos) -> reader.headwordAt(id).key to pos } },
  )

  fun lookUp(text: String): LookupResult {
    val query = Selections.normalise(text) ?: return LookupResult.NotFound(text.trim())
    entry(query)?.let { return it }
    // A phrase the dictionary does not list: the last word in it that it does.
    if (' ' in query) query.split(' ').reversed().forEach { word -> entry(word)?.let { return it } }
    return LookupResult.NotFound(query)
  }

  private fun entry(query: String): LookupResult.Entry? {
    val forms = morphy.lemmas(query.replace('_', ' '))
    if (forms.isEmpty()) return null
    val senses = LinkedHashMap<Pos, List<Sense>>()
    var headword: Headword? = null
    for ((key, pos) in forms) {
      val (id, h) = reader.headword(key.lowercase()) ?: continue
      if (headword == null) headword = h
      val ids = h.senses[pos] ?: continue
      if (senses.containsKey(pos)) continue
      senses[pos] = ids.take(maxSensesPerPos).map { synsetId ->
        val s = reader.synset(synsetId)
        // Synonyms are the other words of the meaning, never the word itself.
        Sense(s.gloss, s.examples.firstOrNull(), s.members.filter { m -> m != id }.map { reader.headwordAt(it).display }.distinct())
      }
    }
    val h = headword ?: return null
    val from = query.takeIf { !it.equals(h.display, ignoreCase = true) }
    return LookupResult.Entry(h.display, from, Pos.entries.filter { it in senses }.associateWith { senses.getValue(it) })
  }
}
