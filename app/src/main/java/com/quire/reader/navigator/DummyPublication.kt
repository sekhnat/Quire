package com.quire.reader.navigator

//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.

import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication

public object RestorationNotSupportedException : Exception(
    "Restoration of the navigator fragment after process death is not supported. You must pop it from the back stack or finish the host Activity before `onResume`."
)

internal val dummyPublication = Publication(
    Manifest(
        metadata = Metadata(
            identifier = "readium:dummy",
            localizedTitle = LocalizedString("")
        )
    )
)
