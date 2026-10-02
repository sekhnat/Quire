package com.quire.reader.data

import com.quire.reader.theme.ReaderTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ReadMode { Paged, Scroll }
enum class TextAlignPref { Left, Justify }

/** How the reader looks. There is one default set, and each book can override it. */
@Serializable
data class ReaderPrefs(
  val theme: ReaderTheme = ReaderTheme.Night,
  /** Index into the reader's font list. */
  val font: Int = 0,
  val fontSize: Int = 19,
  val lineHeight: Float = 1.6f,
  val margin: Int = 26,
  val align: TextAlignPref = TextAlignPref.Justify,
  val mode: ReadMode = ReadMode.Paged,
) {
  fun toJson(): String = json.encodeToString(this)

  companion object {
    const val MIN_SIZE = 13
    const val MAX_SIZE = 30
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Tolerates damaged or older JSON by falling back to the defaults. */
    fun fromJson(text: String?): ReaderPrefs? = text?.let { runCatching { json.decodeFromString<ReaderPrefs>(it) }.getOrNull() }
  }
}
