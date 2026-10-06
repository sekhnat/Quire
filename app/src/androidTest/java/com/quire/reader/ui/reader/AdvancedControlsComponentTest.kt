package com.quire.reader.ui.reader

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.quire.reader.data.AdvancedReaderPrefs
import com.quire.reader.data.WidenLevel
import com.quire.reader.navigator.epub.EpubPreferences
import com.quire.reader.reader.ReaderPreferenceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication

/**
 * The advanced controls' UI mechanics, on the real composable: every group and row renders
 * with its semantic value, the chooser lists a row's options and choosing one applies it and
 * closes, disabled rows keep their values and show why, and the rows carry their state
 * descriptions for TalkBack. The rows are at least 48dp tall (heightIn in the composable).
 */
class AdvancedControlsComponentTest {
  @get:Rule val compose = createComposeRule()

  private fun hasState(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

  private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  private fun bookTypographyContext(): ReaderPreferenceContext = ReaderPreferenceContext.of(
    Publication(Manifest(metadata = Metadata(identifier = "t", localizedTitle = LocalizedString("t"), layout = Layout.REFLOWABLE))),
    EpubPreferences(publisherStyles = true),
  )

  private fun setState(initial: AdvancedReaderPrefs, availability: ReaderPreferenceContext = ReaderPreferenceContext.neutralReflowable()): MutableState<AdvancedReaderPrefs> {
    val state = mutableStateOf(initial)
    compose.setContent {
      AdvancedReadingControls(
        advanced = state.value,
        availability = availability,
        onAdvanced = { change -> state.value = change(state.value) },
        onPreset = { preset -> state.value = state.value.withPreset(preset) ?: state.value },
      )
    }
    return state
  }

  @Test fun `every group and row renders with its semantic value`() {
    setState(AdvancedReaderPrefs())
    for (row in listOf(
      "TYPOGRAPHY", "Quire typography",
      "PARAGRAPHS", "First-line indent", "Paragraph spacing",
      "TEXT SPACING", "Letter spacing", "Word spacing", "Weight",
      "WRITING SYSTEM", "Hyphenation", "Ligatures", "Vertical text", "Reading direction",
      "PAGE", "Images", "Page layout",
    )) {
      check(exists(row)) { "$row missing" }
    }
    // The values repeat across rows, so only their presence is asserted.
    check(exists("Default"))
    check(exists("Quire typography"))
    check(exists("On"))
    check(exists("Original"))
  }

  @Test fun `the chooser lists the options and choosing one applies it`() {
    val state = setState(AdvancedReaderPrefs())
    compose.onNodeWithText("Letter spacing").performClick()
    compose.waitUntil(5_000) { exists("Slight") }
    for (option in listOf("Default", "Slight", "Wider", "Very wide", "Max")) check(exists(option))
    compose.onNodeWithText("Wider").performClick()
    compose.waitUntil(5_000) { state.value.letterSpacing == WidenLevel.Wider }
    // The chooser closed with the choice applied, and the row now shows it.
    compose.waitUntil(5_000) { !exists("Very wide") }
    check(exists("Wider"))
  }

  @Test fun `the paragraph preset offers exactly the three presets`() {
    setState(AdvancedReaderPrefs())
    compose.onNodeWithText("Paragraphs").performClick()
    compose.waitUntil(5_000) { exists("Traditional") }
    check(exists("Default"))
    check(exists("Screen"))
    // Custom is a status, never a choice.
    compose.onAllNodesWithText("Custom").assertCountEquals(0)
  }

  @Test fun `disabled rows show the reason and keep their value and stay closed`() {
    val state = setState(AdvancedReaderPrefs(), bookTypographyContext())
    check(exists("The book's own typography is in use"))
    // The row keeps its value while it is out of action.
    check(exists("Default"))
    // Tapping a disabled row must not open the chooser.
    compose.onNodeWithText("Letter spacing").performClick()
    compose.onAllNodesWithText("Very wide").assertCountEquals(0)
    assertEquals(WidenLevel.Default, state.value.letterSpacing)
  }

  @Test fun `rows carry their state descriptions for TalkBack`() {
    setState(AdvancedReaderPrefs())
    compose.onNodeWithText("First-line indent").assert(hasState("Default")) { "state" }
    compose.onNodeWithText("Hyphenation").assert(hasState("On")) { "state" }
  }
}
