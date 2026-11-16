package com.quire.reader.data

/**
 * What a tag says about a book, as the Archive of Our Own sorts its tags. A tag without a kind (most EPUB subjects,
 * folder tags and the user's own) is a plain tag. Stored in `book_tag.kind` by [key].
 */
enum class TagKind(val key: String, val label: String, val plural: String, val letter: Char) {
  Rating("rating", "Rating", "Ratings", 'r'),
  Warning("warning", "Archive Warning", "Warnings", 'w'),
  Category("category", "Category", "Categories", 'g'),
  Fandom("fandom", "Fandom", "Fandoms", 'd'),
  Relationship("relationship", "Relationship", "Relationships", 'p'),
  Character("character", "Character", "Characters", 'h'),
  Freeform("freeform", "Additional Tag", "Additional Tags", 't'),
  Collection("collection", "Collection", "Collections", 'l');

  companion object {
    private val byKey = entries.associateBy { it.key }
    private val byLetter = entries.associateBy { it.letter }

    fun of(key: String?): TagKind? = key?.let(byKey::get)
    fun ofLetter(letter: Char): TagKind? = byLetter[letter]

    /** The order the book page and the Tags tab list the kinds in: what a work is about first, its labels after. */
    val displayOrder: List<TagKind> = listOf(Fandom, Relationship, Character, Freeform, Rating, Warning, Category, Collection)
  }
}

/**
 * AO3's fixed tags: every work has exactly one rating, at least one archive warning and any number of categories,
 * always spelled the same way. Books that have no structured AO3 preface (see
 * [com.quire.reader.data.scan.Ao3Preface]) still get these typed when they [lookLikeAo3].
 */
object Ao3Vocabulary {
  const val PUBLISHER = "Archive of Our Own"

  val ratings: Set<String> = setOf("General Audiences", "Teen And Up Audiences", "Mature", "Explicit", "Not Rated")
  val warnings: Set<String> = setOf(
    "No Archive Warnings Apply", "Choose Not To Use Archive Warnings", "Creator Chose Not To Use Archive Warnings",
    "Graphic Depictions Of Violence", "Major Character Death", "Rape/Non-Con", "Underage", "Underage Sex",
  )
  val categories: Set<String> = setOf("F/F", "F/M", "Gen", "M/M", "Multi", "Other")

  private val lowered: Map<String, TagKind> = buildMap {
    ratings.forEach { put(it.lowercase(), TagKind.Rating) }
    warnings.forEach { put(it.lowercase(), TagKind.Warning) }
    categories.forEach { put(it.lowercase(), TagKind.Category) }
  }
  private val warningsLowered = warnings.map { it.lowercase() }.toSet()

  /** The kind of one of AO3's fixed tags, ignoring case; null for any other tag. */
  fun kindOf(tag: String): TagKind? = lowered[tag.trim().lowercase()]

  /**
   * True when [tags] carry an archive warning, which only AO3 works do. "Mature" or "Other" alone are ordinary words a
   * non-AO3 book may use as tags, so they never count on their own.
   */
  fun lookLikeAo3(tags: Collection<String>): Boolean = tags.any { it.trim().lowercase() in warningsLowered }

  /** Lowercased archive warnings, for queries that pick out books from AO3. */
  val warningKeys: List<String> get() = warningsLowered.toList()
}

/** A tag read from a book, with its kind when the book says what kind of tag it is. */
data class ScannedTag(val tag: String, val kind: TagKind? = null)

/**
 * The tags to store for a scanned book: its [subjects] (from the EPUB or Calibre), typed by the AO3 preface [ao3] where it
 * lists the same tag (ignoring case), then the preface's tags the subjects lack. When the book comes from AO3 ([ao3] is
 * not null, or the subjects carry an archive warning), tags still untyped get the kind of AO3's fixed tags they match.
 */
fun scannedTags(subjects: List<String>, ao3: com.quire.reader.data.scan.Ao3Work?): List<ScannedTag> {
  val typed = LinkedHashMap<String, Pair<String, TagKind>>()
  ao3?.tags?.forEach { (tag, kind) -> typed.putIfAbsent(tag.lowercase(), tag to kind) }
  val seen = HashSet<String>()
  val out = ArrayList<ScannedTag>()
  for (s in subjects) if (seen.add(s.lowercase())) out += ScannedTag(s, typed[s.lowercase()]?.second)
  for ((key, value) in typed) if (seen.add(key)) out += ScannedTag(value.first, value.second)
  if (ao3 == null && !Ao3Vocabulary.lookLikeAo3(subjects)) return out
  return out.map { if (it.kind == null) it.copy(kind = Ao3Vocabulary.kindOf(it.tag)) else it }
}
