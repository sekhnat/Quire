package com.quire.reader.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.DirectionPref
import com.quire.reader.data.ImageFilterPref
import com.quire.reader.data.PageLayoutPref
import com.quire.reader.data.ParagraphPreset
import com.quire.reader.data.SpacingLevel
import com.quire.reader.data.TriState
import com.quire.reader.data.TypographySource
import com.quire.reader.data.VerticalTextPref
import com.quire.reader.data.WidenLevel
import com.quire.reader.data.WeightLevel
import com.quire.reader.reader.Availability
import com.quire.reader.reader.ReaderPreferenceContext
import com.quire.reader.reader.UnavailableReason
import com.quire.reader.ui.Ic
import com.quire.reader.ui.Kicker
import com.quire.reader.theme.Nq
import com.quire.reader.ui.Ph
import com.quire.reader.ui.QText
import com.quire.reader.ui.Toggle

/** Why a control is greyed out, as the row shows it. Presentation only, never decides enablement. */
internal fun Availability?.reasonText(): String? = when {
  this == null || available -> null
  reason == UnavailableReason.BookTypography -> "The book's own typography is in use"
  reason == UnavailableReason.Mode -> "Not used while scrolling"
  reason == UnavailableReason.Theme -> "Applies in dark themes"
  reason == UnavailableReason.WritingSystem -> "Not used for this writing system"
  else -> "Not available for this book"
}

/** One choosable value of a picker row. */
private class Option(val label: String, val selected: Boolean, val enabled: Boolean, val choose: () -> Unit)

/**
 * The advanced typography and page-layout controls, shared by the reader's Display sheet and
 * Settings. Full-width discrete picker rows — label plus semantic value, options in a dropdown —
 * and a Simplify typography toggle. Disabled rows keep their values and show why; availability
 * is presentation-gated only, and values are never shown as mapped numbers.
 */
@Composable
internal fun AdvancedReadingControls(
  advanced: AdvancedReaderPrefs,
  availability: ReaderPreferenceContext?,
  onAdvanced: ((AdvancedReaderPrefs) -> AdvancedReaderPrefs) -> Unit,
  onPreset: (ParagraphPreset) -> Unit,
  /** Line spacing is a basic setting (the basic row offers three of these steps); the picker here offers them all. */
  lineHeight: Float,
  onLineHeight: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Kicker("Typography")
    PickerRow("Typography source", advanced.typographySource.label(), availability?.typographySource, TypographySource.entries.map { source ->
      Option(source.label(), source == advanced.typographySource, enabled = true) { onAdvanced { it.copy(typographySource = source) } }
    })
    ToggleRow("Simplify typography", "Even out fonts and sizes for easier reading", availability?.simplifyTypography, advanced.simplifyTypography) { on ->
      onAdvanced { it.copy(simplifyTypography = on) }
    }

    Kicker("Paragraphs")
    PickerRow("Paragraphs", advanced.paragraphPreset.label(), availability?.paragraphPreset, listOf(ParagraphPreset.Default, ParagraphPreset.Traditional, ParagraphPreset.Screen).map { preset ->
      Option(preset.label(), preset == advanced.paragraphPreset, enabled = true) { onPreset(preset) }
    })
    PickerRow("First-line indent", advanced.paragraphIndent.label(), availability?.paragraphIndent, SpacingLevel.entries.map { level ->
      Option(level.label(), level == advanced.paragraphIndent, enabled = true) { onAdvanced { it.copy(paragraphIndent = level) } }
    })
    PickerRow("Paragraph spacing", advanced.paragraphSpacing.label(), availability?.paragraphSpacing, SpacingLevel.entries.map { level ->
      Option(level.label(), level == advanced.paragraphSpacing, enabled = true) { onAdvanced { it.copy(paragraphSpacing = level) } }
    })

    Kicker("Text spacing")
    PickerRow("Line spacing", lineSpacingLabel(lineHeight), availability?.lineHeight, LineSpacingSteps.map { (value, label) ->
      Option(label, value == lineHeight, enabled = true) { onLineHeight(value) }
    })
    PickerRow("Letter spacing", advanced.letterSpacing.label(), availability?.letterSpacing, WidenLevel.entries.map { level ->
      Option(level.label(), level == advanced.letterSpacing, enabled = true) { onAdvanced { it.copy(letterSpacing = level) } }
    })
    PickerRow("Word spacing", advanced.wordSpacing.label(), availability?.wordSpacing, WidenLevel.entries.map { level ->
      Option(level.label(), level == advanced.wordSpacing, enabled = true) { onAdvanced { it.copy(wordSpacing = level) } }
    })
    PickerRow("Weight", advanced.fontWeight.label(), availability?.weight, WeightLevel.entries.map { level ->
      Option(level.label(), level == advanced.fontWeight, enabled = true) { onAdvanced { it.copy(fontWeight = level) } }
    })

    Kicker("Writing system")
    PickerRow("Hyphenation", advanced.hyphens.label(), availability?.hyphens, TriState.entries.map { state ->
      Option(state.label(), state == advanced.hyphens, enabled = true) { onAdvanced { it.copy(hyphens = state) } }
    })
    PickerRow("Ligatures", advanced.ligatures.label(), availability?.ligatures, TriState.entries.map { state ->
      Option(state.label(), state == advanced.ligatures, enabled = true) { onAdvanced { it.copy(ligatures = state) } }
    })
    PickerRow("Vertical text", advanced.verticalText.label(), availability?.verticalText, VerticalTextPref.entries.map { pref ->
      Option(pref.label(), pref == advanced.verticalText, enabled = true) { onAdvanced { it.copy(verticalText = pref) } }
    })
    PickerRow("Reading direction", advanced.readingDirection.label(), availability?.readingDirection, DirectionPref.entries.map { pref ->
      Option(pref.label(), pref == advanced.readingDirection, enabled = true) { onAdvanced { it.copy(readingDirection = pref) } }
    })

    Kicker("Page")
    PickerRow("Images", advanced.imageFilter.label(), availability?.images, ImageFilterPref.entries.map { pref ->
      Option(pref.label(), pref == advanced.imageFilter, enabled = true) { onAdvanced { it.copy(imageFilter = pref) } }
    })
    PickerRow("Page layout", advanced.pageLayout.label(), availability?.pageLayout, PageLayoutPref.entries.map { pref ->
      Option(pref.label(), pref == advanced.pageLayout, enabled = true) { onAdvanced { it.copy(pageLayout = pref) } }
    })
  }
}

/**
 * A full-width picker row: the label (and why not) on the left, the semantic value on the right.
 * Tapping it opens the options in a dropdown anchored to the row, drawn above the sheet.
 */
@Composable
private fun PickerRow(label: String, value: String, availability: Availability?, options: List<Option>) {
  val reason = availability.reasonText()
  val enabled = availability?.available != false
  var expanded by remember { mutableStateOf(false) }
  Box(Modifier.fillMaxWidth()) {
    Row(
      Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .clip(RoundedCornerShape(8.dp))
        .alpha(if (enabled) 1f else 0.6f)
        .clickable(enabled = enabled) { expanded = true }
        .padding(vertical = 10.dp, horizontal = 4.dp)
        .semantics { stateDescription = if (!enabled) "Unavailable" else value },
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        QText(label, 13.5f, color = if (enabled) Nq.text else Nq.neutral500)
        if (reason != null) QText(reason, 11f, color = Nq.neutral500)
      }
      QText(value, 13f, color = if (enabled) Nq.neutral300 else Nq.neutral500, maxLines = 1)
      if (enabled) Ph(Ic.CaretDown, 14.dp, Nq.neutral500)
    }
    DropdownMenu(
      expanded = expanded && enabled,
      onDismissRequest = { expanded = false },
      Modifier.widthIn(min = 180.dp),
      shape = RoundedCornerShape(8.dp),
      containerColor = Nq.surface,
      border = BorderStroke(1.dp, Nq.neutral800),
    ) {
      options.forEach { option ->
        Row(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .alpha(if (option.enabled) 1f else 0.45f)
            .clickable(enabled = option.enabled) { option.choose(); expanded = false }
            .padding(horizontal = 14.dp)
            .semantics { stateDescription = if (option.selected) "Selected" else if (!option.enabled) "Unavailable" else option.label },
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          QText(option.label, 14f, Modifier.weight(1f), color = if (option.selected) Nq.accent200 else Nq.text)
          if (option.selected) Ph(Ic.CheckCircle, 16.dp, Nq.accent)
        }
      }
    }
  }
}

/** A full-width toggle row. */
@Composable
private fun ToggleRow(label: String, sub: String, availability: Availability?, on: Boolean, onToggle: (Boolean) -> Unit) {
  val reason = availability.reasonText()
  val enabled = availability?.available != false
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .clip(RoundedCornerShape(8.dp))
      .clickable(enabled = enabled) { onToggle(!on) }
      .padding(vertical = 8.dp, horizontal = 4.dp)
      .semantics { stateDescription = if (!enabled) "Unavailable" else if (on) "On" else "Off" },
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      QText(label, 13.5f, color = if (enabled) Nq.text else Nq.neutral500)
      QText(if (reason != null) reason else sub, 11f, color = Nq.neutral500)
    }
    com.quire.reader.ui.Toggle(on, { onToggle(!on) }, enabled = enabled)
  }
}

/** The line-spacing steps, within the 1.0–2.0 range Readium supports; 1.4, 1.6 and 1.85 are the basic row's. */
private val LineSpacingSteps = listOf(1.2f to "Tight", 1.4f to "Compact", 1.6f to "Normal", 1.85f to "Relaxed", 2.0f to "Loose")

/** The step nearest [value], so a line height saved by an older version still reads as a word, never a number. */
private fun lineSpacingLabel(value: Float) = LineSpacingSteps.minBy { kotlin.math.abs(it.first - value) }.second

private fun TypographySource.label() = when (this) { TypographySource.Quire -> "Quire typography"; TypographySource.Book -> "Book typography" }
private fun SpacingLevel.label() = when (this) { SpacingLevel.None -> "None"; SpacingLevel.Small -> "Small"; SpacingLevel.Medium -> "Medium"; SpacingLevel.Large -> "Large"; SpacingLevel.Default -> "Default" }
private fun WidenLevel.label() = when (this) { WidenLevel.Slight -> "Slight"; WidenLevel.Wider -> "Wider"; WidenLevel.VeryWide -> "Very wide"; WidenLevel.Max -> "Max"; WidenLevel.Default -> "Default" }
private fun WeightLevel.label() = when (this) { WeightLevel.Light -> "Light"; WeightLevel.Regular -> "Regular"; WeightLevel.Medium -> "Medium"; WeightLevel.Heavy -> "Heavy"; WeightLevel.Default -> "Default" }
private fun TriState.label() = when (this) { TriState.Auto -> "Automatic"; TriState.On -> "On"; TriState.Off -> "Off" }
private fun VerticalTextPref.label() = when (this) { VerticalTextPref.Automatic -> "Automatic"; VerticalTextPref.Horizontal -> "Horizontal"; VerticalTextPref.Vertical -> "Vertical" }
private fun DirectionPref.label() = when (this) { DirectionPref.Automatic -> "Automatic"; DirectionPref.LeftToRight -> "Left to right"; DirectionPref.RightToLeft -> "Right to left" }
private fun ImageFilterPref.label() = when (this) { ImageFilterPref.Original -> "Original"; ImageFilterPref.Darken -> "Darken"; ImageFilterPref.Invert -> "Invert" }
private fun PageLayoutPref.label() = when (this) { PageLayoutPref.Auto -> "Automatic"; PageLayoutPref.Single -> "Single page"; PageLayoutPref.Two -> "Two pages" }
private fun ParagraphPreset.label() = when (this) { ParagraphPreset.Default -> "Default"; ParagraphPreset.Traditional -> "Traditional"; ParagraphPreset.Screen -> "Screen"; ParagraphPreset.Custom -> "Custom" }
