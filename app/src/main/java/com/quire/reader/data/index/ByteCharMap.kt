package com.quire.reader.data.index

/**
 * Converts between UTF-8 byte offsets (what SQLite FTS `offsets()` reports) and Kotlin char indexes for one string.
 * Byte offsets are never char indexes: any multibyte character or surrogate pair shifts them.
 */
class ByteCharMap(text: String) {
  private val startByteOfChar = IntArray(text.length + 1)

  /** Length of the string in UTF-8 bytes. */
  val totalBytes: Int

  init {
    var byte = 0
    var i = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      val units = Character.charCount(cp)
      for (k in 0 until units) startByteOfChar[i + k] = byte
      byte += utf8Length(cp)
      i += units
    }
    startByteOfChar[text.length] = byte
    totalBytes = byte
  }

  /** UTF-8 offset at which the char at [charIndex] starts; `text.length` gives [totalBytes]. */
  fun byteOf(charIndex: Int): Int = startByteOfChar[charIndex]

  /** Index of the char whose encoding starts at [byteOffset], or null when it falls inside a character or out of range. */
  fun charIndexOrNull(byteOffset: Int): Int? {
    if (byteOffset < 0 || byteOffset > totalBytes) return null
    var lo = 0
    var hi = startByteOfChar.size - 1
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      if (startByteOfChar[mid] < byteOffset) lo = mid + 1 else hi = mid
    }
    return if (startByteOfChar[lo] == byteOffset) lo else null
  }

  /** Like [charIndexOrNull] but for offsets that must be valid. */
  fun charIndex(byteOffset: Int): Int =
    charIndexOrNull(byteOffset) ?: throw IllegalArgumentException("byte offset $byteOffset is not on a character boundary")
}
