package com.quire.reader.data

/**
 * The colours a highlight can have. A highlight stores the entry's [ordinal], so entries are only ever appended and
 * [Violet], the colour every highlight had before there was a choice, stays first. An index this build does not know
 * (from a newer one) reads as [Violet].
 */
enum class HighlightColor(val label: String, red: Int, green: Int, blue: Int) {
  Violet("Violet", 145, 132, 217),
  Yellow("Yellow", 235, 196, 64),
  Green("Green", 104, 190, 122),
  Blue("Blue", 84, 160, 232),
  Pink("Pink", 232, 118, 166);

  /** Opaque ARGB; the navigator softens it for the page. */
  val tint: Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

  companion object {
    fun fromIndex(index: Int): HighlightColor = entries.getOrElse(index) { Violet }
  }
}
