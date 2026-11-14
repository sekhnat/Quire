package com.quire.reader.ui.reader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import com.quire.reader.data.ImageFilterPref
import com.quire.reader.navigator.epub.ReaderMedia
import com.quire.reader.theme.Nq
import com.quire.reader.theme.ReaderTheme
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Ic
import com.quire.reader.ui.QText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen zoom limits and the double-tap zoom. */
internal object MediaZoom {
  const val MAX = 8f
  const val DOUBLE_TAP = 2.5f

  /** [offset] kept so content of [size] scaled by [scale] never leaves a gap at the edges of a [view]-sized box. */
  fun clamp(offset: Offset, scale: Float, size: IntSize, view: IntSize): Offset {
    val maxX = ((size.width * scale - view.width) / 2f).coerceAtLeast(0f)
    val maxY = ((size.height * scale - view.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
  }
}

/**
 * An image, SVG or table opened full screen over the reader: pinch to zoom (1–8×), drag to pan, double-tap to toggle
 * 2.5×, Back or ✕ to return to exactly where the reader was (the page underneath never moves).
 */
@Composable
internal fun MediaViewer(
  media: ReaderMedia,
  theme: ReaderTheme,
  imageFilter: ImageFilterPref,
  loadBytes: suspend (ReaderMedia.Image) -> ByteArray?,
  intercept: (WebResourceRequest) -> WebResourceResponse?,
  onClose: () -> Unit,
  toast: (String) -> Unit,
) {
  BackHandler(onBack = onClose)
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // Dark themes show images as the page does (dimmed or inverted); "Original" shows them untouched.
  var original by remember(media) { mutableStateOf(false) }
  val filtered = !theme.isLight && imageFilter != ImageFilterPref.Original && !original
  Box(Modifier.fillMaxSize().background(theme.bg).pointerInput(Unit) { detectTapGestures { } }) {
    when (media) {
      is ReaderMedia.Image -> {
        val bytes by produceState<ByteArray?>(null, media) { value = withContext(Dispatchers.IO) { loadBytes(media) } }
        val bitmap = remember(bytes) { bytes?.let { decodeCapped(it) } }
        if (bitmap != null) ZoomableImage(bitmap, if (filtered) filterFor(imageFilter) else null)
        else if (bytes != null) QText("This image can't be shown", 13f, Modifier.align(Alignment.Center), color = theme.muted)
      }
      is ReaderMedia.Markup -> MarkupView(media, theme, intercept)
    }
    Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconBtn(Ic.X, onClose, tint = theme.fg)
      Box(Modifier.weight(1f))
      if (media is ReaderMedia.Image && !theme.isLight && imageFilter != ImageFilterPref.Original) {
        QText(if (original) "As on the page" else "Original", 13f, Modifier.padding(horizontal = 8.dp).pointerInput(Unit) { detectTapGestures { original = !original } }, color = theme.fg)
      }
      if (media is ReaderMedia.Image) IconBtn(Ic.ShareNetwork, {
        scope.launch {
          val ok = runCatching { shareImage(context, media, loadBytes(media) ?: error("no bytes")) }.isSuccess
          if (!ok) toast("Couldn't share the image")
        }
      }, tint = theme.fg)
    }
    val caption = when (media) { is ReaderMedia.Image -> media.alt; is ReaderMedia.Markup -> media.caption }
    if (!caption.isNullOrBlank()) {
      QText(caption, 12.5f, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().background(theme.bg.copy(alpha = 0.85f)).padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(),
        color = theme.muted, align = TextAlign.Center, maxLines = 4)
    }
  }
}

@Composable
private fun ZoomableImage(bitmap: ImageBitmap, filter: ColorFilter?) {
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  var view by remember { mutableStateOf(IntSize.Zero) }
  // The image's size on screen at 1×, fitted inside the view.
  val fitted = remember(view, bitmap) {
    if (view.width == 0 || view.height == 0) IntSize.Zero
    else {
      val s = minOf(view.width.toFloat() / bitmap.width, view.height.toFloat() / bitmap.height)
      IntSize((bitmap.width * s).toInt(), (bitmap.height * s).toInt())
    }
  }
  Image(
    bitmap, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = filter,
    modifier = Modifier.fillMaxSize().onSizeChanged { view = it }
      .pointerInput(fitted) {
        detectTapGestures(onDoubleTap = { tap ->
          if (scale > 1.01f) { scale = 1f; offset = Offset.Zero }
          else {
            val target = MediaZoom.DOUBLE_TAP
            // Keep the tapped point under the finger.
            val center = Offset(view.width / 2f, view.height / 2f)
            offset = MediaZoom.clamp((center - tap) * (target - 1f), target, fitted, view)
            scale = target
          }
        })
      }
      .pointerInput(fitted) {
        detectTransformGestures { _, pan, zoom, _ ->
          scale = (scale * zoom).coerceIn(1f, MediaZoom.MAX)
          offset = if (scale <= 1f) Offset.Zero else MediaZoom.clamp(offset + pan, scale, fitted, view)
        }
      }
      .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
  )
}

/**
 * A table or SVG in a WebView of its own, with the book's stylesheets and the reader's colours. JavaScript stays off: it
 * only draws the fragment. Pinch zoom is the WebView's own.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkupView(media: ReaderMedia.Markup, theme: ReaderTheme, intercept: (WebResourceRequest) -> WebResourceResponse?) {
  val html = remember(media, theme) { markupPage(media, theme) }
  AndroidView(
    modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 48.dp),
    factory = { ctx ->
      WebView(ctx).apply {
        settings.javaScriptEnabled = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webViewClient = object : WebViewClient() {
          override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = intercept(request)
          // Nothing in the viewer navigates.
          override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
      }
    },
    update = { it.loadDataWithBaseURL(media.baseUrl.toString(), html, "text/html", "utf-8", null) },
  )
}

/** The page the markup viewer shows: the fragment with the book's stylesheets, in the reader's colours. */
internal fun markupPage(media: ReaderMedia.Markup, theme: ReaderTheme): String {
  fun hex(c: androidx.compose.ui.graphics.Color) = "#%06X".format(0xFFFFFF and android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()))
  val links = media.styles.joinToString("") { """<link rel="stylesheet" href="$it">""" }
  return """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">$links
<style>html,body{background:${hex(theme.bg)} !important;color:${hex(theme.fg)} !important;margin:0;padding:12px}
table{border-collapse:collapse}td,th{border:1px solid ${hex(theme.muted)};padding:4px 8px}svg{max-width:100%;height:auto}</style>
</head><body>${media.html}</body></html>"""
}

/** Decodes [bytes], scaled down by powers of two to at most 4,096 px a side so a huge scan never runs out of memory. */
private fun decodeCapped(bytes: ByteArray): ImageBitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** The page's image filters, for the viewer: Darken dims, Invert flips the colours. */
private fun filterFor(filter: ImageFilterPref): ColorFilter? = when (filter) {
  ImageFilterPref.Original -> null
  ImageFilterPref.Darken -> ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.8f, 0.8f, 0.8f, 1f) })
  ImageFilterPref.Invert -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
}

/** Shares the image file through the app's FileProvider, from `cache/share` like quote cards. */
private suspend fun shareImage(context: Context, media: ReaderMedia.Image, bytes: ByteArray) {
  val type = media.link.mediaType?.toString()?.substringBefore(';') ?: "image/*"
  val ext = when (type) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
  val file = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    File(dir, "image-${System.currentTimeMillis()}.$ext").also { it.writeBytes(bytes) }
  }
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
  val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  send.clipData = ClipData.newRawUri(null, uri)
  context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
