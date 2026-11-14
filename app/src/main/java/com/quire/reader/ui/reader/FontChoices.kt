package com.quire.reader.ui.reader

import android.net.Uri
import com.quire.reader.data.fonts.UserFont
import com.quire.reader.ui.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The fonts the user added, as the reader and Settings reach them (adapted in Features from UserFontLibrary). */
interface UserFontsPort {
  val fonts: Flow<List<UserFont>>
  /** Adds the picked files; returns the message to show. */
  suspend fun add(uris: List<Uri>): String
  suspend fun remove(id: Int)
  /** How many books chose [font] in their own settings. */
  suspend fun booksUsing(font: UserFont): Int
}

/** No user fonts: for screens and tests that do not offer them. */
object NoUserFonts : UserFontsPort {
  override val fonts: Flow<List<UserFont>> = emptyFlow()
  override suspend fun add(uris: List<Uri>) = ""
  override suspend fun remove(id: Int) {}
  override suspend fun booksUsing(font: UserFont) = 0
}

/**
 * The font picker's view of the user's fonts and what it can do with them, shared by the Display sheet and Settings.
 * Writes run on [persist], so leaving the screen never drops a font being added.
 */
class FontChoices(private val port: UserFontsPort, scope: CoroutineScope, private val persist: CoroutineScope, private val toasts: Toasts) {
  val fonts: StateFlow<List<UserFont>> = port.fonts.stateIn(scope, SharingStarted.Eagerly, emptyList())

  fun add(uris: List<Uri>) {
    if (uris.isEmpty()) return
    persist.launch { port.add(uris).takeIf { it.isNotEmpty() }?.let(toasts::show) }
  }

  fun remove(font: UserFont) {
    persist.launch { port.remove(font.id); toasts.show("Removed ${font.family}") }
  }

  suspend fun booksUsing(font: UserFont): Int = port.booksUsing(font)
}
