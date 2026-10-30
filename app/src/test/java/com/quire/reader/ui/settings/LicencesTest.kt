package com.quire.reader.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LicencesTest {
  @Test fun `titles come from the file names`() {
    assertEquals("Source Serif", licenceTitle("licenses/fonts/source-serif-OFL.txt"))
    assertEquals("Charis SIL", licenceTitle("licenses/fonts/charis-sil-OFL.txt"))
    assertEquals("EB Garamond", licenceTitle("licenses/fonts/eb-garamond-OFL.txt"))
    assertEquals("Crimson Pro", licenceTitle("licenses/fonts/crimson-pro-OFL.txt"))
    assertEquals("Readium", licenceTitle("licenses/readium-LICENSE.txt"))
  }

  @Test fun `hard-wrapped paragraphs are joined and paragraph breaks kept`() {
    assertEquals("One two three.\n\nFour five.", reflowLicence("One two\r\nthree.\r\n\r\n\r\nFour\r\n  five.\r\n"))
  }

  @Test fun `every shipped notice has a title and says what it covers`() {
    val dir = File("src/main/assets/$LICENCES_DIR")
    val notices = dir.walk().filter { it.isFile }.map { "$LICENCES_DIR/" + it.relativeTo(dir).invariantSeparatorsPath }.toList()
    assert(notices.isNotEmpty()) { "no notices under ${dir.absolutePath}" }
    notices.forEach { path ->
      assert(licenceTitle(path).isNotBlank()) { path }
      assertNotEquals(path, "Licence", licenceKind(path))
    }
  }
}
