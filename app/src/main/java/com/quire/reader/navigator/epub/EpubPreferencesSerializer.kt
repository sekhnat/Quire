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

import kotlinx.serialization.json.Json
import org.readium.r2.navigator.preferences.PreferencesSerializer

/**
 * JSON serializer of [EpubPreferences].
 */
public class EpubPreferencesSerializer : PreferencesSerializer<EpubPreferences> {

    override fun serialize(preferences: EpubPreferences): String =
        Json.encodeToString(EpubPreferences.serializer(), preferences)

    override fun deserialize(preferences: String): EpubPreferences =
        Json.decodeFromString(EpubPreferences.serializer(), preferences)
}
