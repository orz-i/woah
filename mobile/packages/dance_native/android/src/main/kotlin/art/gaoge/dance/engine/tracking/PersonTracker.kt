package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.inference.PersonDetection

enum class TrackState {
    NEW,
    ACTIVE,
    OCCLUDED,
    REACQUIRING,
    LOST,
    REMOVED
}

data class TrackedPerson(
    val id: Int,
    var bbox: FloatRect,
    var mask: NativeMask?,
    var confidence: Float,
    var missedFrames: Int = 0,
    var framesSinceLastObservation: Int = 0,
    var age: Int = 1,
    var state: TrackState = TrackState.NEW,
    var occludedByTrackIds: Set<Int> = emptySet(),
    var observedThisFrame: Boolean = (state == TrackState.ACTIVE || state == TrackState.NEW),
    var footY: Float? = null
)

interface PersonTracker {
    fun initialize(detections: List<PersonDetection>): List<TrackedPerson>
    fun update(detections: List<PersonDetection>, timestampUs: Long): List<TrackedPerson>
    fun predict(timestampUs: Long): List<TrackedPerson>
    fun reset()
}
