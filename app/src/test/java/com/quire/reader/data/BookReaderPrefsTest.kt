package com.quire.reader.data

import com.quire.reader.theme.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The override semantics of a book's stored prefs row (see C1 of the advanced-controls plan). */
class BookReaderPrefsTest {
  private val globals = ReaderPrefs(fontSize = 22, lineHeight = 1.8f, theme = ReaderTheme.Sepia, advanced = AdvancedReaderPrefs(wordSpacing = WidenLevel.Slight))

  @Test fun `the legacy whole-object row still reads as a basic override`() {
    val text = javaClass.getResourceAsStream("/reader-prefs/pre-advanced.json")!!.readBytes().decodeToString().trim()
    val row = BookReaderPrefs.fromJson(text)!!
    assertTrue(row.hasBasic)
    assertTrue(!row.hasAdvanced)
    // The row overrides exactly its basic group; the advanced group stays the globals'.
    val legacy = ReaderPrefs.fromJson(text)!!
    assertEquals(legacy.copy(advanced = globals.advanced), row.appliedTo(globals))
  }

  @Test fun `an advanced-only row does not pin the inherited basic settings`() {
    val row = BookReaderPrefs.fromJson("""{"advanced":{"letterSpacing":"Wider"}}""")!!
    assertFalse(row.hasBasic)
    assertTrue(row.hasAdvanced)
    val resolved = row.appliedTo(globals)
    // Every basic value still comes from the globals…
    assertEquals(globals.theme, resolved.theme)
    assertEquals(globals.fontSize, resolved.fontSize)
    assertEquals(globals.lineHeight, resolved.lineHeight)
    // …and the advanced group is the row's own object, wholesale: a book's advanced edit
    // writes the whole effective object, so its rows always carry complete values.
    assertEquals(row.advanced, resolved.advanced)
    assertEquals(WidenLevel.Wider, resolved.advanced.letterSpacing)
  }


  @Test fun `a basic-only row keeps inheriting the global advanced object`() {
    val row = BookReaderPrefs.fromJson("""{"theme":"Paper","fontSize":25}""")!!
    assertTrue(row.hasBasic)
    assertFalse(row.hasAdvanced)
    val resolved = row.appliedTo(globals)
    assertEquals(ReaderTheme.Paper, resolved.theme)
    assertEquals(25, resolved.fontSize)
    assertEquals(globals.lineHeight, resolved.lineHeight)
    assertEquals(globals.advanced, resolved.advanced)
  }

  @Test fun `a row carrying both groups overrides both`() {
    val advanced = AdvancedReaderPrefs(fontWeight = WeightLevel.Heavy, pageLayout = PageLayoutPref.Two)
    val row = BookReaderPrefs(theme = ReaderTheme.Black, fontSize = 17, advanced = advanced)
    val resolved = row.appliedTo(globals)
    assertEquals(ReaderTheme.Black, resolved.theme)
    assertEquals(17, resolved.fontSize)
    assertEquals(globals.lineHeight, resolved.lineHeight)
    assertEquals(advanced, resolved.advanced)
  }

  @Test fun `an advanced-only row serializes without any basic key`() {
    val row = BookReaderPrefs(advanced = AdvancedReaderPrefs(hyphens = TriState.Off))
    val text = row.toJson()
    assertEquals(text, BookReaderPrefs.fromJson(text)!!.toJson())
    assertFalse(text.contains("theme"))
    assertTrue(text.startsWith("{\"advanced\":"))
  }

  @Test fun `a basic edit keeps the stored advanced object and an advanced edit keeps the basic group`() {
    val stored = BookReaderPrefs.fromJson("""{"theme":"Night","fontSize":19,"advanced":{"hyphens":"Off"}}""")!!

    val afterBasicEdit = stored.withBasicFrom(globals)
    val reread = BookReaderPrefs.fromJson(afterBasicEdit.toJson())!!
    assertEquals(globals.fontSize, reread.fontSize)
    assertEquals(TriState.Off, reread.advanced!!.hyphens)

    val afterAdvancedEdit = stored.copy(advanced = AdvancedReaderPrefs(letterSpacing = WidenLevel.Max))
    val rereadAdvanced = BookReaderPrefs.fromJson(afterAdvancedEdit.toJson())!!
    assertEquals(19, rereadAdvanced.fontSize)
    assertEquals(WidenLevel.Max, rereadAdvanced.advanced!!.letterSpacing)
  }

  @Test fun `clearing the advanced group of an advanced-only row leaves nothing to store`() {
    val stored = BookReaderPrefs.fromJson("""{"advanced":{"hyphens":"Off"}}""")!!
    assertTrue(stored.copy(advanced = null).isEmpty)
    val withBasics = BookReaderPrefs.fromJson("""{"theme":"Night","advanced":{"hyphens":"Off"}}""")!!
    assertFalse(withBasics.copy(advanced = null).isEmpty)
  }

  @Test fun `damaged rows fall back to no override`() {
    assertNull(BookReaderPrefs.fromJson(null))
    assertNull(BookReaderPrefs.fromJson("not json"))
    assertNull(BookReaderPrefs.fromJson("""{"advanced":{"letterSpacing":"Sideways"}}"""))
  }
}
