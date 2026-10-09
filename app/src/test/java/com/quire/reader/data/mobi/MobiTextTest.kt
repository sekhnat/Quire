package com.quire.reader.data.mobi

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class MobiTextTest {
  private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
  private fun ByteArray.text() = String(this, Charsets.ISO_8859_1)

  @Test fun `PalmDOC literals, space pairs and back-references`() {
    // "abc", then a back-reference 3 bytes back for 6 bytes, then 0xC1 (" A"), then a literal run of two.
    val data = bytes('a'.code, 'b'.code, 'c'.code, 0x80, (3 shl 3) or (6 - 3), 0xC1, 0x02, 0xF0, 'z'.code)
    assertEquals("abcabcabc Aðz", PalmDoc.decompress(data, data.size).text())
  }

  @Test fun `PalmDOC back-references may overlap what they copy`() {
    val data = bytes('x'.code, 0x80, (1 shl 3) or (10 - 3))
    assertEquals("x".repeat(11), PalmDoc.decompress(data, data.size).text())
  }

  @Test fun `trailing entries are stripped, the multibyte overlap last`() {
    // Text, then two multibyte-overlap bytes (the count byte 0x01 says one more), then a 3-byte entry ending in its size.
    val record = "hello".toByteArray() + bytes(0xE4, 0x01) + bytes(0xAA, 0xBB, 0x83)
    assertEquals(5, trailingSize(record, 0b11))
    assertEquals(3, trailingSize(record, 0b10))
    assertEquals(0, trailingSize(record, 0))
  }

  @Test fun `a trailing entry's size can take several bytes`() {
    // A 130-byte entry: its size, 130, is the backward integer 0x01 0x82 (the stop bit on the first byte read back).
    val entry = ByteArray(128) { 0x55 } + bytes(0x81, 0x02)
    val record = "text".toByteArray() + entry
    assertEquals(130, trailingSize(record, 0b10))
  }

  @Test fun `forward variable-width integers`() {
    assertEquals(5L to 1, MobiIndex.forwardVwi(bytes(0x85), 0))
    assertEquals(130L to 2, MobiIndex.forwardVwi(bytes(0x01, 0x82), 0))
    assertEquals(0L to 1, MobiIndex.forwardVwi(bytes(0x80, 0x99), 0))
  }

  /**
   * A HUFF table where every first byte is a terminal 8-bit code with the same maximum, so input byte c reads phrase
   * 255 - c, and a CDIC with 256 phrases: literal letters, except phrase 0 (byte 0xFF), which is itself compressed.
   */
  @Test fun `Huffman codes read phrases, expanding compressed ones`() {
    val huff = ByteArrayOutputStream().also { out ->
      DataOutputStream(out).apply {
        writeBytes("HUFF"); writeInt(0x18); writeInt(24); writeInt(24 + 1024)
        writeInt(0); writeInt(0)
        repeat(256) { writeInt((255 shl 8) or 0x80 or 8) }
        repeat(64) { writeInt(0) }
      }
    }.toByteArray()
    val phrases = (0 until 256).map { r -> if (r == 0) bytes(0xFE, 0xFD) to false else byteArrayOf(('A'.code + r % 26).toByte()) to true }
    val cdic = ByteArrayOutputStream().also { out ->
      DataOutputStream(out).apply {
        writeBytes("CDIC"); writeInt(0x10); writeInt(256); writeInt(8)
        var off = 256 * 2
        for ((p, _) in phrases) { writeShort(off); off += 2 + p.size }
        for ((p, literal) in phrases) { writeShort(p.size or (if (literal) 0x8000 else 0)); write(p) }
      }
    }.toByteArray()
    val decoder = HuffCdic(huff, listOf(cdic))
    // 0xFE reads phrase 1 ("B"), 0xFD phrase 2 ("C"), 0x00 phrase 255 ("V"), 0xFF phrase 0, which expands to "BC".
    assertEquals("BCV", decoder.decompress(bytes(0xFE, 0xFD, 0x00), 3).text())
    assertEquals("BCV", decoder.decompress(bytes(0xFF, 0x00), 2).text())
    // The expanded phrase is kept and reused.
    assertEquals("BCBC", decoder.decompress(bytes(0xFF, 0xFF), 2).text())
  }

  @Test fun `anchors go at tag starts, character starts and after page breaks`() {
    val text = "<p>ab</p><mbp:pagebreak/><p>cd</p>"
    // Offset 1 is inside <p>; 5 is plain text; 9 is the page break; 26 is at "<p>" after it.
    assertEquals(
      "<a id=\"filepos1\"></a><p>ab<a id=\"filepos5\"></a></p><mbp:pagebreak/><a id=\"filepos9\"></a><a id=\"filepos26\"></a><p>cd</p>",
      Mobi6Converter.insertAnchors(text, listOf(26, 1, 9, 5), utf8 = false),
    )
    // "é" in UTF-8 is two bytes; an offset at its second byte moves back to its first.
    val utf8 = String("<p>é</p>".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    assertEquals(utf8.substring(0, 3) + "<a id=\"filepos4\"></a>" + utf8.substring(3), Mobi6Converter.insertAnchors(utf8, listOf(4), utf8 = true))
  }

  @Test fun `MOBI 6 lengths and font sizes become CSS`() {
    assertEquals("1em", Mobi6Converter.cssLength("1em"))
    assertEquals("-27pt", Mobi6Converter.cssLength("-27pt"))
    assertEquals("0", Mobi6Converter.cssLength("0pt"))
    assertEquals("12pt", Mobi6Converter.cssLength("12"))
    assertEquals(null, Mobi6Converter.cssLength("auto"))
    assertEquals("3em", Mobi6Converter.fontSize("7"))
    assertEquals("1.13em", Mobi6Converter.fontSize("+1"))
    assertEquals("0.63em", Mobi6Converter.fontSize("-5"))
    assertEquals(null, Mobi6Converter.fontSize("big"))
  }

  @Test fun `KF8 link fragments are percent-encoded where needed`() {
    assertEquals("ch1_a-b.c", Kf8Converter.fragment("ch1_a-b.c"))
    // UTF-8 bytes of "é", held one per char.
    assertEquals("caf%C3%A9%20x", Kf8Converter.fragment(String("café x".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)))
  }
}
