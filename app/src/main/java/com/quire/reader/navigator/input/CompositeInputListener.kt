/*
 * Module: r2-navigator-kotlin
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.

package com.quire.reader.navigator.input

import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Aggregates several [InputListener] implementations into a single one.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class CompositeInputListener : InputListener {

    private val listeners = mutableListOf<InputListener>()

    fun add(listener: InputListener) {
        listeners.add(listener)
    }

    fun remove(listener: InputListener) {
        listeners.remove(listener)
    }

    override fun onTap(event: TapEvent): Boolean =
        listeners.any { it.onTap(event) }

    override fun onDrag(event: DragEvent): Boolean =
        listeners.any { it.onDrag(event) }

    override fun onKey(event: KeyEvent): Boolean =
        listeners.any { it.onKey(event) }
}