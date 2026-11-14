package com.quire.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.navigator.media.tts.android.AndroidTtsEngine.Voice
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language

@OptIn(ExperimentalReadiumApi::class)
class PreferredVoiceTest {
  private fun voice(id: String, language: String, quality: Voice.Quality = Voice.Quality.Normal, network: Boolean = false) =
    Voice(Voice.Id(id), Language(language), quality, network)

  private fun pick(language: String?, vararg voices: Voice) = preferredVoice(language?.let(::Language), voices.toSet())?.id?.value

  @Test fun `an English book is read in English even when another language has a better voice`() {
    assertEquals(
      "en-normal",
      pick("en", voice("es-highest", "es-US", Voice.Quality.Highest), voice("en-normal", "en", Voice.Quality.Normal)),
    )
  }

  @Test fun `the book's region comes first, then the best voice of the language`() {
    val us = voice("en-us", "en-US", Voice.Quality.Low)
    val gb = voice("en-gb", "en-GB", Voice.Quality.High)
    assertEquals("en-us", pick("en-US", us, gb))
    assertEquals("en-gb", pick("en-US", gb, voice("fr", "fr-FR", Voice.Quality.Highest)))
    assertEquals("en-gb", pick("en", us, gb))
  }

  @Test fun `regions match whatever their case`() {
    assertEquals("en-us", pick("en-us", voice("en-us", "en-US", Voice.Quality.Low), voice("en-gb", "en-GB", Voice.Quality.High)))
  }

  @Test fun `a three-letter language code finds the two-letter voices`() {
    assertEquals("en", pick("eng", voice("en", "en-US"), voice("de", "de-DE", Voice.Quality.Highest)))
  }

  @Test fun `a voice that needs a connection is never chosen`() {
    assertEquals("local", pick("en", voice("online", "en-US", Voice.Quality.Highest, network = true), voice("local", "en-US", Voice.Quality.Low)))
    assertNull(pick("en", voice("online", "en-US", Voice.Quality.Highest, network = true)))
  }

  @Test fun `no voice in the book's language leaves it to the engine`() {
    assertNull(pick("ja", voice("en", "en-US"), voice("es", "es-US")))
    assertNull(pick(null, voice("en", "en-US")))
  }
}
