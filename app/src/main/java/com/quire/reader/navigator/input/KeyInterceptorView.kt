package com.quire.reader.navigator.input

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.shared.ExperimentalReadiumApi

//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.

/**
 * Utility view to intercept keyboard events and forward them to a [VisualNavigator.Listener].
 */
@SuppressLint("ViewConstructor")
@OptIn(ExperimentalReadiumApi::class)
internal class KeyInterceptorView(
    view: View,
    private val listener: InputListener?,
) : FrameLayout(view.context) {

    init {
        addView(view)

        isFocusable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        event
            ?.let { KeyEvent(KeyEvent.Type.Up, it) }
            ?.let { listener?.onKey(it) }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        event
            ?.let { KeyEvent(KeyEvent.Type.Down, it) }
            ?.let { listener?.onKey(it) }
        return super.onKeyDown(keyCode, event)
    }
}