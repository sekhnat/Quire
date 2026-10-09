package com.quire.reader.reader

import android.content.Context
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Starts the WebView engine before the first book is opened. The first WebView in a process loads the WebView package
 * and starts Chromium, several hundred milliseconds that otherwise land on the first book the reader opens. Asking for
 * the default user agent does both without making a WebView: the package loads on a background thread, and Chromium
 * posts its own start-up to the main thread, which runs it when it next has a moment.
 */
object WebViewWarmup {
  private val started = AtomicBoolean(false)

  /** Warms the engine once the main thread is next idle (the first screen is up); later calls do nothing. */
  fun startWhenIdle(context: Context) {
    if (started.get()) return
    val app = context.applicationContext
    Looper.myQueue().addIdleHandler {
      if (started.compareAndSet(false, true)) {
        thread(name = "WebViewWarmup", isDaemon = true) {
          // A missing or updating WebView package fails here; the reader reports it when a book is opened.
          runCatching { WebSettings.getDefaultUserAgent(app) }.onFailure { Log.w("WebViewWarmup", "WebView warm-up failed", it) }
        }
      }
      false
    }
  }
}
