/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.

package com.quire.reader.navigator.extensions

import java.text.NumberFormat
import org.readium.r2.shared.InternalReadiumApi

@InternalReadiumApi
public fun Number.format(maximumFractionDigits: Int, percent: Boolean = false): String {
    val format = if (percent) NumberFormat.getPercentInstance() else NumberFormat.getNumberInstance()
    format.maximumFractionDigits = maximumFractionDigits
    return format.format(this)
}