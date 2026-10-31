package com.quire.reader.navigator.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechStartTest {
  @Test fun `a selector comes back from the page as a JSON string`() {
    assertEquals("body > div:nth-child(2) > p:nth-child(6)", SpeechStart.selectorFrom("\"body > div:nth-child(2) > p:nth-child(6)\""))
    assertEquals("body", SpeechStart.selectorFrom("\"body\""))
  }

  @Test fun `anything that is not a selector from body is no selector`() {
    assertNull(SpeechStart.selectorFrom("null"))
    assertNull(SpeechStart.selectorFrom(null))
    assertNull(SpeechStart.selectorFrom(""))
    assertNull(SpeechStart.selectorFrom("42"))
    assertNull(SpeechStart.selectorFrom("{\"a\":1}"))
    assertNull(SpeechStart.selectorFrom("\"html > body > p\""))
    assertNull(SpeechStart.selectorFrom("not json"))
  }

  @Test fun `the scripts ask for what the iterator can use`() {
    // The positions are counted among siblings, from body down, the form Jsoup resolves with selectFirst.
    assertEquals(true, SpeechStart.SELECTION.contains(":nth-child("))
    assertEquals(true, SpeechStart.firstBelow(123.5).contains("})(123.5)"))
  }
}
