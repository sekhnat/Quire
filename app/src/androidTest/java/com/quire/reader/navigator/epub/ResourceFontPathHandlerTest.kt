package com.quire.reader.navigator.epub

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewAssetLoader
import com.quire.reader.reader.ReaderFontResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The UI fonts kept only in res/font still reach the reader's WebView at their reading-font paths. */
@RunWith(AndroidJUnit4::class)
class ResourceFontPathHandlerTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  // Built as WebViewServer builds its assets loader.
  private val loader = WebViewAssetLoader.Builder()
    .setDomain(WebViewServer.ASSETS_HOSTNAME)
    .addPathHandler("/fonts/", ResourceFontPathHandler(app, ReaderFontResources))
    .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(app))
    .build()

  private fun fetch(path: String) = loader.shouldInterceptRequest(Uri.parse("https://${WebViewServer.ASSETS_HOSTNAME}/$path"))

  @Test fun `every res-font reading font is served as a TrueType font`() {
    ReaderFontResources.keys.forEach { path ->
      val response = checkNotNull(fetch(path)) { "$path is not served" }
      assertEquals(path, "font/ttf", response.mimeType)
      val head = response.data.use { it.readNBytes(4) }
      assertTrue("$path does not start like a TrueType file", head.contentEquals(byteArrayOf(0, 1, 0, 0)))
    }
  }

  @Test fun `other reading fonts still come from the assets`() {
    val response = checkNotNull(fetch("fonts/lora.ttf")) { "fonts/lora.ttf is not served" }
    assertTrue(response.data.use { it.read() } >= 0)
  }

  @Test fun `an unknown font is not answered from res-font`() {
    assertNull(ResourceFontPathHandler(app, ReaderFontResources).handle("missing.ttf"))
  }
}
