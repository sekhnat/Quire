package com.quire.reader.reader

import com.quire.reader.navigator.epub.EpubDefaults
import com.quire.reader.navigator.epub.EpubPreferences
import com.quire.reader.navigator.epub.EpubPreferencesEditor
import com.quire.reader.navigator.epub.EpubSettingsResolver
import com.quire.reader.navigator.epub.css.Layout as ReadiumCssLayout
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Metadata
import com.quire.reader.data.ReaderPrefs
import org.readium.r2.shared.publication.Publication

/** Why a control cannot act on the book — presentation only, never used to decide enablement. */
enum class UnavailableReason {
  /** The control is usable. */
  None,
  /** The book's own typography rules the page. */
  BookTypography,
  /** The reading mode makes the control meaningless: scroll has no page layout. */
  Mode,
  /** The theme makes the control meaningless: image filters apply in dark themes. */
  Theme,
  /** The writing system excludes the control: letter spacing is LTR-only, ligatures RTL-only. */
  WritingSystem,
  /** Nothing more specific applies: the control does not exist for this book's layout. */
  NotAvailable,
}

/** Whether a control can act on the book, and why not when it cannot. */
data class Availability(val available: Boolean, val reason: UnavailableReason) {
  companion object {
    val Available = Availability(true, UnavailableReason.None)
    fun unavailable(reason: UnavailableReason) = Availability(false, reason)
  }
}

/**
 * Whether each reading control can act on one book, computed once per submitted preferences
 * against the vendored editor.
 *
 * Controls whose value is already set are checked directly on that editor. Null-valued
 * controls are checked through a candidate editor carrying that one value, so availability
 * never needs a probe and nothing here ever submits preferences (C8). Direction and language
 * are always usable — the writing-system group is therefore always shown (C2) — while its own
 * controls still disable on their own effectiveness. Reasons are presentation only.
 */
class ReaderPreferenceContext private constructor(
  private val layout: Layout,
  private val metadata: Metadata,
  private val submitted: EpubPreferences,
) {
  private val editor = EpubPreferencesEditor(submitted, metadata, layout, EpubDefaults())

  /** The same settings the vendored editor derives, for the disabled reasons' categories. */
  private val settings = EpubSettingsResolver(metadata, EpubDefaults()).settings(submitted)

  private val cssLayout = ReadiumCssLayout.from(settings)
  private val bookTypography = settings.publisherStyles == true
  private val scrolling = settings.scroll == true
  private val darkTheme = settings.theme == Theme.DARK

  val typographySource: Availability =
    if (layout == Layout.REFLOWABLE) Availability.Available else Availability.unavailable(UnavailableReason.NotAvailable)

  val lineHeight: Availability = typographyRow { it.lineHeight.isEffective }

  val textAlign: Availability = typographyRow { it.textAlign.isEffective }

  val paragraphPreset: Availability get() = combine(paragraphIndent, paragraphSpacing)
  /** Null-valued at Default: checked through a candidate editor carrying a value (C8). */
  val paragraphIndent: Availability = typographyRow(candidate = { it.copy(paragraphIndent = 1.6) }) { it.paragraphIndent.isEffective }

  /** Null-valued at Default: checked through a candidate editor carrying a value (C8). */
  val paragraphSpacing: Availability = typographyRow(candidate = { it.copy(paragraphSpacing = 1.0) }) { it.paragraphSpacing.isEffective }

  /** Null-valued at Default, and excluded in RTL writing systems. */
  val letterSpacing: Availability = typographyRow(candidate = { it.copy(letterSpacing = 0.5) }, ltrOnly = true) { it.letterSpacing.isEffective }

  /** Null-valued at Default, and excluded in RTL writing systems. */
  val wordSpacing: Availability = typographyRow(candidate = { it.copy(wordSpacing = 0.5) }, ltrOnly = true) { it.wordSpacing.isEffective }

  /** Null-valued at Default: checked through a candidate editor carrying a value (C8). */
  val weight: Availability = typographyRow(candidate = { it.copy(fontWeight = 1.25) }) { it.fontWeight.isEffective }

  /** Factory value On; excluded in RTL writing systems once the reader sets it to Auto. */
  val hyphens: Availability = typographyRow(candidate = { it.copy(hyphens = true) }, ltrOnly = true) { it.hyphens.isEffective }

  /** Null at factory, and only effective in RTL writing systems. */
  val ligatures: Availability = typographyRow(candidate = { it.copy(ligatures = true) }, rtlOnly = true) { it.ligatures.isEffective }

  val verticalText: Availability =
    if (layout == Layout.REFLOWABLE && editor.verticalText.isEffective) Availability.Available
    else Availability.unavailable(UnavailableReason.NotAvailable)

  val simplifyTypography: Availability = typographyRow { it.textNormalization.isEffective }

  val readingDirection: Availability = Availability.Available

  val images: Availability = when {
    !darkTheme -> Availability.unavailable(UnavailableReason.Theme)
    editor.imageFilter.isEffective -> Availability.Available
    else -> Availability.unavailable(UnavailableReason.NotAvailable)
  }

  /** Scroll has no columns or spreads to choose between (the mode reason). */
  val pageLayout: Availability = when {
    layout == Layout.REFLOWABLE && scrolling -> Availability.unavailable(UnavailableReason.Mode)
    layout == Layout.REFLOWABLE && editor.columnCount.isEffective -> Availability.Available
    layout != Layout.REFLOWABLE && editor.spread.isEffective -> Availability.Available
    else -> Availability.unavailable(UnavailableReason.NotAvailable)
  }

  /**
   * A typography-dependent row: unavailable outside reflowable layouts or under book
   * typography; [candidate] removes the row's own null-gating for the check.
   */
  private fun typographyRow(
    candidate: ((EpubPreferences) -> EpubPreferences)? = null,
    ltrOnly: Boolean = false,
    rtlOnly: Boolean = false,
    check: (EpubPreferencesEditor) -> Boolean,
  ): Availability = when {
    layout != Layout.REFLOWABLE -> Availability.unavailable(UnavailableReason.NotAvailable)
    bookTypography -> Availability.unavailable(UnavailableReason.BookTypography)
    ltrOnly && cssLayout.stylesheets != ReadiumCssLayout.Stylesheets.Default -> Availability.unavailable(UnavailableReason.WritingSystem)
    rtlOnly && cssLayout.stylesheets != ReadiumCssLayout.Stylesheets.Rtl -> Availability.unavailable(UnavailableReason.WritingSystem)
    else -> if (check(candidate?.let(::candidate) ?: editor)) Availability.Available
    else Availability.unavailable(UnavailableReason.NotAvailable)
  }

  /** A candidate editor: the submitted preferences with one more field set (C8). */
  private fun candidate(transform: (EpubPreferences) -> EpubPreferences): EpubPreferencesEditor =
    EpubPreferencesEditor(transform(submitted), metadata, layout, EpubDefaults())

  /** The preset needs both of its rows. */
  private fun combine(a: Availability, b: Availability): Availability = when {
    a.available && b.available -> Availability.Available
    a.reason != UnavailableReason.None -> a
    else -> b
  }

  companion object {
    /** The context for a book's currently submitted preferences. */
    fun of(publication: Publication, preferences: EpubPreferences): ReaderPreferenceContext =
      ReaderPreferenceContext(publication.metadata.layout ?: Layout.REFLOWABLE, publication.metadata, preferences)

    /** One neutral reflowable context for authoring the globals in Settings: the sample book
     * is reflowable, reads left-to-right, in a dark theme, in Pages mode, with Quire typography —
     * exactly what the factory mapping submits. */
    fun neutralReflowable(): ReaderPreferenceContext {
      val mapped = ReaderPrefs().toEpubPreferences(Layout.REFLOWABLE)
      val metadata = Metadata(identifier = "quire:settings", localizedTitle = LocalizedString("Settings"))
      return ReaderPreferenceContext(Layout.REFLOWABLE, metadata, mapped)
    }
}
}
