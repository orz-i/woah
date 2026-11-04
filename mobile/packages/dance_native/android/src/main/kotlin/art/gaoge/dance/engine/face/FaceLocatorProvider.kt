package art.gaoge.dance.engine.face

import android.content.Context
import com.google.mediapipe.tasks.core.Delegate

/** Explicit opt-in boundary for FACE_ONLY positional detection. */
object FaceLocatorProvider {
    fun createOrNull(
        context: Context,
        enabled: Boolean = false,
        preferGpu: Boolean = true
    ): FaceLocator? {
        if (!enabled) return null
        return ParallelMediaPipeFaceLocator(
            context, preferredDelegate = if (preferGpu) Delegate.GPU else Delegate.CPU
        )
    }
}
