package com.quire.reader.data

import com.quire.reader.reader.speedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadAloudPrefsTest {
  @Test fun `the speed button steps up through the speeds and wraps round`() {
    assertEquals(listOf(1.25, 1.5, 1.75, 2.0, 0.75, 1.0), generateSequence(1.0) { ReadAloudPrefs.nextSpeed(it) }.drop(1).take(6).toList())
  }

  @Test fun `a speed between two steps goes up to the next one`() {
    assertEquals(1.25, ReadAloudPrefs.nextSpeed(1.1), 0.0)
    assertEquals(0.75, ReadAloudPrefs.nextSpeed(3.0), 0.0)
  }

  @Test fun `speeds read as people say them`() {
    assertEquals("0.75×", speedLabel(0.75))
    assertEquals("1×", speedLabel(1.0))
    assertEquals("1.5×", speedLabel(1.5))
    assertEquals("2×", speedLabel(2.0))
  }

  @Test fun `a voice is remembered for its language, replacing the one before`() {
    val prefs = ReadAloudPrefs().withVoice("en-US", "a").withVoice("fr-FR", "b").withVoice("en-US", "c")
    assertEquals(mapOf("en-US" to "c", "fr-FR" to "b"), prefs.voices)
  }

  @Test fun `preferences round trip through JSON, and damaged JSON is not preferences`() {
    val prefs = ReadAloudPrefs(speed = 1.75, voices = mapOf("de-DE" to "x"))
    assertEquals(prefs, ReadAloudPrefs.fromJson(prefs.toJson()))
    assertNull(ReadAloudPrefs.fromJson("{not json"))
    assertNull(ReadAloudPrefs.fromJson(null))
  }
}
