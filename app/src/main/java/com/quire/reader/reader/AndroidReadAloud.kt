package com.quire.reader.reader

import android.app.Application
import android.content.Intent
import com.quire.reader.data.ReadAloudPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language

private typealias AndroidTtsNavigator = TtsNavigator<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>

/**
 * Readium's text-to-speech navigator over Android's own speech engine, which speaks on the device with no connection. Its
 * Media3 player carries the audio focus, the pause when headphones are pulled out and the controls the media session shows,
 * so the reader plays and pauses through that player rather than through the navigator.
 */
@OptIn(ExperimentalReadiumApi::class)
class AndroidReadAloudOpener(private val app: Application) : ReadAloudOpener {
  override suspend fun open(
    publication: Publication, from: Locator?, prefs: ReadAloudPrefs, scope: CoroutineScope, onStopRequested: () -> Unit,
  ): Result<ReadAloudPlayback> {
    val factory = TtsNavigatorFactory(app, publication, voiceSelector = ::preferredVoice)
      ?: return Result.failure(ReadAloudUnavailable("This book can’t be read aloud."))
    val initial = AndroidTtsPreferences(
      speed = prefs.speed,
      voices = prefs.voices.entries.associate { (language, id) -> Language(language) to AndroidTtsEngine.Voice.Id(id) },
    )
    val navigator = factory.createNavigator(object : TtsNavigator.Listener { override fun onStopRequested() = onStopRequested() }, from, initial)
      .fold({ it }, { error -> return Result.failure(ReadAloudUnavailable(unavailableMessage(error))) })
    return Result.success(AndroidReadAloud(app, navigator, initial, scope))
  }

  private fun unavailableMessage(error: TtsNavigatorFactory.Error) = when (error) {
    is TtsNavigatorFactory.Error.UnsupportedPublication -> "This book can’t be read aloud."
    is TtsNavigatorFactory.Error.EngineInitialization -> "No text-to-speech engine is available. Install one in Android settings."
  }

  /**
   * The best voice that works on the device. A voice that needs a connection would send the book's text to a server,
   * which the app promises never to do, so none is chosen for the person (null leaves it to the engine's own default).
   */
  private fun preferredVoice(language: Language?, voices: Set<AndroidTtsEngine.Voice>): AndroidTtsEngine.Voice? =
    voices.filter { !it.requiresNetwork }.maxByOrNull { it.quality.ordinal }
}

@OptIn(ExperimentalReadiumApi::class)
private class AndroidReadAloud(
  private val app: Application,
  private val navigator: AndroidTtsNavigator,
  private var preferences: AndroidTtsPreferences,
  scope: CoroutineScope,
) : ReadAloudPlayback {
  private val player = navigator.asMedia3Player()

  override val playing: StateFlow<Boolean> = navigator.playback.map { it.playWhenReady && it.state is TtsNavigator.State.Ready }
    .stateIn(scope, SharingStarted.Eagerly, false)

  override val utterance: StateFlow<ReadAloudPosition?> = navigator.location
    .map { ReadAloudPosition(it.utteranceLocator, it.tokenLocator ?: it.utteranceLocator) }
    .stateIn(scope, SharingStarted.Eagerly, null)

  override val outcome: StateFlow<ReadAloudOutcome?> = navigator.playback.map { playback ->
    when (val state = playback.state) {
      is TtsNavigator.State.Failure -> ReadAloudOutcome.Failed(state.error.message)
      is TtsNavigator.State.Ended -> ReadAloudOutcome.Ended
      else -> null
    }
  }.stateIn(scope, SharingStarted.Eagerly, null)

  override val language: String get() = navigator.settings.value.language.code

  init {
    ReadAloudRegistry.player = player
    app.startService(Intent(app, ReadAloudService::class.java))
  }

  override fun play() = player.play()
  override fun pause() = player.pause()
  override fun next() = navigator.skipToNextUtterance()
  override fun previous() = navigator.skipToPreviousUtterance()

  override fun setSpeed(speed: Double) {
    preferences = preferences.copy(speed = speed)
    navigator.submitPreferences(preferences)
  }

  override fun voices(): List<VoiceOption> = numberedVoices(
    navigator.voices.filter { !it.requiresNetwork }.map { VoiceOption(it.language.code, it.id.value, "${it.language.locale.displayName} · ${it.quality.name}") },
  )

  override fun setVoice(language: String, id: String) {
    preferences = preferences.copy(voices = preferences.voices.orEmpty() + (Language(language) to AndroidTtsEngine.Voice.Id(id)))
    navigator.submitPreferences(preferences)
  }

  override fun close() {
    if (ReadAloudRegistry.player === player) ReadAloudRegistry.player = null
    app.stopService(Intent(app, ReadAloudService::class.java))
    navigator.close()
  }
}
