package com.quire.reader.reader

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Lets vertical scrolling run on from one chapter into the next.
 *
 * Readium's scroll mode treats each chapter (spine item) as its own scrolling page and needs a sideways swipe
 * to change chapter. This layout watches the touch stream without consuming it: when a finger keeps scrolling
 * past the end (or start) of the visible chapter, it reports [onEdgeScroll] once so the reader can move to the
 * neighbouring chapter. Chapters then no longer need any special gesture, and the reader just keeps scrolling.
 */
class EdgeScrollLayout(context: Context) : FrameLayout(context) {
  /** Called with `true` when scrolling past the end of the chapter, `false` past the start. */
  var onEdgeScroll: ((forward: Boolean) -> Unit)? = null

  /** Only active in scroll mode. */
  var continuous: Boolean = false

  private val triggerPx = 48 * resources.displayMetrics.density
  private var lastY = 0f
  private var pulled = 0f
  private var pulledForward: Boolean? = null
  private var fired = false

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    if (continuous && onEdgeScroll != null) track(ev)
    return super.dispatchTouchEvent(ev)
  }

  private fun track(ev: MotionEvent) {
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> { lastY = ev.y; pulled = 0f; pulledForward = null; fired = false }
      MotionEvent.ACTION_MOVE -> {
        val dy = ev.y - lastY
        lastY = ev.y
        if (fired || dy == 0f) return
        val web = visibleWebView() ?: return
        val forward = dy < 0 // finger moving up scrolls the page down, towards the next chapter
        val atEdge = if (forward) !web.canScrollVertically(1) else !web.canScrollVertically(-1)
        // Distance only builds up while the page is pinned at the edge and the finger keeps going the same way.
        pulled = if (atEdge && (pulledForward == null || pulledForward == forward)) pulled + kotlin.math.abs(dy) else 0f
        pulledForward = if (pulled > 0f) forward else null
        if (pulled >= triggerPx) {
          fired = true
          onEdgeScroll?.invoke(forward)
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pulled = 0f; pulledForward = null; fired = false }
    }
  }

  /** The chapter WebView the reader is looking at (Readium keeps neighbours loaded off-screen). */
  private fun visibleWebView(): WebView? {
    val mine = Rect().also { getGlobalVisibleRect(it) }
    var best: WebView? = null
    var bestArea = 0L
    fun visit(v: View) {
      if (v is WebView && v.isShown) {
        val r = Rect()
        if (v.getGlobalVisibleRect(r) && r.intersect(mine)) {
          val area = r.width().toLong() * r.height()
          if (area > bestArea) { best = v; bestArea = area }
        }
      }
      if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
    }
    visit(this)
    return best
  }
}
