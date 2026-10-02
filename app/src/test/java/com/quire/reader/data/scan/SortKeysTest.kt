package com.quire.reader.data.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class SortKeysTest {
  @Test fun `leading articles are ignored when sorting titles`() {
    assertEquals("hound of the baskervilles", LibraryScanner.sortKey("The Hound of the Baskervilles"))
    assertEquals("study in scarlet", LibraryScanner.sortKey("A Study in Scarlet"))
    assertEquals("the", LibraryScanner.sortKey("The")) // nothing left to sort by, so keep it
    assertEquals("anna karenina", LibraryScanner.sortKey("Anna Karenina")) // "An" is not an article here
  }

  @Test fun `author sort is surname first`() {
    assertEquals("doyle, arthur conan", LibraryScanner.authorSortFrom("Arthur Conan Doyle"))
    assertEquals("homer", LibraryScanner.authorSortFrom("Homer"))
    assertEquals("~", LibraryScanner.authorSortFrom(LibraryScanner.UNKNOWN_AUTHOR))
  }
}
