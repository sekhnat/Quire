/*
 * Copyright 2024 Quire. Continuous-scroll chapter view, forked from the Readium
 * Kotlin Toolkit 3.3.0's R2EpubPageFragment (BSD-style licensed — see the vendored
 * sources under this package) and reshaped into a plain view for the chapter stack.
 */
//
// One chapter (spine item) of the continuous vertical stack: a WebView laid out at
// its full content height, so the stack container scrolls it like a section of one
// long column. The WebView therefore has no scrolling of its own; taps, selection,
// decorations and links still work through Readium's injected scripts exactly as in
// the stock navigator.

@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class, org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.quire.reader.navigator

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.quire.reader.R
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.postDelayed
import androidx.core.view.updateLayoutParams
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.AbsoluteUrl

internal class ChapterWebView(
  context: Context,
  /** The forked navigator owning the chapter stack. */
  private val navigator: com.quire.reader.navigator.epub.EpubNavigatorFragment,
  /** Index of this chapter in the reading order. */
  val index: Int,
  val link: Link,
  /** Readium-served URL of the chapter resource. */
  private val resourceUrl: AbsoluteUrl,
) : FrameLayout(context), ScriptRunner {

  private var _webView: R2WebView? = null

  val webView: R2WebView
    get() = requireNotNull(_webView)

  private val _isLoaded = MutableStateFlow(false)

  /** True once the resource is loaded, laid out, and its content height measured. */
  override val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

  private var isPageFinished = false
  private var pendingActions = mutableListOf<() -> Unit>()

  /** Height of the laid-out content, in Android px; 0 until first measured. */
  var contentHeight: Int = 0
    private set

  /** Notified whenever the measured content height changes (load, images, fonts). */
  var onContentHeightChanged: (() -> Unit)? = null

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
    }
    return super.dispatchTouchEvent(event)
  }

  init {
    setupWebView()
  }

  @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
  private fun setupWebView() {
    LayoutInflater.from(context).inflate(R.layout.quire_navigator_viewpager_fragment_epub, this, true)
    val webView = findViewById<R2WebView>(R.id.webView)
    _webView = webView

    val listener = navigator.webViewListener
    webView.listener = listener
    for ((name, obj) in listener.javascriptInterfacesForResource(link)) {
      if (obj != null) {
        webView.addJavascriptInterface(obj, name)
      }
    }

    webView.disablePageTurnsWhileScrolling = navigator.config.disablePageTurnsWhileScrolling
    // The chapters are full-height windows in the stack; they don't scroll themselves.
    // The scroll-mode flag keeps Readium's CSS/taps behaving as in scroll mode and the
    // stock page-turn logic inert.
    webView.scrollModeFlow.value = true
    webView.overScrollMode = View.OVER_SCROLL_NEVER
    webView.addJavascriptInterface(webView, "Android")
    webView.settings.javaScriptEnabled = true
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false
    webView.settings.useWideViewPort = true
    webView.settings.loadWithOverviewMode = true
    webView.settings.setSupportZoom(true)
    webView.settings.builtInZoomControls = true
    webView.settings.displayZoomControls = false
    webView.resourceUrl = resourceUrl
    webView.setPadding(0, 0, 0, 0)
    webView.addJavascriptInterface(this, "QuireChapter")

    webView.webViewClient = object : WebViewClientCompat() {

      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return (webView as R2BasicWebView).shouldOverrideUrlLoading(request)
      }

      override fun shouldOverrideKeyEvent(view: WebView, event: android.view.KeyEvent): Boolean =
        false

      override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        isPageFinished = true
        webView.listener?.onResourceLoaded(webView, link)
        webView.onContentReady {
          onLoad()
        }
      }

      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        (webView as R2BasicWebView).shouldInterceptRequest(view, request)
    }

    webView.isHapticFeedbackEnabled = false
    webView.isLongClickable = false
    webView.setOnLongClickListener { false }

    webView.visibility = View.INVISIBLE
    webView.loadUrl(resourceUrl.toString())
    setPadding(0, 0, 0, 0)

    // Forward a tap event when the web view is not ready to propagate the taps. This
    // allows toggling a navigation UI while a page is loading, for example.
    setOnClickListenerWithPoint { _, point ->
      webView.listener?.onTap(point)
    }
  }

  // ── measuring ─────────────────────────────────────────────────────────────

  /** Re-reads the rendered content height and applies it to the WebView's layout. */
  fun remeasure() {
    if (!isPageFinished) return
    val measured = webView.contentHeight
    if (measured <= 0 || measured == contentHeight) return

    contentHeight = measured
    onContentHeightChanged?.invoke()
  }

  /**
   * Called from the injected ResizeObserver bridge whenever the page reflows (late
   * images, font changes, insets). JS interface calls arrive off the main thread.
   */
  @android.webkit.JavascriptInterface
  fun onChapterResized() {
    post { remeasure() }
  }

  private fun onLoad() {
    if (_isLoaded.value) return

    injectResizeObserver()
    remeasure()
    _isLoaded.value = true
    webView.visibility = View.VISIBLE

    val pending = pendingActions
    pendingActions = mutableListOf()
    pending.forEach { it() }

    navigator.webViewListener.onPageLoaded(webView, link)

    // The height callback fired while `isLoaded` was still false; repeat it so the
    // container can resolve any pending jump now that the chapter is ready.
    remeasure()
    onContentHeightChanged?.invoke()
  }

  /** Reports any document reflow back to the host view, including after a font change. */
  private fun injectResizeObserver() {
    webView.runJavaScript(
      """
      if (!window.__quireResizer) {
        window.__quireResizer = new ResizeObserver(function () {
          QuireChapter.onChapterResized();
        });
        window.__quireResizer.observe(document.documentElement);
        window.__quireResizer.observe(document.body);
      }
      """.trimIndent()
    )
  }

  // ── scripting ─────────────────────────────────────────────────────────────

  /** Runs [action] as soon as the chapter is loaded and measured. */
  fun whenLoaded(action: () -> Unit) {
    if (_isLoaded.value) {
      action()
    } else {
      pendingActions.add(action)
    }
  }

  override fun runJavaScript(script: String, callback: ((String) -> Unit)?) {
    whenLoaded {
      webView.runJavaScript(script, callback)
    }
  }

  override suspend fun runJavaScriptSuspend(javascript: String): String = suspendCoroutine { cont ->
    runJavaScript(javascript) { result -> cont.resume(result) }
  }

  override suspend fun awaitLoaded() {
    isLoaded.first { it }
  }

  /**
   * Vertical offset of the element with [htmlId] from the top of this chapter's
   * content, in Android px. Null when the element doesn't exist.
   */
  suspend fun offsetTopForId(htmlId: String): Int? {
    val escaped = JSONObject.quote(htmlId)
    val script =
      "(function () {" +
        " var el = document.getElementById($escaped) || document.getElementsByName($escaped)[0];" +
        " if (!el) return null;" +
        " return { top: el.getBoundingClientRect().top, dpr: window.devicePixelRatio }; })()"
    val json = runJavaScriptSuspend(script).takeIf { it != "null" } ?: return null
    return runCatching {
      val obj = JSONObject(json)
      val top = obj.getDouble("top")
      val dpr = obj.getDouble("dpr").takeIf { it > 0 } ?: resources.displayMetrics.density.toDouble()
      (top * dpr / resources.displayMetrics.density).roundToInt()
    }.getOrNull()
  }

  // ── insets padding (driven by the navigator fragment) ──────────────────────

  internal fun applyPaddings(top: Int, bottom: Int) {
    setPadding(0, top, 0, bottom)
  }

  private fun setOnClickListenerWithPoint(action: (View, PointF) -> Unit) {
    var point = PointF()
    setOnTouchListener { _, event ->
      if (event.action == MotionEvent.ACTION_DOWN) {
        point = PointF(event.x, event.y)
      }
      false
    }
    setOnClickListener {
      action(it, point)
    }
  }

  private fun WebView.onContentReady(action: () -> Unit) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) {
      WebViewCompat.postVisualStateCallback(this, 0) {
        action()
      }
    } else {
      // On older devices, there's no reliable way to guarantee the page is fully laid
      // out. As a workaround, run a dummy JavaScript, then wait for a short delay
      // before assuming it's ready.
      evaluateJavascript("true") {
        postDelayed(500, action)
      }
    }
  }
}