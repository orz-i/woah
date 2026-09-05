package com.danceanon.native.face

import android.content.Context

/** Explicit opt-in boundary for FACE_ONLY positional detection. */
object FaceLocatorProvider {
    fun createOrNull(
        context: Context,
        enabled: Boolean = false
    ): FaceLocator? {
        if (!enabled) return null
        return ParallelMediaPipeFaceLocator(context)
    }
}
