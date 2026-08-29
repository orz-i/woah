package com.danceanon.native.privacy

import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.TrackedPerson

enum class PersonPrivacyMode {
    NONE,
    FACE_ONLY,
    FULL_BODY
}

data class PrivacyPolicyAdaptation(
    val persons: List<TrackedPerson>,
    val selectedPersonIds: Set<Int>,
    val faceOnlyTrackIds: Set<Int>,
    val fullBodyTrackIds: Set<Int>,
    val escalatedFullBodyTrackIds: Set<Int>,
    val unresolvedSelectedTrackIds: Set<Int>
) {
    val readyForRender: Boolean get() = unresolvedSelectedTrackIds.isEmpty()
}

/**
 * Adapts the existing TrackedPerson list for the existing PrivacyOcclusionResolver.
 *
 * NONE persons retain their original full-person masks so they can still act as
 * foreground occluders. FULL_BODY persons keep the current mask. FACE_ONLY persons
 * keep the same YOLO identity/bbox/state but substitute a face privacy mask.
 *
 * A missing or incompatible FACE_ONLY mask never becomes transparent: if the
 * tracked full-body mask exists, privacy escalates to FULL_BODY for that frame.
 * If neither mask exists, the track is explicitly marked unresolved so a future
 * runtime caller can fail the frame/export instead of silently exposing a face.
 */
object PersonPrivacyPolicyAdapter {
    fun adapt(
        persons: List<TrackedPerson>,
        modeByTrackId: Map<Int, PersonPrivacyMode>,
        faceMaskByTrackId: Map<Int, NativeMask>
    ): PrivacyPolicyAdaptation {
        val selected = linkedSetOf<Int>()
        val faceOnly = linkedSetOf<Int>()
        val fullBody = linkedSetOf<Int>()
        val escalated = linkedSetOf<Int>()
        val unresolved = linkedSetOf<Int>()

        val adapted = persons.map { person ->
            when (modeByTrackId[person.id] ?: PersonPrivacyMode.NONE) {
                PersonPrivacyMode.NONE -> person

                PersonPrivacyMode.FULL_BODY -> {
                    selected += person.id
                    fullBody += person.id
                    if (person.mask == null) unresolved += person.id
                    person
                }

                PersonPrivacyMode.FACE_ONLY -> {
                    selected += person.id
                    faceOnly += person.id
                    val faceMask = faceMaskByTrackId[person.id]
                    val referenceMask = person.mask ?: persons.firstNotNullOfOrNull { it.mask }
                    val faceUsable = faceMask != null &&
                        (referenceMask == null || maskContractsMatch(referenceMask, faceMask))

                    when {
                        faceUsable -> person.copy(mask = faceMask)
                        person.mask != null -> {
                            escalated += person.id
                            fullBody += person.id
                            person
                        }
                        else -> {
                            unresolved += person.id
                            person.copy(mask = null)
                        }
                    }
                }
            }
        }

        return PrivacyPolicyAdaptation(
            persons = adapted,
            selectedPersonIds = selected,
            faceOnlyTrackIds = faceOnly,
            fullBodyTrackIds = fullBody,
            escalatedFullBodyTrackIds = escalated,
            unresolvedSelectedTrackIds = unresolved
        )
    }

    fun maskContractsMatch(a: NativeMask, b: NativeMask): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        if (a.originalWidth != b.originalWidth || a.originalHeight != b.originalHeight) return false
        if (a.samplingRect != b.samplingRect) return false

        val am = a.mapper
        val bm = b.mapper
        if (am != null && bm != null) {
            if (am.srcWidth != bm.srcWidth || am.srcHeight != bm.srcHeight) return false
            if (am.modelInputSize != bm.modelInputSize || am.protoSize != bm.protoSize) return false
        }
        return true
    }
}
