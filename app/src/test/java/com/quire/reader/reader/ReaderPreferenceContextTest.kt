package com.quire.reader.reader

import com.quire.reader.data.ReaderPrefs
import com.quire.reader.navigator.epub.EpubPreferences
import com.quire.reader.reader.UnavailableReason.BookTypography
import com.quire.reader.reader.UnavailableReason.Mode
import com.quire.reader.reader.UnavailableReason.NotAvailable
import com.quire.reader.reader.UnavailableReason.Theme
import com.quire.reader.reader.UnavailableReason.WritingSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.preferences.Theme as NavTheme
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication

/**
 * Availability against the real vendored editor: the neutral reflowable book offers every
 * control but the RTL-only ligatures, book typography disables the dependent rows, scroll
 * disables the page layout, non-dark themes disable the image controls, the writing system
 * splits the letter controls from ligatures, and a fixed layout offers none of the reflowable
 * typography. Reasons are presentation only and never decide enablement.
 */
class ReaderPreferenceContextTest {
  /** The factory mapping, the base every context below starts from. */
  private val factory = ReaderPrefs().toEpubPreferences(Layout.REFLOWABLE)

  private fun context(
    layout: Layout = Layout.REFLOWABLE,
    tweak: EpubPreferences.() -> EpubPreferences = { this },
  ): ReaderPreferenceContext {
    val metadata = Metadata(identifier = "t", localizedTitle = LocalizedString("t"), layout = layout)
    val publication = Publication(Manifest(metadata = metadata))
    return ReaderPreferenceContext.of(publication, factory.tweak())
  }

  @Test fun `a neutral reflowable book offers every control but the RTL-only ligatures`() {
    val context = context()
    for (available in listOf(
      context.typographySource, context.lineHeight, context.textAlign, context.paragraphPreset,
      context.paragraphIndent, context.paragraphSpacing, context.letterSpacing, context.wordSpacing,
      context.weight, context.hyphens, context.verticalText, context.simplifyTypography,
      context.readingDirection, context.images, context.pageLayout,
    )) {
      assertTrue("expected available, was ${available.reason}", available.available)
    }
    assertFalse(context.ligatures.available)
    assertEquals(WritingSystem, context.ligatures.reason)
  }

  @Test fun `null-valued rows are checked through candidate editors and stay available`() {
    // Hyphens at Auto submits null; the row must still be offerable (C8), without any probe.
    val context = context { copy(hyphens = null) }
    assertTrue(context.hyphens.available)
    assertTrue(context.paragraphIndent.available)
    assertTrue(context.paragraphSpacing.available)
    assertTrue(context.weight.available)
  }

  @Test fun `book typography disables the dependent rows with the book reason`() {
    val context = context { copy(publisherStyles = true) }
    for (row in listOf(
      context.lineHeight, context.textAlign, context.paragraphPreset, context.paragraphIndent,
      context.paragraphSpacing, context.letterSpacing, context.wordSpacing, context.weight,
      context.hyphens, context.simplifyTypography,
    )) {
      assertFalse("expected BookTypography, was ${row.reason}", row.available)
      assertEquals(BookTypography, row.reason)
    }
    // The rows that are not about typography stay usable, including the source row itself.
    assertTrue(context.typographySource.available)
    assertTrue(context.images.available)
    assertTrue(context.pageLayout.available)
    assertTrue(context.verticalText.available)
    assertTrue(context.readingDirection.available)
  }

  @Test fun `scroll disables the page layout with the mode reason`() {
    val context = context { copy(scroll = true) }
    assertFalse(context.pageLayout.available)
    assertEquals(Mode, context.pageLayout.reason)
    assertTrue(context.paragraphIndent.available)
  }

  @Test fun `non-dark themes disable the image controls with the theme reason`() {
    for (theme in listOf(NavTheme.LIGHT, NavTheme.SEPIA)) {
      val context = context { copy(theme = theme) }
      assertFalse(context.images.available)
      assertEquals(Theme, context.images.reason)
    }
    assertTrue(context { copy(theme = NavTheme.DARK) }.images.available)
  }

  @Test fun `the writing system splits the letter controls from ligatures`() {
    val rtl = context { copy(readingProgression = ReadingProgression.RTL) }
    assertFalse(rtl.letterSpacing.available)
    assertEquals(WritingSystem, rtl.letterSpacing.reason)
    assertFalse(rtl.wordSpacing.available)
    assertFalse(rtl.hyphens.available)
    assertTrue(rtl.ligatures.available)
  }

  @Test fun `a fixed layout offers none of the reflowable typography but keeps the spread`() {
    val context = context(layout = Layout.FIXED)
    for (row in listOf(
      context.typographySource, context.lineHeight, context.textAlign, context.paragraphIndent,
      context.paragraphSpacing, context.weight, context.verticalText, context.simplifyTypography,
    )) {
      assertFalse("expected NotAvailable, was ${row.reason}", row.available)
      assertEquals(NotAvailable, row.reason)
    }
    assertTrue(context.pageLayout.available)
    assertTrue(context.readingDirection.available)
  }
}
