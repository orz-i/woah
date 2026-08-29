package com.danceanon.native.privacy

/**
 * Converts request-level ID sets into the internal tri-state policy.
 *
 * Existing selectedPersonIds retain their historical FULL_BODY meaning. A new
 * face-only list can add FACE_ONLY targets without changing legacy callers.
 * If an ID appears in both lists, FULL_BODY wins, matching desktop behavior.
 */
object PersonPrivacyModeResolver {
    fun resolve(
        fullBodyPersonIds: Collection<Int>,
        faceOnlyPersonIds: Collection<Int>?
    ): Map<Int, PersonPrivacyMode> {
        val fullBody = fullBodyPersonIds.toSet()
        val faceOnly = faceOnlyPersonIds.orEmpty().toSet()
        if (fullBody.isEmpty() && faceOnly.isEmpty()) return emptyMap()

        val modes = linkedMapOf<Int, PersonPrivacyMode>()
        faceOnly.sorted().forEach { id ->
            modes[id] = PersonPrivacyMode.FACE_ONLY
        }
        fullBody.sorted().forEach { id ->
            modes[id] = PersonPrivacyMode.FULL_BODY
        }
        return modes
    }
}
