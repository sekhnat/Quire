package com.quire.reader.ui

import com.quire.reader.data.Book
import com.quire.reader.data.TagKind

/** One `kind:value` term of a library search: books with a tag of [kind] whose name contains [value] (lowercased; empty: any). */
data class TagTerm(val kind: TagKind, val value: String) {
  fun matches(tag: String, tagKind: TagKind?): Boolean = tagKind == kind && (value.isEmpty() || tag.lowercase().contains(value))
}

/**
 * A library search split into its typed terms (`fandom:supergirl`, `ship:"Kara Danvers/Lena Luthor"`) and the rest of
 * the [text], which is matched as before. A word before a colon that names no kind stays part of the text.
 */
data class TagQuery(val terms: List<TagTerm>, val text: String) {
  /** True when [book] has, for every term, a tag of the term's kind containing its value. */
  fun matches(book: Book): Boolean = terms.all { t -> book.tags.any { t.matches(it, book.tagKinds[it]) } }
}

/** The words a typed term may start with, each naming the kind it searches. */
val TAG_QUERY_PREFIXES: Map<String, TagKind> = mapOf(
  "rating" to TagKind.Rating,
  "warning" to TagKind.Warning, "warnings" to TagKind.Warning,
  "category" to TagKind.Category,
  "fandom" to TagKind.Fandom,
  "relationship" to TagKind.Relationship, "ship" to TagKind.Relationship, "pairing" to TagKind.Relationship,
  "character" to TagKind.Character, "char" to TagKind.Character,
  "tag" to TagKind.Freeform, "freeform" to TagKind.Freeform,
  "collection" to TagKind.Collection,
)

private val TERM = Regex("""(?<![\p{L}\p{N}])([A-Za-z]+):(?:"([^"]*)"?|(\S*))""")

fun parseTagQuery(query: String): TagQuery {
  if (':' !in query) return TagQuery(emptyList(), query.trim())
  val terms = ArrayList<TagTerm>()
  val text = TERM.replace(query) { m ->
    val kind = TAG_QUERY_PREFIXES[m.groupValues[1].lowercase()] ?: return@replace m.value
    val value = (m.groups[2]?.value ?: m.groupValues[3]).trim().lowercase()
    terms += TagTerm(kind, value)
    " "
  }
  return TagQuery(terms, text.trim().replace(Regex("\\s+"), " "))
}
