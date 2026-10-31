package com.quire.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudVoicesTest {
  private fun voice(id: String, label: String) = VoiceOption("en-AU", id, label)

  @Test fun `voices that read the same are numbered in id order`() {
    val shown = numberedVoices(
      listOf(voice("c", "English (Australia) · High"), voice("a", "English (Australia) · High"), voice("b", "English (Australia) · High")),
    )
    assertEquals(listOf("a", "b", "c"), shown.map { it.id })
    assertEquals(
      listOf("English (Australia) · High · 1", "English (Australia) · High · 2", "English (Australia) · High · 3"),
      shown.map { it.label },
    )
  }

  @Test fun `a voice with a label of its own is left as it is`() {
    val shown = numberedVoices(listOf(voice("x", "English (India) · High"), voice("a", "English (Australia) · High"), voice("b", "English (Australia) · High")))
    assertEquals(
      listOf("English (Australia) · High · 1", "English (Australia) · High · 2", "English (India) · High"),
      shown.map { it.label },
    )
  }

  @Test fun `numbering does not change which voice is which`() {
    val voices = listOf(voice("b", "English (Australia) · High"), voice("a", "English (Australia) · High"))
    assertEquals(numberedVoices(voices), numberedVoices(voices.reversed()))
  }

  @Test fun `no voices is no list`() = assertEquals(emptyList<VoiceOption>(), numberedVoices(emptyList()))
}
