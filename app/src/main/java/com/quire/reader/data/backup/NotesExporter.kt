package com.quire.reader.data.backup

import com.quire.reader.data.db.BookEntity
import com.quire.reader.data.db.HighlightEntity
import com.quire.reader.reader.PublicationLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.readium.r2.shared.publication.Link
import java.io.File
import java.util.Base64
import java.net.URLEncoder

/**
 * The `identityKey` a book row answers to in snapshots and note exports: namespaced from its
 * strongest identity, with the opaque entry key as the fallback for books without any.
 */
fun identityKeyFor(book: BookEntity): String =
  identityKeyOf(snapshotIdentity(book.calibreUuid, book.epubUid, book.fingerprint), entryKeyFor(book.id, book.path))

/** A book's highlights as a Markdown file: reading order, chapter labels, notes, and a locator comment each. */
object NotesExporter {
  fun markdown(
    title: String,
    author: String,
    identityKey: String,
    highlights: List<HighlightEntity>,
    /** Chapter labels by resource href, from the book's table of contents when its file can be read. */
    chapterTitles: Map<String, String> = emptyMap(),
  ): String = buildString {
    append("# ").append(title.replace("\n", " ")).append("\n\n")
    append(author.replace("\n", " ")).append("\n")
    for (highlight in highlights.sortedWith(compareBy({ it.progress }, { it.createdAt }))) {
      append("\n## ").append(chapterOf(highlight, chapterTitles)).append("\n\n")
      append(highlight.text.lineSequence().joinToString("\n> ", prefix = "> ")).append("\n")
      for (note in NoteVariants.split(highlight.note)) {
        append("\n").append(note).append("\n")
      }
      append("\n<!-- quire://book/").append(encode(identityKey)).append("?locator=").append(base64Url(highlight.locatorJson)).append(" -->\n")
    }
  }

  private fun chapterOf(highlight: HighlightEntity, chapterTitles: Map<String, String>): String {
    // New highlights carry the chapter in their locator; old ones get the table of contents' label
    // for their file when the book can still be read, then the resource, then nothing at all.
    locatorTitle(highlight.locatorJson)?.let { return it }
    val href = hrefOf(highlight.locatorJson)
    href?.let { href -> chapterTitles[href]?.let { return it } }
    chapterLabel(highlight.locatorJson)?.let { return it }
    return "Unknown chapter"
  }

  /**
   * Chapter labels by resource href, from a readable book's table of contents. A file holding several
   * chapters is labelled by its first one; a book that cannot be read yields an empty map, and the
   * export falls back to the resource name.
   */
  suspend fun chapterTitles(loader: PublicationLoader, path: String): Map<String, String> = runCatching {
    val publication = loader.open(File(path)).getOrElse { return emptyMap() }
    try {
      val order = publication.readingOrder.map { link -> link.url().removeFragment().toString() }
      val entries = ArrayList<Pair<String, String>>()
      fun walk(links: List<Link>) {
        for (link in links) {
          entries += link.url().removeFragment().toString() to link.title.orEmpty().trim()
          walk(link.children)
        }
      }
      walk(publication.tableOfContents)
      val labels = HashMap<String, String>()
      for ((file, title) in entries.sortedBy { order.indexOf(it.first) }) {
        if (title.isNotEmpty()) labels.putIfAbsent(file, title)
      }
      labels
    } finally {
      publication.close()
    }
  }.getOrDefault(emptyMap())

  private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

  private fun base64Url(text: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

  private fun hrefOf(locatorJson: String): String? = element(locatorJson)?.let { obj ->
    (obj["href"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.substringBefore('#')?.ifEmpty { null }
  }

  private fun element(locatorJson: String): JsonObject? = runCatching {
    Json.parseToJsonElement(locatorJson) as? JsonObject
  }.getOrNull()
}
