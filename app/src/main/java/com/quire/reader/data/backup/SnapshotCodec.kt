package com.quire.reader.data.backup

import kotlinx.serialization.json.Json

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
/**
 * Encodes and decodes [UserDataSnapshot] documents. Decoding is strict on purpose: a snapshot is the
 * thing a restore trusts, so a file that is not exactly what this schema promises is refused (and
 * never overwritten by a fresh snapshot) rather than partially imported.
 */
object SnapshotCodec {
  /** Unknown keys are tolerated within one schema version; nulls are omitted to keep files small. */
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
  private val elementJson = Json { ignoreUnknownKeys = true }

  sealed interface Decoded {
    data class Ok(val snapshot: UserDataSnapshot) : Decoded
    /** Not a snapshot at all: truncated, edited, or a different document. */
    data class Malformed(val reason: String) : Decoded
    /** A real snapshot written by a different schema version. */
    data class UnsupportedVersion(val found: Int) : Decoded
  }

  fun encode(snapshot: UserDataSnapshot): String = json.encodeToString(snapshot)

  fun decode(text: String): Decoded {
    val snapshot = runCatching { json.decodeFromString<UserDataSnapshot>(text) }
      .getOrElse { return Decoded.Malformed(it.message ?: "not valid JSON") }
    if (snapshot.schemaVersion != SNAPSHOT_SCHEMA_VERSION) return Decoded.UnsupportedVersion(snapshot.schemaVersion)
    return validate(snapshot)?.let(Decoded::Malformed) ?: Decoded.Ok(snapshot)
  }

  /** The first structural problem found, or null when the snapshot is valid. */
  fun validate(snapshot: UserDataSnapshot): String? {
    snapshot.settings.brightness?.takeIf { it !in 30..100 }?.let { return "brightness $it outside 30..100" }
    snapshot.settings.textSearchOrder?.takeIf { order ->
      com.quire.reader.data.index.SearchOrder.entries.none { it.name == order }
    }?.let { return "unknown text search order $it" }
    for ((index, book) in snapshot.books.withIndex()) {
      if (book.entryKey.isBlank()) return "book $index: empty entryKey"
      if (book.identityKey.isBlank()) return "book $index: empty identityKey"
      book.identity.calibreUuid?.takeIf { it.isBlank() }?.let { return "book $index: blank calibreUuid" }
      book.identity.epubUid?.takeIf { it.isBlank() }?.let { return "book $index: blank epubUid" }
      book.identity.fingerprint?.takeIf { it.isBlank() }?.let { return "book $index: blank fingerprint" }
      if (book.title.isBlank()) return "book $index: empty title"
      book.state?.let { state ->
        state.status?.takeIf { it !in VALID_STATUSES }?.let { return "book $index: unknown status $it" }
        state.progress?.takeIf { it !in 0f..1f }?.let { return "book $index: progress $it outside 0..1" }
        state.userRating?.takeIf { it !in 0..5 }?.let { return "book $index: rating $it outside 0..5" }
        state.locatorJson?.takeIf { !isJsonObject(it) }?.let { return "book $index: locator is not a JSON object" }
      }
      for (bookmark in book.bookmarks) {
        if (!isJsonObject(bookmark.locatorJson)) return "book $index: bookmark locator is not a JSON object"
        bookmark.progress.takeIf { it !in 0f..1f }?.let { return "book $index: bookmark progress ${bookmark.progress} outside 0..1" }
      }
      for (highlight in book.highlights) {
        if (!isJsonObject(highlight.locatorJson)) return "book $index: highlight locator is not a JSON object"
        highlight.progress.takeIf { it !in 0f..1f }?.let { return "book $index: highlight progress ${highlight.progress} outside 0..1" }
        highlight.noteVariants?.takeIf { it.isEmpty() }?.let { return "book $index: empty noteVariants list" }
      }
      book.userTags.firstOrNull { it.isBlank() }?.let { return "book $index: blank user tag" }
    }
    return null
  }

  private fun isJsonObject(text: String): Boolean =
    runCatching { elementJson.parseToJsonElement(text) is JsonObject }.getOrDefault(false)

  /**
   * A locator reduced to what identifies a position: the display-only `title` is dropped and object
   * keys are sorted, so locators written at different times or with different member order compare
   * equal when they point at the same place. Returns null when [raw] is not JSON.
   */
  fun canonicalLocatorJson(raw: String): String? = runCatching {
    canonicalize(elementJson.parseToJsonElement(raw)).toString()
  }.getOrNull()

  private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> buildJsonObject {
      element.entries
        .filter { it.key != "title" }
        .sortedBy { it.key }
        .forEach { (key, value) -> put(key, canonicalize(value)) }
    }
    is JsonArray -> buildJsonArray { element.forEach { add(canonicalize(it)) } }
    is JsonPrimitive -> element
  }

  /** Mirrors `BookStateEntity.STATUS_*`. */
  private val VALID_STATUSES = setOf("unread", "reading", "finished")
}
