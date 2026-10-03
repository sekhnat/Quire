/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.


package com.quire.reader.navigator.extensions

/**
 * Unwraps the two given arguments and pass them to the [closure] if they are not null.
 */
internal inline fun <A, B, R> let(a: A?, b: B?, closure: (A, B) -> R?): R? =
    if (a == null || b == null) {
        null
    } else {
        closure(a, b)
    }
