/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.


package com.quire.reader.navigator.epub.extensions

import org.readium.r2.shared.util.Language

internal val Language.isRtl: Boolean get() {
    val c = code.lowercase()
    return c == "ar" ||
        c == "fa" ||
        c == "he" ||
        c == "zh-hant" ||
        c == "zh-tw"
}

internal val Language.isCjk: Boolean get() {
    val c = code.lowercase()
    return c == "ja" ||
        c == "ko" ||
        removeRegion().code.lowercase() == "zh"
}
