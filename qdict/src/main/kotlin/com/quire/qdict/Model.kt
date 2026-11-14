package com.quire.qdict

/** A WordNet part of speech. Satellite adjectives ("s" in WordNet) count as adjectives. */
enum class Pos(val code: Char, val label: String) {
  Noun('n', "noun"),
  Verb('v', "verb"),
  Adjective('a', "adjective"),
  Adverb('r', "adverb");

  companion object {
    fun of(code: Char): Pos? = when (code) {
      'n' -> Noun
      'v' -> Verb
      'a', 's' -> Adjective
      'r' -> Adverb
      else -> null
    }
  }
}

/** One meaning: its definition, its examples, and the headwords that share it (its synonyms). */
data class Synset(val pos: Pos, val gloss: String, val examples: List<String>, val members: List<Int>)

/**
 * A word as the dictionary lists it. [key] is lower-case with spaces between the words of a collocation ("give up");
 * [display] keeps the source's capitals ("Paris"). [senses] lists synset ids in sense order, per part of speech.
 */
data class Headword(val key: String, val display: String, val senses: Map<Pos, List<Int>>)

/** An irregular form ("ran") and the headword and part of speech it is a form of ("run", verb). */
data class IrregularForm(val form: String, val headword: Int, val pos: Pos)

/** Everything a `.qdict` file holds, in memory: what the converter writes and what verification reads back. */
data class DictionaryData(
  val release: String,
  /** Sorted by [Headword.key] in UTF-8 byte order; a headword's id is its index here. */
  val headwords: List<Headword>,
  /** Sorted by form (UTF-8 byte order), then headword id. */
  val exceptions: List<IrregularForm>,
  /** A synset's id is its index here. */
  val synsets: List<Synset>,
)

/** Compares strings in UTF-8 byte order, the order the file's keys are sorted and searched in. */
object Utf8Order : Comparator<String> {
  override fun compare(a: String, b: String): Int {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(x.size, y.size)) {
      val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
      if (d != 0) return d
    }
    return x.size - y.size
  }
}
