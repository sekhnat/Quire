package com.quire.reader.navigator.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure scheme gate of the reader's navigation policy: only web links (http/https) may be
 * parsed and acted on at all; every other scheme — including local (file, content), app
 * launches (intent), and non-navigational (mailto, tel, javascript, data, blob) — must be
 * rejected before any URL parsing (Readium's AbsoluteUrl throws on opaque URIs).
 */
class ReaderLinkPolicyTest {

  @Test
  fun `web schemes are supported`() {
    assertTrue(ReaderLinkPolicy.isSupportedScheme("http"))
    assertTrue(ReaderLinkPolicy.isSupportedScheme("https"))
  }

  @Test
  fun `web schemes are matched case-insensitively`() {
    assertTrue(ReaderLinkPolicy.isSupportedScheme("HTTP"))
    assertTrue(ReaderLinkPolicy.isSupportedScheme("HTTPS"))
    assertTrue(ReaderLinkPolicy.isSupportedScheme("Http"))
  }

  @Test
  fun `local file and content schemes are rejected`() {
    assertFalse(ReaderLinkPolicy.isSupportedScheme("file"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme("content"))
  }

  @Test
  fun `app launch schemes are rejected`() {
    assertFalse(ReaderLinkPolicy.isSupportedScheme("intent"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme("market"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme("android-app"))
  }

  @Test
  fun `non navigational and opaque schemes are rejected`() {
    assertFalse(ReaderLinkPolicy.isSupportedScheme("mailto"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme("tel"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme("javascript"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme("data"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme("blob"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme("about"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme("sms"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme("geo"))
  }

  @Test
  fun `unknown and missing schemes are rejected`() {
    assertFalse(ReaderLinkPolicy.isSupportedScheme("quire-custom"))
    assertFalse(ReaderLinkPolicy.isSupportedScheme(""))
    assertFalse(ReaderLinkPolicy.isSupportedScheme(null))
  }

  @Test
  fun `the scheme is read from the text, lower-cased`() {
    assertEquals("https", ReaderLinkPolicy.schemeOf("https://example.org/a#b"))
    assertEquals("http", ReaderLinkPolicy.schemeOf("HTTP://EXAMPLE.ORG"))
    assertEquals("mailto", ReaderLinkPolicy.schemeOf("mailto:nobody@example.invalid"))
    assertEquals("javascript", ReaderLinkPolicy.schemeOf("  javascript:alert(1)"))
    assertEquals("android-app", ReaderLinkPolicy.schemeOf("android-app://x"))
  }

  @Test
  fun `text without a scheme has none`() {
    assertNull(ReaderLinkPolicy.schemeOf("chapter2.xhtml#sec4"))
    assertNull(ReaderLinkPolicy.schemeOf("#note3"))
    assertNull(ReaderLinkPolicy.schemeOf("//example.org/x"))
    assertNull(ReaderLinkPolicy.schemeOf("1http://x"))
    assertNull(ReaderLinkPolicy.schemeOf(""))
  }

  @Test
  fun `unsupported, schemeless and overlong links never reach navigation`() {
    val reached = mutableListOf<Any>()
    ReaderLinkPolicy.onLinkActivated("mailto:nobody@example.invalid") { reached += it }
    ReaderLinkPolicy.onLinkActivated("javascript:alert(1)") { reached += it }
    ReaderLinkPolicy.onLinkActivated("chapter2.xhtml") { reached += it }
    ReaderLinkPolicy.onLinkActivated("https://example.org/" + "a".repeat(ReaderLinkPolicy.MAX_LINK_LENGTH)) { reached += it }
    assertTrue(reached.isEmpty())
  }
}

