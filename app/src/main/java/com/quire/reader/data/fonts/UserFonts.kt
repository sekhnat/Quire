package com.quire.reader.data.fonts

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One file of a font the user added: its name in the user-fonts folder, its style, and its weight (a range if variable). */
@Serializable
data class UserFontFile(val name: String, val italic: Boolean = false, val minWeight: Int = 400, val maxWeight: Int = 400) {
  val weights: IntRange get() = minWeight..maxWeight
}

/** A font family the user added. [id] is never reused, so a book's stored choice can never point at another font. */
@Serializable
data class UserFont(val id: Int, val family: String, val files: List<UserFontFile>) {
  /** The index books store for this font: after every bundled font, by id. */
  val index: Int get() = USER_FONT_BASE + id

  /** The CSS family name the reader declares it under; unique, so it never clashes with a book's own fonts. */
  val cssName: String get() = "QuireUserFont$id"

  companion object {
    /** Book font indices from here up are user fonts (USER_FONT_BASE + id); below are the bundled fonts. */
    const val USER_FONT_BASE = 10_000

    fun idOf(index: Int): Int? = (index - USER_FONT_BASE).takeIf { index >= USER_FONT_BASE }
  }
}

/** The user's fonts and the next id to give, as stored in the settings and carried in snapshots. */
@Serializable
data class UserFontList(val nextId: Int = 1, val fonts: List<UserFont> = emptyList()) {
  fun toJson(): String = json.encodeToString(this)

  companion object {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun fromJson(text: String?): UserFontList? = text?.let { runCatching { json.decodeFromString<UserFontList>(it) }.getOrNull() }
  }
}

/** A font file that was read and can be added. */
data class ParsedFont(val displayName: String, val bytes: ByteArray, val info: OpenTypeInfo)

/**
 * Groups parsed files into families by their internal family name and merges them into [list]: a family already there
 * gains the new styles, a new one gets the next id. A file whose family, style and weights are already present is
 * skipped. Returns the new list and, per family touched, the files to write (named `<id>-<n>.<ext>`).
 */
fun mergeFonts(list: UserFontList, parsed: List<ParsedFont>): Pair<UserFontList, List<Pair<UserFontFile, ParsedFont>>> {
  var nextId = list.nextId
  val fonts = list.fonts.toMutableList()
  val writes = ArrayList<Pair<UserFontFile, ParsedFont>>()
  for ((family, files) in parsed.groupBy { it.info.family.trim() }) {
    val at = fonts.indexOfFirst { it.family.equals(family, ignoreCase = true) }
    val font = if (at >= 0) fonts[at] else UserFont(nextId++, family, emptyList())
    var n = (font.files.maxOfOrNull { it.name.substringAfter('-').substringBefore('.').toIntOrNull() ?: 0 } ?: -1) + 1
    val added = ArrayList<UserFontFile>()
    for (p in files) {
      val same = (font.files + added).any { it.italic == p.info.italic && it.weights == p.info.weights }
      if (same) continue
      val file = UserFontFile("${font.id}-${n++}.${p.info.extension}", p.info.italic, p.info.weights.first, p.info.weights.last)
      added += file
      writes += file to p
    }
    if (added.isEmpty()) continue
    val merged = font.copy(files = font.files + added)
    if (at >= 0) fonts[at] = merged else fonts += merged
  }
  return UserFontList(nextId, fonts) to writes
}

/** "2 fonts added · Foo.ttc isn't supported (font collections)". */
fun addFontsSummary(families: Int, rejected: List<Pair<String, FontRejection>>): String = buildList {
  if (families > 0) add(if (families == 1) "1 font added" else "$families fonts added")
  if (families == 0 && rejected.isEmpty()) add("Those fonts are already added")
  rejected.take(3).forEach { (name, why) -> add("$name isn't supported (${why.reason})") }
  if (rejected.size > 3) add("${rejected.size - 3} more not supported")
}.joinToString(" · ")
