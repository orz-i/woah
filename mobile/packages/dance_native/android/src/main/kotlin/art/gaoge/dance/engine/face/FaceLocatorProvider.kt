package art.gaoge.dance.engine.face

import android.content.Context

/** Explicit opt-in boundary for FACE_ONLY positional detection. */
object FaceLocatorProvider {
    fun createOrNull(
        context: Context,
        enabled: Boolean = false,
        preferGpu: Boolean = true
    ): FaceLocator? {
        if (!enabled) return null
        return ParallelGridFaceLocator(context, preferGpu)
    }
}
