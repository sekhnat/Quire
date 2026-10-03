/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.


package com.quire.reader.navigator.epub

import org.readium.r2.navigator.preferences.PreferencesFilter
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Suggested filter to keep only shared [EpubPreferences].
 */
public object EpubSharedPreferencesFilter : PreferencesFilter<EpubPreferences> {

    @OptIn(ExperimentalReadiumApi::class)
    override fun filter(preferences: EpubPreferences): EpubPreferences =
        preferences.copy(
            readingProgression = null,
            language = null,
            spread = null,
            verticalText = null
        )
}

/**
 * Suggested filter to keep only publication-specific [EpubPreferences].
 */
public object EpubPublicationPreferencesFilter : PreferencesFilter<EpubPreferences> {

    @OptIn(ExperimentalReadiumApi::class)
    override fun filter(preferences: EpubPreferences): EpubPreferences =
        EpubPreferences(
            readingProgression = preferences.readingProgression,
            language = preferences.language,
            spread = preferences.spread,
            verticalText = preferences.verticalText
        )
}
