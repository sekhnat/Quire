package com.quire.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareQuoteTest {
  @Test fun `a quote carries its title and author`() =
    assertEquals("“Call me Ishmael.”\n— Moby-Dick, Herman Melville", shareQuote("Call me Ishmael.", "Moby-Dick", "Herman Melville"))

  @Test fun `line breaks and runs of spaces in the selection become single spaces`() =
    assertEquals("“a b c”\n— T, A", shareQuote("  a\n b   c ", "T", "A"))

  @Test fun `a missing or unknown author is left out`() {
    assertEquals("“x”\n— T", shareQuote("x", "T", ""))
    assertEquals("“x”\n— T", shareQuote("x", "T", "Unknown author"))
  }

  @Test fun `with nothing known about the book the passage stands alone`() =
    assertEquals("“x”", shareQuote("x", "", "Unknown author"))
}
