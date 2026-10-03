package com.quire.reader.data.index

/** One indexed token, located in the original text by UTF-16 char index and by UTF-8 byte offset (as FTS `offsets()` reports). */
data class Token(val startChar: Int, val endChar: Int, val startByte: Int, val endByte: Int)

/**
 * Finds token boundaries the way SQLite's `unicode61` tokenizer does, so that query token counts and the chunker's
 * context overlap agree with what FTS indexed. It finds boundaries only; case and diacritic folding stay SQLite's job.
 *
 * A token is a run of letters, numbers or private-use characters. Apostrophes, hyphens, underscores and the `.` in
 * `3.14` all split. Combining marks U+0300..U+036F belong to the token they follow and are skipped when they lead.
 *
 * Known divergence: SQLite uses its own Unicode tables (which vary by Android version), this uses the JVM's. They agree
 * on every token of three full novels, but differ on rare characters: combining marks outside U+0300..U+036F (pointed
 * Arabic and Hebrew), emoji modifiers, and unassigned or very new code points. The chunker keeps a few spare context
 * tokens for this; no Unicode table is hard-coded here.
 */
object Tokenizer {
  fun tokenize(text: String): List<Token> {
    val out = ArrayList<Token>()
    var i = 0
    var byte = 0
    var startChar = -1
    var startByte = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      val inToken = startChar >= 0
      val belongs = if (isCombiningMark(cp)) inToken else isTokenChar(cp)
      if (belongs) {
        if (!inToken) { startChar = i; startByte = byte }
      } else if (inToken) {
        out += Token(startChar, i, startByte, byte)
        startChar = -1
      }
      i += Character.charCount(cp)
      byte += utf8Length(cp)
    }
    if (startChar >= 0) out += Token(startChar, text.length, startByte, byte)
    return out
  }

  private fun isCombiningMark(cp: Int) = cp in 0x300..0x36F

  private fun isTokenChar(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
    Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
    Character.PRIVATE_USE -> true
    else -> false
  }
}

/** Number of bytes [cp] takes in UTF-8. */
internal fun utf8Length(cp: Int): Int = when {
  cp < 0x80 -> 1
  cp < 0x800 -> 2
  cp < 0x10000 -> 3
  else -> 4
}

/** Number of bytes this string takes in UTF-8 (a lone surrogate counts as 3, as it would not encode; fine for sizing). */
internal fun String.utf8Length(): Int {
  var bytes = 0
  var i = 0
  while (i < length) {
    val cp = codePointAt(i)
    bytes += utf8Length(cp)
    i += Character.charCount(cp)
  }
  return bytes
}
