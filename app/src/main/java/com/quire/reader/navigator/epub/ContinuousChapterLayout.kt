/*
 * Copyright 2024 Quire. Continuous-scroll chapter stack (written for Quire's vendored
 * Readium navigator fork; BSD-style license headers of copied code preserved there).
 */
//
// The scroll-mode container replacing Readium's horizontal resource pager.
//
// It presents the reading order as one virtual column. Every chapter keeps its own
// viewport-sized WebView (Readium's offscreen-pager memory profile), but the container
// owns the whole scroll gesture: while the viewport top lies inside a chapter's span it
// scrolls that chapter's WebView internally (as Readium's scroll mode does); at a
// chapter edge it keeps going by sliding the window across, synchronising the next
// chapter's WebView at the same time. One `OverScroller` carries drag and fling
// momentum through any number of chapter boundaries, so scrolling is continuous from
// the first to the last page.
//
// Chapter offsets are estimated from Readium's `positions` (≈1 per 1,024 characters)
// until a chapter has loaded and reported its real content height; when that happens
// the geometry is rebuilt and the position re-anchored on the same visible content.

package com.quire.reader.navigator.epub

import android.content.Context
import com.quire.reader.navigator.ChapterWebView
import android.util.Log
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EdgeEffect
import android.widget.OverScroller
import androidx.collection.SparseArrayCompat
import androidx.collection.forEach
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class ContinuousChapterLayout(
  context: Context,
  private val host: Host,
) : ViewGroup(context) {

  /** What the stack needs from the navigator fragment. */
  internal interface Host {
    /** Number of chapters (spine items) in the reading order. */
    val pageCount: Int

    /** Number of Readium `positions` for a chapter, used for height estimates. */
    fun positionCountFor(index: Int): Int

    /** Creates the configured chapter view for [index] (starts loading immediately). */
    fun createChapterView(index: Int): ChapterWebView

    /** Destroys a detached chapter view and clears the fragment's references to it. */
    fun disposeChapterView(view: ChapterWebView)
  }

  /** Fires on every scroll step and measurement change; the fragment refreshes locators. */
  var onScrollChanged: (() -> Unit)? = null

  /** Fires when a chapter finished loading; the fragment resolves pending jumps. */
  var onChapterAvailable: ((index: Int) -> Unit)? = null

  /** Window radius of chapters kept alive around the visible one. */
  private val windowHalf = 1

  // ── chapter views ─────────────────────────────────────────────────────────

  private val chapters = SparseArrayCompat<ChapterWebView>()

  /** Measured content height per chapter; 0 while unknown. */
  private var heights = IntArray(0)

  /** Absolute offset of each chapter in the virtual column, synced with [heights]. */
  private var tops = IntArray(0)

  /** Total rendered height of the (measured + estimated) column. */
  private var totalHeight = 0

  /** True when [tops]/[totalHeight] no longer match [heights]. */
  private var geometryDirty = true

  /** Running estimate: rendered px per Readium position. Negative until the first sample. */
  private var pxPerPosition = -1f

  /** The virtual scroll position: where the viewport top sits in the column. */
  private var y = 0

  private fun ensureGeometry() {
    val count = host.pageCount
    if (heights.size != count) {
      heights = IntArray(count)
      geometryDirty = true
    }
    if (tops.size != count) {
      tops = IntArray(count)
      geometryDirty = true
    }
  }

  /** Height of chapter [index]: its measured content height, or a position-based estimate. */
  fun chapterHeight(index: Int): Int {
    ensureGeometry()
    if (index !in heights.indices) return 0
    heights[index].takeIf { it > 0 }?.let { return it }
    return (host.positionCountFor(index).coerceAtLeast(1) * pxPerPosition.coerceAtLeast(1f))
      .roundToInt()
      .coerceAtLeast(viewportHeight())
  }

  /** Absolute top of chapter [index] in the virtual column. */
  fun chapterTop(index: Int): Int {
    ensureGeometry()
    if (geometryDirty) recomputeTops()
    return tops.getOrNull(index) ?: 0
  }

  private fun recomputeTops() {
    geometryDirty = false
    var running = 0
    for (i in heights.indices) {
      tops[i] = running
      running += chapterHeight(i)
    }
    totalHeight = running
  }

  private fun viewportHeight(): Int = height.coerceAtLeast(1)

  /** Total scrollable range: the highest position of the viewport top. */
  fun maxScrollY(): Int {
    ensureGeometry()
    if (geometryDirty) recomputeTops()
    return (totalHeight - viewportHeight()).coerceAtLeast(0)
  }

  /** The chapter whose span contains the viewport top, or 0 before initialization. */
  fun focusChapter(): Int {
    ensureGeometry()
    if (geometryDirty) recomputeTops()
    val last = host.pageCount - 1
    if (tops.isEmpty() || last < 0) return 0
    var low = 0
    var high = min(tops.lastIndex, last)
    while (low < high) {
      val mid = (low + high + 1) / 2
      if (tops[mid] <= y) low = mid else high = mid - 1
    }
    return low
  }

  /** Progression of the viewport top inside the focus chapter, following Readium's
   * scroll-mode convention (scroll offset / content height). */
  fun focusProgression(): Double {
    val index = focusChapter()
    val height = chapterHeight(index)
    if (height <= 0) return 0.0
    val within = (y - chapterTop(index)).coerceAtLeast(0)
    return (within.toDouble() / height).coerceIn(0.0, 1.0)
  }

  /** Attached chapter view for [index], or null when not in the window. */
  fun chapterAt(index: Int): ChapterWebView? = chapters[index]

  /** Iterates over the currently attached chapter views. */
  fun forEachChapter(action: (ChapterWebView) -> Unit) {
    chapters.forEach { _, chapter -> action(chapter) }
  }

  // ── windowing ─────────────────────────────────────────────────────────────

  /** Deferred first window update: never create WebViews inside a measure pass
   * (the fragment is added during Compose's `AndroidView` update). */
  private var pendingWindowIndex = -2
  private var windowUpdateScheduled = false

  /** Attaches a window of chapters around [index] and detaches the rest. */
  fun updateWindow(index: Int = focusChapter()) {
    if (!isLaidOut && !isInLayout) {
      pendingWindowIndex = index
      if (!windowUpdateScheduled) {
        windowUpdateScheduled = true
        post {
          windowUpdateScheduled = false
          val queued = pendingWindowIndex
          pendingWindowIndex = -2
          updateWindow(queued)
        }
      }
      return
    }
    val count = host.pageCount
    if (count == 0) return
    val first = (index - windowHalf).coerceAtLeast(0)
    val last = (index + windowHalf).coerceAtMost(count - 1)

    for (i in first..last) {
      if (chapters[i] == null) {
        val chapter = host.createChapterView(i)
        chapter.onContentHeightChanged = { chapterMeasured(i, chapter) }
        addView(chapter, LayoutParams(LayoutParams.MATCH_PARENT, viewportHeight()))
        chapters.put(i, chapter)
      }
    }

    val toDetach = mutableListOf<Pair<Int, ChapterWebView>>()
    chapters.forEach { i, chapter ->
      if (i !in first..last) toDetach.add(i to chapter)
    }
    for ((i, chapter) in toDetach) {
      chapters.remove(i)
      chapter.onContentHeightChanged = null
      removeView(chapter)
      host.disposeChapterView(chapter)
    }
    syncTo(y)
    invalidate()
  }

  private fun chapterMeasured(index: Int, chapter: ChapterWebView) {
    val height = chapter.contentHeight
    if (height <= 0 || heights.getOrNull(index) == height) {
      onChapterAvailable?.invoke(index)
      return
    }

    // Capture the anchor (chapter + progression) from the OLD geometry before the
    // change, then rebuild and scroll back to the same content — but only when the
    // changed chapter sits at or before the anchor; a change entirely below the
    // viewport doesn't move the visible content, and re-anchoring would sweep the
    // window away from any jump in flight.
    val (anchor, progression) = contentAnchorAt(y)
    val affectsAnchor = index <= anchor
    heights[index] = height
    observeHeightEstimate(index, height)
    geometryDirty = true
    recomputeTops()

    if (affectsAnchor) {
      val wasAnimating = !scroller.isFinished
      val velocity = if (wasAnimating) scroller.currVelocity else 0f
      scroller.abortAnimation()
      val top = chapterTop(anchor)
      val target = (top + progression * chapterHeight(anchor)).roundToInt().coerceIn(0, maxScrollY())
      if (target != y) {
        jumpToY(target, sweepWindow = false)
        if (abs(velocity) > 800) {
          flingScroll(-velocity.roundToInt())
        }
      } else {
        syncTo(y)
        onScrollChanged?.invoke()
      }
    } else {
      syncTo(y)
      onScrollChanged?.invoke()
    }
    onChapterAvailable?.invoke(index)
  }

  /** Feeds the per-position estimate with a freshly measured chapter. */
  private fun observeHeightEstimate(index: Int, height: Int) {
    val positions = host.positionCountFor(index).coerceAtLeast(1)
    val sample = height.toFloat() / positions
    pxPerPosition = if (pxPerPosition <= 0) sample else (pxPerPosition * 2 + sample) / 3
  }

  /**
   * The (chapter, progression) anchor at [atY] in the current geometry. The anchor is
   * the newest *measured* chapter whose span covers or precedes [atY]; its geometry is
   * real, so the progression survives geometry rebuilds exactly. The progression is
   * intentionally unclamped: when [atY] falls in an estimated span below the anchor it
   * is carried as an extrapolation and re-grounded once those chapters measure.
   */
  private fun contentAnchorAt(atY: Int): Pair<Int, Double> {
    ensureGeometry()
    if (geometryDirty) recomputeTops()
    var measured = -1
    for (i in tops.indices) {
      if (tops[i] <= atY && heights[i] > 0) measured = i
    }
    if (measured < 0) {
      val focus = focusChapter()
      val top = chapterTop(focus)
      val h = max(chapterHeight(focus), 1)
      return focus to ((atY - top).coerceAtLeast(0).toDouble() / h)
    }
    return measured to ((atY - tops[measured]).toDouble() / max(heights[measured], 1))
  }

  // ── scrolling (the container owns the whole gesture) ──────────────────────

  private val scroller = OverScroller(context)
  private var velocityTracker: VelocityTracker? = null
  private var touchSlop = 0
  private var minimumVelocity = 0
  private var maximumVelocity = 0
  private var isDragging = false
  private var activePointerId = -1
  private val drag = DragTracker()
  private var downTouchX = 0f
  private var downTouchY = 0f

  private val topEdge = EdgeEffect(context)
  private val bottomEdge = EdgeEffect(context)

  /** True while a programmatic internal scroll is being applied by [syncScroll]. */
  private var isSyncingScroll = false

  init {
    val configuration = ViewConfiguration.get(context)
    touchSlop = configuration.scaledTouchSlop
    minimumVelocity = configuration.scaledMinimumFlingVelocity
    maximumVelocity = configuration.scaledMaximumFlingVelocity
    descendantFocusability = FOCUS_AFTER_DESCENDANTS
    isVerticalScrollBarEnabled = false
    overScrollMode = OVER_SCROLL_NEVER
  }

  /**
   * Positions and synchronises the attached chapters for the viewport top at [ny]:
   * each chapter's internal scroll is `ny − top(chapter)` clamped to what it can
   * scroll, and the view is translated so the content window abuts the column.
   */
  private fun syncTo(ny: Int) {
    y = ny
    val viewport = viewportHeight()
    chapters.forEach { i, chapter ->
      val within = (y - chapterTop(i)).coerceIn(0, max(chapterHeight(i) - viewport, 0))
      chapter.translationY = (chapterTop(i) + within - y).toFloat()
      syncScroll(chapter, within)
    }
  }

  /** Sets a chapter's internal scroll position as part of the container's scroll. */
  private fun syncScroll(chapter: ChapterWebView, within: Int) {
    if (chapter.webView.scrollY == within) return
    isSyncingScroll = true
    try {
      chapter.webView.scrollTo(0, within)
    } finally {
      isSyncingScroll = false
    }
  }

  /** Scrolls the column so the viewport top is at [ny]; fires [onScrollChanged]. When
   * [sweepWindow] is false (geometric re-anchors) the attached window is left untouched so a
   * pending jump's target chapter is not detached before it can load. */
  fun jumpToY(ny: Int, sweepWindow: Boolean = true) {
    scroller.abortAnimation()
    val clamped = ny.coerceIn(0, maxScrollY())
    syncTo(clamped)
    if (sweepWindow) updateWindow()
    onScrollChanged?.invoke()
  }

  /** Flings from the current position; [velocityY] in touch convention
   * (positive = finger moved down = scroll backwards). */
  fun flingScroll(velocityY: Int) {
    scroller.abortAnimation()
    scroller.fling(0, y, 0, -velocityY, 0, 0, 0, maxScrollY())
    if (y != scroller.finalY) {
      ViewCompat.postInvalidateOnAnimation(this)
    }
  }

  /** Stops an in-flight fling: the standard scroll-container contract for a finger
   * landing mid-momentum (ScrollView, RecyclerView, WebView all do this). Without it
   * a tap or hold cannot freeze the column, and a drag started mid-fling fights
   * computeScroll, which keeps snapping the position back onto the scroller's
   * trajectory. No position sync is needed — computeScroll applies currY to y in the
   * same frame, so aborting freezes the column exactly where it is. */
  private fun stopFling() {
    if (!scroller.isFinished) {
      scroller.abortAnimation()
    }
  }

  override fun computeScroll() {
    if (scroller.computeScrollOffset()) {
      syncTo(scroller.currY)
      updateWindow()
      onScrollChanged?.invoke()
      ViewCompat.postInvalidateOnAnimation(this)
    }
    if (!topEdge.isFinished || !bottomEdge.isFinished) {
      ViewCompat.postInvalidateOnAnimation(this)
    }
  }

  // ── draw (book-edge glows) ────────────────────────────────────────────────

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (!topEdge.isFinished) {
      canvas.save().also { count ->
        canvas.translate(0f, 70f)
        topEdge.setSize(width, height)
        topEdge.draw(canvas)
        canvas.restoreToCount(count)
      }
    }
    if (!bottomEdge.isFinished) {
      canvas.save().also { count ->
        canvas.rotate(180f, width / 2f, height / 2f)
        canvas.translate(0f, 70f)
        bottomEdge.setSize(width, height)
        bottomEdge.draw(canvas)
        canvas.restoreToCount(count)
      }
    }
  }

  private fun releaseGlows() {
    val wasActive = !topEdge.isFinished || !bottomEdge.isFinished
    topEdge.onRelease()
    bottomEdge.onRelease()
    if (wasActive || !topEdge.isFinished || !bottomEdge.isFinished) {
      ViewCompat.postInvalidateOnAnimation(this)
    }
  }

  // ── touch ─────────────────────────────────────────────────────────────────

  override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        stopFling()
        activePointerId = event.getPointerId(0)
        downTouchX = event.x
        downTouchY = event.y
        isDragging = false
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
      }

      MotionEvent.ACTION_MOVE -> {
        velocityTracker?.addMovement(event)
        if (isDragging) return true
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return false
        val pointerY = event.getY(pointerIndex)
        val pointerX = event.x
        val dy = pointerY - downTouchY
        val dx = pointerX - downTouchX
        val touched = chapterUnder(pointerX, pointerY)
        if (abs(dy) > touchSlop && abs(dy) > abs(dx) && touched?.webView?.isSelecting != true) {
          // Take over the gesture, first adopting whatever the chapter scrolled
          // natively before this point so the motion is continuous.
          if (touched != null) {
            val top = chapterTop(touched.index)
            y = (top + touched.webView.scrollY).coerceIn(0, maxScrollY())
          }
          drag.start(pointerY)
          isDragging = true
          return true
        }
      }

      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        velocityTracker?.recycle()
        velocityTracker = null
      }
    }
    return isDragging
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        // A gesture no child consumed (bare container while the window churns):
        // own it so the finger still freezes the column and the UP/CANCEL cleanup
        // below runs — an unhandled down means the rest of the gesture would
        // never arrive. Later events bypass onInterceptTouchEvent (no child
        // target) and come straight here.
        stopFling()
        activePointerId = event.getPointerId(0)
        downTouchX = event.x
        downTouchY = event.y
        isDragging = false
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        velocityTracker?.addMovement(event)
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0 || !isDragging) return true
        val delta = drag.step(event.getY(pointerIndex))
        if (delta != 0) {
          // Finger up (positive) scrolls forward through the book.
          dragBy(delta)
        }
      }

      MotionEvent.ACTION_UP -> {
        if (!isDragging) {
          velocityTracker?.recycle()
          velocityTracker = null
          activePointerId = -1
          return true
        }
        velocityTracker?.addMovement(event)
        val tracker = velocityTracker
        if (tracker != null) {
          tracker.computeCurrentVelocity(1000, maximumVelocity.toFloat())
          val velocity = tracker.getYVelocity(activePointerId).roundToInt()
          if (abs(velocity) > minimumVelocity) {
            flingScroll(velocity)
          }
        }
        releaseGlows()
        velocityTracker?.recycle()
        velocityTracker = null
        isDragging = false
        activePointerId = -1
      }

      MotionEvent.ACTION_CANCEL -> {
        releaseGlows()
        velocityTracker?.recycle()
        velocityTracker = null
        isDragging = false
        activePointerId = -1
      }
    }
    return true
  }

  /** Scrolls the column by [delta] (positive = forward), clamping with edge glows. */
  private fun dragBy(delta: Int) {
    val maxY = maxScrollY()
    val target = y + delta
    when {
      target < 0 -> {
        syncTo(0)
        topEdge.onPull(-delta.toFloat() * 0.4f)
      }
      target > maxY -> {
        syncTo(maxY)
        bottomEdge.onPull((target - maxY).toFloat() * 0.4f)
      }
      else -> syncTo(target)
    }
    updateWindow()
    onScrollChanged?.invoke()
    ViewCompat.postInvalidateOnAnimation(this)
  }

  /** The attached chapter whose content covers the given point. */
  fun chapterUnder(px: Float, py: Float): ChapterWebView? {
    val virtual = (py + y).roundToInt()
    var found: ChapterWebView? = null
    chapters.forEach { i, chapter ->
      val top = tops.getOrNull(i) ?: return@forEach
      if (virtual in top until (top + chapterHeight(i))) found = chapter
    }
    return found
  }

  // ── layout ────────────────────────────────────────────────────────────────

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val width = MeasureSpec.getSize(widthMeasureSpec)
    val height = MeasureSpec.getSize(heightMeasureSpec)
    setMeasuredDimension(width, height)
    ensureGeometry()

    chapters.forEach { _, chapter ->
      chapter.measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
      )
    }
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    // All chapter views stack at the same viewport-sized frame and move with
    // translationY as the column scrolls; syncTo also (re)applies translations.
    chapters.forEach { _, chapter ->
      chapter.layout(0, 0, width, height)
    }
    syncTo(y)
  }
}