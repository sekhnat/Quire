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
  /** What the lines above and below the page show. */
  val statusLine: StatusLine = StatusLine(),
  /** The warm tint over the reader. */
  val warmLight: WarmLight = WarmLight(),
  /** A long press on a word shows its definition from the bundled dictionary. */
  val lookUpOnLongPress: Boolean = true,
  /** Auto-scroll and auto page turn speeds. */
  val autoScroll: AutoScrollPrefs = AutoScrollPrefs(),
) {
  fun toJson(): String = json.encodeToString(this)

  companion object {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Tolerates damaged or older JSON by falling back to the defaults. */
    fun fromJson(text: String?): ReaderControls? = text?.let { runCatching { json.decodeFromString<ReaderControls>(it) }.getOrNull() }
  }
}
