package com.quire.reader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * How read aloud sounds. App-wide: how fast you like to be read to and which voice you picked do not depend on the book.
 * [voices] maps a language (a BCP 47 code, "en-US") to the id of the voice chosen for it, since a voice only speaks its own language.
 */
@Serializable
data class ReadAloudPrefs(
  val speed: Double = 1.0,
  val voices: Map<String, String> = emptyMap(),
) {
  fun toJson(): String = json.encodeToString(this)

  fun withVoice(language: String, id: String) = copy(voices = voices + (language to id))

  companion object {
    /** The speeds the speed button steps through, slowest first. */
    val SPEEDS = listOf(0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The next speed up from [current], wrapping from the fastest back to the slowest. */
    fun nextSpeed(current: Double): Double = SPEEDS.firstOrNull { it > current + 0.001 } ?: SPEEDS.first()

    /** Tolerates damaged or older JSON by falling back to the defaults. */
    fun fromJson(text: String?): ReadAloudPrefs? = text?.let { runCatching { json.decodeFromString<ReadAloudPrefs>(it) }.getOrNull() }
  }
}
