package art.gaoge.dance.engine.diagnostics

import android.content.Context
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.privacy.FacePrivacyEllipse
import art.gaoge.dance.engine.tracking.TrackedPerson

/** Release builds never open QA trace files. */
internal class PrivacyRegressionCapture private constructor() : AutoCloseable {
    fun record(pts: Long, crop: FloatRect, regions: Map<Int, FacePrivacyEllipse>, persons: List<TrackedPerson>, hero: Int?) = Unit
    override fun close() = Unit
    companion object {
        fun open(context: Context, sourceName: String, outputName: String, width: Int, height: Int,
            sourceWidth: Int, sourceHeight: Int, trackingWidth: Int, trackingHeight: Int): PrivacyRegressionCapture? = null
    }
}
