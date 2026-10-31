package com.quire.reader.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every reading-font file the WebView may ask for is bundled exactly once: in the assets, or in res/font. */
class ReaderFontFilesTest {
  private val assets = File("src/main/assets")

  @Test fun `each font file is in the assets unless res-font serves it`() {
    ReaderFontList.flatMap { it.files }.forEach { file ->
      val inAssets = File(assets, file.asset).isFile
      if (file.resource == null) assertTrue("${file.asset} is missing from the assets", inAssets)
      else assertFalse("${file.asset} is served from res/font and must not be bundled twice", inAssets)
    }
  }

  @Test fun `the UI fonts are the ones served from res-font`() {
    assertEquals(
      setOf("fonts/literata.ttf", "fonts/literata-italic.ttf", "fonts/source-serif.ttf", "fonts/atkinson-regular.ttf", "fonts/atkinson-bold.ttf", "fonts/inter.ttf"),
      ReaderFontResources.keys,
    )
  }
}
