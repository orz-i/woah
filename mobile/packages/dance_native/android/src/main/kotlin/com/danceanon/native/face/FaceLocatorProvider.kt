package com.danceanon.native.face

import android.content.Context

/**
 * Explicit opt-in boundary. No current preview/export/API caller enables this.
 */
object FaceLocatorProvider {
    fun createOrNull(
        context: Context,
        enabled: Boolean = false
    ): FaceLocator? {
        if (!enabled) return null
        return MediaPipeFaceLocator(context)
    }
}
