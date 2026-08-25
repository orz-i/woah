package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection

enum class TrackState {
    NEW,
    ACTIVE,
    LOST,
    REMOVED
}

data class TrackedPerson(
    val id: Int,
    var bbox: FloatRect,
    var mask: NativeMask?,
    var confidence: Float,
    var missedFrames: Int = 0,
    var age: Int = 1,
    var state: TrackState = TrackState.NEW
)

interface PersonTracker {
    fun initialize(detections: List<PersonDetection>): List<TrackedPerson>
    fun update(detections: List<PersonDetection>, timestampUs: Long): List<TrackedPerson>
    fun predict(timestampUs: Long): List<TrackedPerson>
    fun reset()
}
