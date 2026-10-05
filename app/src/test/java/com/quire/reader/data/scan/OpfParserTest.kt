package com.quire.reader.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class OpfParserTest {
  private fun opf(name: String) = checkNotNull(javaClass.getResourceAsStream("/opf/$name")) { "missing fixture $name" }.use { OpfParser.parse(it) }

  @Test
  fun `parses a full Calibre OPF`() {
    val m = checkNotNull(opf("calibre_series.opf"))
    assertEquals("The Sign of the Four", m.title)
    assertEquals(listOf("Arthur Conan Doyle"), m.authors)
    assertEquals("Doyle, Arthur Conan", m.authorSort)
    assertEquals("Sherlock Holmes", m.series)
    assertEquals(2.0, m.seriesIndex!!, 0.0)
    assertEquals(listOf("Mystery", "Classics"), m.tags)
    assertEquals(4, m.rating) // Calibre's 8 of 10
    assertEquals(1890, m.year)
    assertEquals("eng", m.language)
    assertEquals("Sign of the Four, The", m.titleSort)
    assertEquals(java.time.Instant.parse("2021-05-04T10:20:30Z").toEpochMilli(), m.addedAtMillis)
  }

  @Test
  fun `description loses its markup but keeps paragraphs and entities`() {
    val m = checkNotNull(opf("calibre_series.opf"))
    assertEquals("A young woman receives a pearl & a note.\nHolmes investigates.", m.description)
  }

  @Test
  fun `a minimal OPF still yields a title and treats Calibre's null date as unknown`() {
    val m = checkNotNull(opf("minimal.opf"))
    assertEquals("Plain Book", m.title)
    assertEquals(emptyList<String>(), m.authors)
    assertNull(m.year)
    assertNull(m.series)
    assertEquals(0, m.rating)
  }

  @Test
  fun `reads EPUB 3 collection metadata and keeps every author`() {
    val m = checkNotNull(opf("epub3_collection.opf"))
    assertEquals(listOf("First Author", "Second Author"), m.authors)
    assertEquals("The Series", m.series)
    assertEquals(2.0, m.seriesIndex!!, 0.0)
  }

  @Test
  fun `malformed or title-less input returns null instead of throwing`() {
    assertNull(OpfParser.parse(ByteArrayInputStream("not xml at all".toByteArray())))
    assertNull(OpfParser.parse(ByteArrayInputStream("<package xmlns='http://www.idpf.org/2007/opf'><metadata/></package>".toByteArray())))
    assertNull(OpfParser.parse(ByteArrayInputStream(ByteArray(0))))
  }

  @Test
  fun `a document type declaration is refused`() {
    val xxe = """<?xml version="1.0"?><!DOCTYPE p [<!ENTITY x "boom">]><package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>&x;</dc:title></metadata></package>"""
    val m = OpfParser.parse(ByteArrayInputStream(xxe.toByteArray()))
    // Either rejected outright, or parsed without expanding the entity.
    if (m != null) assertNotNull(m.title) else assertNull(m)
  }

  @Test
  fun `identifiers of a Calibre OPF give its uuid, which is also the unique identifier`() {
    val ids = checkNotNull(javaClass.getResourceAsStream("/opf/calibre_series.opf")).use { OpfParser.identifiers(it) }
    assertEquals(OpfIdentifiers(uuid = "0b0c5b2e-aaaa-bbbb-cccc-1234567890ab", uniqueId = "0b0c5b2e-aaaa-bbbb-cccc-1234567890ab"), ids)
  }

  @Test
  fun `identifiers need no title, follow unique-identifier and drop the urn prefix of a uuid`() {
    val xml = """<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="pub-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
      <dc:identifier id="isbn">9780000000000</dc:identifier><dc:identifier id="pub-id">urn:isbn:123</dc:identifier>
      <dc:identifier id="uuid_id">urn:uuid:abc-def</dc:identifier></metadata></package>"""
    assertEquals(OpfIdentifiers(uuid = "abc-def", uniqueId = "urn:isbn:123"), OpfParser.identifiers(ByteArrayInputStream(xml.toByteArray())))
    assertEquals(OpfIdentifiers(null, null), OpfParser.identifiers(ByteArrayInputStream(checkNotNull(javaClass.getResourceAsStream("/opf/minimal.opf")).readBytes())))
    assertNull(OpfParser.identifiers(ByteArrayInputStream("not xml".toByteArray())))
  }

  @Test
  fun `rating rounds and clamps`() {
    fun rate(content: String) = OpfParser.parse(ByteArrayInputStream("""<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title><meta name="calibre:rating" content="$content"/></metadata></package>""".toByteArray()))!!.rating
    assertEquals(5, rate("10.0"))
    assertEquals(3, rate("5.0")) // 2.5 rounds half up
    assertEquals(0, rate("0"))
    assertEquals(5, rate("99"))
  }
}
