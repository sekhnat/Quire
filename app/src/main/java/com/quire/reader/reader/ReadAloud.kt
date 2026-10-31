package com.quire.reader.reader

import com.quire.reader.data.ReadAloudPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** Where read aloud is, for the reader's controls. */
enum class ReadAloudStatus { Off, Starting, Playing, Paused }

data class ReadAloudUi(val status: ReadAloudStatus = ReadAloudStatus.Off, val speed: Double = 1.0) {
  val active: Boolean get() = status != ReadAloudStatus.Off
}

/** A voice the phone's speech engine offers, for the voice picker; [language] is a BCP 47 code. */
data class VoiceOption(val language: String, val id: String, val label: String)

/** The sentence being read, and the word in it (or the sentence again when the engine has no words), as places in the book. */
data class ReadAloudPosition(val utterance: Locator, val follow: Locator)

/** How read aloud stopped by itself. */
sealed interface ReadAloudOutcome {
  data object Ended : ReadAloudOutcome
  data class Failed(val message: String) : ReadAloudOutcome
}

/**
 * One book being read aloud: what the reader needs from the speech engine, so that the reader's logic can be tested
 * without one. [close] stops the speech, the notification and everything else the playback holds.
 */
interface ReadAloudPlayback {
  /** True while speaking, false while paused. */
  val playing: StateFlow<Boolean>
  val utterance: StateFlow<ReadAloudPosition?>
  /** Null until the playback ends by itself: after the last sentence, or when the engine fails. */
  val outcome: StateFlow<ReadAloudOutcome?>
  /** The language being read, a BCP 47 code, which decides the voices that can be picked. */
  val language: String

  fun play()
  fun pause()
  fun next()
  fun previous()
  fun setSpeed(speed: Double)
  /** The voices that work without a connection; the app has none. */
  fun voices(): List<VoiceOption>
  fun setVoice(language: String, id: String)
  fun close()
}

/**
 * The voices in the order they are listed, with a number added to any that would read exactly the same, so that several
 * "English (Australia) · High" can be told apart; the number follows the voice's id, which does not change.
 */
fun numberedVoices(voices: List<VoiceOption>): List<VoiceOption> {
  val sorted = voices.sortedWith(compareBy({ it.label }, { it.id }))
  val sameLabel = sorted.groupingBy { it.label }.eachCount()
  val seen = HashMap<String, Int>()
  return sorted.map { voice ->
    if (sameLabel.getValue(voice.label) == 1) voice else voice.copy(label = "${voice.label} · ${seen.merge(voice.label, 1, Int::plus)}")
  }
}

/** A reason read aloud cannot start, worded for the person reading. */
class ReadAloudUnavailable(message: String) : Exception(message)

fun interface ReadAloudOpener {
  /**
   * Starts a playback of [publication] from [from] (the start when null), sounding as [prefs] say. [onStopRequested] is
   * called when the person stops it from outside the reader, such as the notification. Fails with [ReadAloudUnavailable].
   */
  suspend fun open(publication: Publication, from: Locator?, prefs: ReadAloudPrefs, scope: CoroutineScope, onStopRequested: () -> Unit): Result<ReadAloudPlayback>
}

/** What the reader needs to offer read aloud: a way to open a playback, and a way to ask the system for more voices. */
class ReadAloudSupport(val opener: ReadAloudOpener, val installVoices: () -> Unit)

/** "0.75×", "1×", "1.25×". */
fun speedLabel(speed: Double): String = java.math.BigDecimal.valueOf(speed).stripTrailingZeros().toPlainString() + "×"
