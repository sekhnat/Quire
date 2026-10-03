/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.


package com.quire.reader.navigator.extensions

import android.graphics.RectF
import org.json.JSONObject

/**
 * Parses a [RectF] from its JSON representation.
 */
internal fun JSONObject.optRectF(name: String): RectF? =
    optJSONObject(name)?.let { json ->
        val left = json.optDouble("left").toFloat()
        val top = json.optDouble("top").toFloat()
        val right = json.optDouble("right").toFloat()
        val bottom = json.optDouble("bottom").toFloat()
        RectF(left, top, right, bottom)
    }
