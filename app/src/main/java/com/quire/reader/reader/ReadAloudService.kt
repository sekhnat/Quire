package com.quire.reader.reader

import android.app.PendingIntent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Hands the playing book's player to [ReadAloudService], which is made by the system and cannot be given arguments. */
object ReadAloudRegistry {
  @Volatile var player: Player? = null
}

/**
 * Keeps read aloud going with the screen off or the app in the background, and gives it a notification and lock-screen
 * controls. It holds a media session over the playback's player and nothing else: starting, stopping and everything
 * about the book stays with `ReaderState`, which stops this service when the playback closes.
 */
class ReadAloudService : MediaSessionService() {
  private var session: MediaSession? = null

  override fun onCreate() {
    super.onCreate()
    val player = ReadAloudRegistry.player
    if (player == null) { stopSelf(); return }
    val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
      PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    val made = MediaSession.Builder(this, player).apply { open?.let(::setSessionActivity) }.build()
    session = made
    // The service is started by the app, not bound by a controller, so nothing else would hand it the session: without
    // this there is no notification and the service never moves to the foreground.
    addSession(made)
  }

  /** Only the system and this app may control it, so another app cannot start or stop someone's reading. */
  // Both opt-ins: the compiler reads Kotlin's, and lint only AndroidX's.
  @OptIn(UnstableApi::class)
  @androidx.annotation.OptIn(UnstableApi::class)
  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session?.takeIf { controllerInfo.isTrusted }

  override fun onDestroy() {
    session?.release()
    session = null
    super.onDestroy()
  }
}
