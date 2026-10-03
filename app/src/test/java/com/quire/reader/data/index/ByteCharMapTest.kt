package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ByteCharMapTest {
  // a (1 byte), ñ (2), 日 (3), U+1D49C (4 bytes, two chars), x (1)
  private val text = "añ日𝒜x"
  private val map = ByteCharMap(text)

  @Test fun `byte offsets map back to the char they start`() {
    assertEquals(0, map.charIndex(0))
    assertEquals(1, map.charIndex(1))
    assertEquals(2, map.charIndex(3))
    assertEquals(3, map.charIndex(6)) // the surrogate pair starts at its high surrogate
    assertEquals(5, map.charIndex(10))
    assertEquals(text.length, map.charIndex(11))
  }

  @Test fun `offsets inside a character or past the end are not boundaries`() {
    assertNull(map.charIndexOrNull(2))
    assertNull(map.charIndexOrNull(7))
    assertNull(map.charIndexOrNull(12))
    assertNull(map.charIndexOrNull(-1))
    assertThrows(IllegalArgumentException::class.java) { map.charIndex(2) }
  }

  @Test fun `chars map forward to the bytes they start at`() {
    assertEquals(11, map.totalBytes)
    assertEquals(3, map.byteOf(2))
    assertEquals(6, map.byteOf(3))
    assertEquals(11, map.byteOf(text.length))
  }

  @Test fun `slicing the original text by mapped offsets agrees with slicing its bytes`() {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val from = map.charIndex(6)
    val to = map.charIndex(10)
    assertEquals(bytes.copyOfRange(6, 10).toString(Charsets.UTF_8), text.substring(from, to))
  }
}
