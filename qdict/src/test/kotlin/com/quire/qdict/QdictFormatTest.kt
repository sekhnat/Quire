package com.quire.qdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The `.qdict` format round trip, on data built to cross block boundaries (spec feature 7). */
class QdictFormatTest {

  /** [n] headwords and synsets, with non-ASCII words and collocations among them, sorted as the format requires. */
  private fun data(n: Int): DictionaryData {
    val keys = (0 until n).map { i ->
      when (i % 7) {
        0 -> "word$i"
        1 -> "wörd$i"
        2 -> "give up $i"
        3 -> "ñandú$i"
        else -> "w${"x".repeat(i % 5)}$i"
      }
    }.distinct().sortedWith(Utf8Order)
    val headwords = keys.mapIndexed { i, k ->
      Headword(k, if (i % 11 == 0) k.replaceFirstChar { it.uppercase() } else k,
        mapOf(Pos.entries[i % 4] to listOf(i % n, (i * 7 + 3) % n), Pos.Noun to listOf((i * 13) % n)).filterValues { it.isNotEmpty() })
    }
    val synsets = (0 until n).map { i ->
      Synset(Pos.entries[i % 4], "definition number $i — with ünïcode and some repeated phrasing of a typical gloss",
        if (i % 3 == 0) emptyList() else listOf("an example for $i", "and another"), listOf(i % keys.size, (i * 5) % keys.size).distinct().sorted())
    }
    val forms = (0 until n step 9).map { IrregularForm("form$it", it % keys.size, Pos.Verb) }.sortedWith(compareBy(Utf8Order) { it.form })
    return DictionaryData("test-1", headwords, forms, synsets)
  }

  private fun assertRoundTrip(d: DictionaryData, codec: Codec, block: Int, preset: Boolean) {
    val r = QdictReader(ByteArraySource(QdictWriter.write(d, codec, block, preset)), cacheBlocks = 2)
    assertEquals("test-1", r.release)
    assertEquals(d.headwords, r.allHeadwords().toList())
    assertEquals(d.exceptions, r.allExceptions().toList())
    d.synsets.forEachIndexed { id, s -> assertEquals("synset $id", s, r.synset(id)) }
    d.headwords.forEachIndexed { id, h ->
      assertEquals(id, r.headword(h.key)?.first)
      assertEquals(h, r.headwordAt(id))
    }
    d.exceptions.forEach { e -> assertTrue(r.exceptionsOf(e.form).contains(e.headword to e.pos)) }
  }

  @Test fun `every entry reads back, deflate with a preset dictionary`() = assertRoundTrip(data(3000), Codec.Deflate, 1024, preset = true)

  @Test fun `every entry reads back, deflate without a preset`() = assertRoundTrip(data(3000), Codec.Deflate, 1024, preset = false)

  @Test fun `every entry reads back, uncompressed`() = assertRoundTrip(data(3000), Codec.None, 1024, preset = false)

  @Test fun `one block, and blocks of a single record`() {
    assertRoundTrip(data(50), Codec.Deflate, 1 shl 20, preset = true)
    assertRoundTrip(data(50), Codec.Deflate, 1, preset = false)
  }

  @Test fun `words that are not there, before the first and after the last`() {
    val d = data(500)
    val r = QdictReader(ByteArraySource(QdictWriter.write(d, Codec.Deflate, 512, true)))
    assertNull(r.headword("aaaa"))
    assertNull(r.headword("zzzz"))
    assertNull(r.headword("word1x"))
    assertTrue(r.exceptionsOf("nothing").isEmpty())
  }

  @Test fun `damaged and truncated files are refused cleanly`() {
    val bytes = QdictWriter.write(data(800), Codec.Deflate, 512, true)
    fun refused(b: ByteArray) {
      try {
        val r = QdictReader(ByteArraySource(b))
        (0 until r.synsetCount).forEach { r.synset(it) }
        r.allHeadwords().toList()
        fail("a damaged file was read")
      } catch (_: QdictException) {
      }
    }
    refused(ByteArray(0))
    refused("QDCX".toByteArray() + bytes.copyOfRange(4, bytes.size))
    refused(bytes.copyOf(bytes.size / 2))
    refused(bytes.copyOf(20))
    // Flip bytes inside the compressed data.
    refused(bytes.copyOf().also { b -> for (i in b.size - 2000 until b.size - 1000) b[i] = (b[i].toInt() xor 0x5A).toByte() })
  }
}
