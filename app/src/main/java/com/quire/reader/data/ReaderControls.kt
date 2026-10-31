package com.quire.reader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How the reader holds the screen: [Auto] follows the device, the others fix it while a book is open. */
@Serializable
enum class ReaderOrientation { Auto, Portrait, Landscape }

/**
 * How the reader behaves around the page rather than on it. App-wide, not per book: they describe how this
 * person likes to hold and use the phone, so a book never overrides them.
 */
@Serializable
data class ReaderControls(
  /** Volume down turns to the next page and volume up to the previous one. */
  val volumeKeys: Boolean = false,
  val keepScreenOn: Boolean = false,
  val orientation: ReaderOrientation = ReaderOrientation.Auto,
) {
  fun toJson(): String = json.encodeToString(this)

  companion object {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Tolerates damaged or older JSON by falling back to the defaults. */
    fun fromJson(text: String?): ReaderControls? = text?.let { runCatching { json.decodeFromString<ReaderControls>(it) }.getOrNull() }
  }
}
