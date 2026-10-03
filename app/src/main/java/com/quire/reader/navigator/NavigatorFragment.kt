/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */
//
// Vendored from the Readium Kotlin Toolkit 3.3.0 (readium-navigator module, official
// sources jar) into Quire to implement continuous vertical scrolling. The original
// BSD-style license header above applies to this file and its modifications.


package com.quire.reader.navigator

import android.os.Bundle
import androidx.fragment.app.Fragment
import org.readium.r2.navigator.Navigator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isRestricted

public abstract class NavigatorFragment internal constructor(
    protected val publication: Publication,
) : Fragment(), Navigator {

    override fun onCreate(savedInstanceState: Bundle?) {
        require(!publication.isRestricted) { "The provided publication is restricted. Check that any DRM was properly unlocked using a Content Protection." }

        super.onCreate(savedInstanceState)
    }
}
