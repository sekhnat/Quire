package com.quire.reader.data.fonts

import com.quire.reader.reader.ReaderFontList
import com.quire.reader.reader.UserFontRegistry
import com.quire.reader.reader.resolveFont
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Loading the user's own fonts (spec feature 6). Real files are the app's own OFL reading fonts (licences in
 * assets/licenses/fonts); the odd cases are built here byte by byte.
 */
class UserFontsTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun bundled(name: String) = File("src/main/assets/fonts/$name").readBytes()
  private fun resource(name: String) = File("src/main/res/font/$name").readBytes()

  @After fun clearRegistry() { UserFontRegistry.fonts = emptyList(); UserFontRegistry.dir = null }

  @Test
  fun `a static regular, italic and bold`() {
    val regular = OpenTypeReader.read(bundled("spectral-regular.ttf")).getOrThrow()
    assertEquals(OpenTypeInfo("Spectral", italic = false, weights = 400..400, extension = "ttf"), regular)
    assertTrue(OpenTypeReader.read(bundled("spectral-italic.ttf")).getOrThrow().italic)
    assertEquals(700..700, OpenTypeReader.read(bundled("spectral-bold.ttf")).getOrThrow().weights)
  }

  @Test
  fun `a variable font reports its weight axis`() {
    val lora = OpenTypeReader.read(bundled("lora.ttf")).getOrThrow()
    assertEquals("Lora", lora.family)
    assertEquals(400..700, lora.weights)
    assertFalse(lora.italic)
    val inter = OpenTypeReader.read(resource("inter.ttf")).getOrThrow()
    assertEquals(100..900, inter.weights)
  }

  @Test
  fun `the typographic family wins over a style-linked family`() {
    // Charis SIL's bold italic is family "Charis SIL" (ID 16 or 1) in every file, so the four files make one family.
    val names = listOf("regular", "italic", "bold", "bold-italic").map { OpenTypeReader.read(bundled("charis-sil-$it.ttf")).getOrThrow().family }.toSet()
    assertEquals(1, names.size)
  }

  @Test
  fun `a CFF font is an otf`() {
    val info = OpenTypeReader.read(synthetic(tag = "OTTO", family = "Test Sans", weight = 300, italic = true)).getOrThrow()
    assertEquals(OpenTypeInfo("Test Sans", italic = true, weights = 300..300, extension = "otf"), info)
  }

  @Test
  fun `collections, web fonts, truncated and random files are refused`() {
    fun rejection(bytes: ByteArray) = (OpenTypeReader.read(bytes).exceptionOrNull() as OpenTypeReader.FontException).rejection
    assertEquals(FontRejection.Collection, rejection("ttcf".toByteArray() + ByteArray(40)))
    assertEquals(FontRejection.Woff, rejection("wOFF".toByteArray() + ByteArray(40)))
    assertEquals(FontRejection.Woff, rejection("wOF2".toByteArray() + ByteArray(40)))
    assertEquals(FontRejection.Unreadable, rejection("not a font at all, just text".toByteArray()))
    val real = bundled("lora.ttf")
    assertEquals(FontRejection.Unreadable, rejection(real.copyOf(200)))
    assertEquals(FontRejection.Unreadable, rejection(synthetic(tag = "OTTO", family = "")))
    assertEquals(FontRejection.TooLarge, rejection(ByteArray(OpenTypeReader.MAX_BYTES + 1)))
  }

  @Test
  fun `files group into families, and a family added again gains only new styles`() {
    fun parsed(family: String, italic: Boolean, weights: IntRange) = ParsedFont("x", ByteArray(0), OpenTypeInfo(family, italic, weights, "ttf"))
    val (list, writes) = mergeFonts(UserFontList(), listOf(parsed("Foo", false, 400..400), parsed("Foo", true, 400..400), parsed("Bar", false, 100..900)))
    assertEquals(listOf("Foo", "Bar"), list.fonts.map { it.family })
    assertEquals(listOf("1-0.ttf", "1-1.ttf", "2-0.ttf"), writes.map { it.first.name })
    assertEquals(3, list.nextId)
    val (again, more) = mergeFonts(list, listOf(parsed("foo", true, 400..400), parsed("Foo", false, 700..700)))
    assertEquals(listOf("1-2.ttf"), more.map { it.first.name })
    assertEquals(3, again.fonts.first { it.id == 1 }.files.size)
    assertEquals(3, again.nextId)
  }

  @Test
  fun `ids are never reused`() {
    val (one, _) = mergeFonts(UserFontList(), listOf(ParsedFont("a", ByteArray(0), OpenTypeInfo("A", false, 400..400, "ttf"))))
    val removed = one.copy(fonts = emptyList())
    val (two, _) = mergeFonts(removed, listOf(ParsedFont("b", ByteArray(0), OpenTypeInfo("B", false, 400..400, "ttf"))))
    assertEquals(2, two.fonts.single().id)
  }

  @Test
  fun `a stored index resolves to the bundled font, the user font, or the default`() {
    val user = UserFont(7, "Mine", listOf(UserFontFile("7-0.ttf")))
    assertSame(ReaderFontList[3], resolveFont(3, emptyList()))
    assertEquals("QuireUserFont7", resolveFont(UserFont.USER_FONT_BASE + 7, listOf(user)).name)
    assertEquals("Mine", resolveFont(UserFont.USER_FONT_BASE + 7, listOf(user)).label)
    // A removed font, an unknown index from a newer build, a negative one: the default, never the last bundled font.
    assertSame(ReaderFontList[0], resolveFont(UserFont.USER_FONT_BASE + 8, listOf(user)))
    assertSame(ReaderFontList[0], resolveFont(ReaderFontList.size, listOf(user)))
    assertSame(ReaderFontList[0], resolveFont(-1, listOf(user)))
  }

  @Test
  fun `the font server answers only listed files, by exact name`() {
    val dir = tmp.newFolder("fonts")
    File(dir, "7-0.ttf").writeBytes(byteArrayOf(1))
    File(dir, "secret.db").writeBytes(byteArrayOf(2))
    UserFontRegistry.dir = dir
    UserFontRegistry.fonts = listOf(UserFont(7, "Mine", listOf(UserFontFile("7-0.ttf"))))
    assertEquals(File(dir, "7-0.ttf"), UserFontRegistry.file("7-0.ttf"))
    assertNull(UserFontRegistry.file("secret.db"))
    assertNull(UserFontRegistry.file("../fonts/7-0.ttf"))
    assertNull(UserFontRegistry.file("7-1.ttf"))
    UserFontRegistry.fonts = emptyList()
    assertNull(UserFontRegistry.file("7-0.ttf"))
  }

  @Test
  fun `font-face rules name each file's style and weight`() {
    val css = UserFontRegistry.fontFaceCss(listOf(UserFont(3, "Mine", listOf(UserFontFile("3-0.otf", italic = true, minWeight = 200, maxWeight = 800))))) { "https://a/fonts/user/$it" }
    assertTrue(css.contains("font-family: \"QuireUserFont3\""))
    assertTrue(css.contains("url(\"https://a/fonts/user/3-0.otf\") format(\"opentype\")"))
    assertTrue(css.contains("font-style: italic"))
    assertTrue(css.contains("font-weight: 200 800"))
  }

  @Test
  fun `the summary names what was added and what was not`() {
    assertEquals("2 fonts added · Foo.ttc isn't supported (font collections)", addFontsSummary(2, listOf("Foo.ttc" to FontRejection.Collection)))
    assertEquals("1 font added", addFontsSummary(1, emptyList()))
    assertEquals("Those fonts are already added", addFontsSummary(0, emptyList()))
  }

  @Test
  fun `the list survives JSON`() {
    val list = UserFontList(4, listOf(UserFont(3, "Mine", listOf(UserFontFile("3-0.otf", true, 200, 800)))))
    assertEquals(list, UserFontList.fromJson(list.toJson()))
    assertNull(UserFontList.fromJson("{broken"))
  }

  /** A minimal sfnt with a `name` table (Windows Unicode, ID 1) and an `OS/2` table. */
  private fun synthetic(tag: String, family: String, weight: Int = 400, italic: Boolean = false): ByteArray {
    val nameBytes = family.toByteArray(Charsets.UTF_16BE)
    val name = ByteArrayOutputStream().also { o -> DataOutputStream(o).run {
      writeShort(0); writeShort(1); writeShort(6 + 12)
      writeShort(3); writeShort(1); writeShort(0x409); writeShort(1); writeShort(nameBytes.size); writeShort(0)
      write(nameBytes)
    } }.toByteArray()
    val os2 = ByteArray(78).also { b ->
      b[4] = (weight shr 8).toByte(); b[5] = weight.toByte()
      if (italic) b[63] = 1
    }
    val tables = listOf("OS/2" to os2, "name" to name)
    val out = ByteArrayOutputStream()
    DataOutputStream(out).run {
      write(tag.toByteArray(Charsets.ISO_8859_1)); writeShort(tables.size); writeShort(0); writeShort(0); writeShort(0)
      var offset = 12 + tables.size * 16
      for ((t, data) in tables) { write(t.toByteArray(Charsets.ISO_8859_1)); writeInt(0); writeInt(offset); writeInt(data.size); offset += data.size }
      tables.forEach { write(it.second) }
    }
    return out.toByteArray()
  }
}
