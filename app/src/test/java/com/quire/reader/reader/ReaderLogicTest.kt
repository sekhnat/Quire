package com.quire.reader.reader

import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.BookReaderPrefs
import com.quire.reader.data.PageLayoutPref
import com.quire.reader.data.ParagraphPreset
import com.quire.reader.data.ReadMode
import com.quire.reader.data.ReaderPrefs
import com.quire.reader.data.SpacingLevel
import com.quire.reader.data.TextAlignPref
import com.quire.reader.data.TriState
import com.quire.reader.data.TypographySource
import com.quire.reader.data.WidenLevel
import com.quire.reader.data.WeightLevel
import com.quire.reader.theme.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLogicTest {
  @Test fun `reader prefs survive a JSON round trip`() {
    val prefs = ReaderPrefs(theme = ReaderTheme.Black, font = 2, fontSize = 22, lineHeight = 1.85f, margin = 40, align = TextAlignPref.Left, mode = ReadMode.Scroll)
    assertEquals(prefs, ReaderPrefs.fromJson(prefs.toJson()))
  }

  @Test fun `damaged or empty prefs JSON falls back instead of crashing`() {
    assertNull(ReaderPrefs.fromJson(null))
    assertNull(ReaderPrefs.fromJson("not json"))
    assertNull(ReaderPrefs.fromJson("""{"theme":"Neon"}"""))
  }

  @Test fun `prefs from a newer version with extra keys still load`() {
    val p = ReaderPrefs.fromJson("""{"theme":"Sepia","fontSize":21,"somethingNew":true}""")
    assertNotNull(p)
    assertEquals(ReaderTheme.Sepia, p!!.theme)
    assertEquals(21, p.fontSize)
    assertEquals(ReaderPrefs().margin, p.margin) // unspecified fields keep their defaults
  }

  @Test fun `the Black theme is pure black with softened text`() {
    val bg = ReaderTheme.Black.bg
    assertEquals(0f, bg.red, 0f); assertEquals(0f, bg.green, 0f); assertEquals(0f, bg.blue, 0f); assertEquals(1f, bg.alpha, 0f)
    assertTrue(ReaderTheme.Black.fg.red > 0.5f) // readable, but not pure white
    assertTrue(ReaderTheme.Black.fg.red < 1f)
    assertTrue(!ReaderTheme.Black.isLight)
  }

  @Test fun `margin presets map to Readium page margin factors`() {
    assertEquals(0.7, marginFactor(16), 0.0)
    assertEquals(1.0, marginFactor(26), 0.0)
    assertEquals(1.6, marginFactor(40), 0.0)
  }

  @Test fun `anchor offsets are found by id or name and give a share of the file`() {
    val html = "<html><body>" + "x".repeat(100) + """<h2 id="ch2">Two</h2>""" + "y".repeat(100) + """<a name="ch3"></a>""" + "</body></html>"
    val two = ReaderSession.anchorProgression(html, "ch2")!!
    val three = ReaderSession.anchorProgression(html, "ch3")!!
    assertTrue(two > 0.0 && two < three && three < 1.0)
    assertNull(ReaderSession.anchorProgression(html, "missing"))
    assertNull(ReaderSession.anchorProgression("", "ch2"))
  }

  @Test fun `search snippets are cut at word boundaries`() {
    val before = ReaderSession.snippetBefore("a".repeat(5) + " " + "word ".repeat(30))
    assertTrue(before.startsWith("…"))
    assertTrue(!before.drop(1).startsWith("ord")) // never starts mid-word
    val after = ReaderSession.snippetAfter("lorem ipsum dolor sit amet ".repeat(10))
    assertTrue(after.endsWith("…"))
    assertTrue(after.dropLast(1).endsWith("amet") || after.dropLast(1).endsWith("sit") || after.dropLast(1).endsWith("dolor") || after.dropLast(1).endsWith("ipsum") || after.dropLast(1).endsWith("lorem"))
    assertEquals("short text", ReaderSession.snippetAfter("short text"))
    assertEquals("short  text".replace("  ", " "), ReaderSession.snippetBefore("short  text"))
  }

  @Test fun `the pre-advanced defaults JSON still reads as the factory settings`() {
    val text = javaClass.getResourceAsStream("/reader-prefs/pre-advanced.json")!!.readBytes().decodeToString().trim()
    assertEquals(ReaderPrefs(), ReaderPrefs.fromJson(text))
    val row = BookReaderPrefs.fromJson(text)!!
    assertTrue(row.hasBasic)
    assertTrue(!row.hasAdvanced)
  }

  @Test fun `advanced prefs survive a round trip and tolerate unknown nested keys`() {
    val advanced = AdvancedReaderPrefs(
      typographySource = TypographySource.Book, paragraphIndent = SpacingLevel.Small, letterSpacing = WidenLevel.Wider,
      fontWeight = WeightLevel.Heavy, hyphens = TriState.Off, simplifyTypography = true, pageLayout = PageLayoutPref.Two,
    )
    val prefs = ReaderPrefs(advanced = advanced)
    assertEquals(prefs, ReaderPrefs.fromJson(prefs.toJson()))
    val decoded = ReaderPrefs.fromJson("""{"theme":"Night","advanced":{"letterSpacing":"Max","somedayNew":true}}""")!!
    assertEquals(WidenLevel.Max, decoded.advanced.letterSpacing)
    assertEquals(AdvancedReaderPrefs().paragraphIndent, decoded.advanced.paragraphIndent)
  }

  @Test fun `the paragraph preset is derived from indent and spacing`() {
    assertEquals(ParagraphPreset.Default, AdvancedReaderPrefs().paragraphPreset)
    assertEquals(ParagraphPreset.Traditional, AdvancedReaderPrefs(paragraphIndent = SpacingLevel.Medium, paragraphSpacing = SpacingLevel.None).paragraphPreset)
    assertEquals(ParagraphPreset.Screen, AdvancedReaderPrefs(paragraphIndent = SpacingLevel.None, paragraphSpacing = SpacingLevel.Medium).paragraphPreset)
    assertEquals(ParagraphPreset.Custom, AdvancedReaderPrefs(paragraphIndent = SpacingLevel.Small).paragraphPreset)
    assertEquals(SpacingLevel.Medium to SpacingLevel.None, AdvancedReaderPrefs.levelsFor(ParagraphPreset.Traditional))
    assertNull(AdvancedReaderPrefs.levelsFor(ParagraphPreset.Custom))
    assertTrue(AdvancedReaderPrefs().isFactory)
    assertTrue(!AdvancedReaderPrefs(wordSpacing = WidenLevel.Slight).isFactory)
  }
}
