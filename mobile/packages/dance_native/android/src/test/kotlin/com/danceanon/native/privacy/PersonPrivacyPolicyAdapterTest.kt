package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PersonPrivacyPolicyAdapterTest {
    @Test
    fun `face only substitutes privacy mask but preserves yolo identity geometry and state`() {
        val body = mask(10, 10, 1..7, 1..8)
        val face = mask(10, 10, 3..5, 1..3)
        val original = person(11, body, TrackState.OCCLUDED).copy(
            missedFrames = 2,
            framesSinceLastObservation = 1,
            age = 17,
            occludedByTrackIds = setOf(22),
            observedThisFrame = false,
            footY = 88f
        )

        val adapted = PersonPrivacyPolicyAdapter.adapt(
            persons = listOf(original),
            modeByTrackId = mapOf(11 to PersonPrivacyMode.FACE_ONLY),
            faceMaskByTrackId = mapOf(11 to face)
        )

        val output = adapted.persons.single()
        assertTrue(adapted.readyForRender)
        assertEquals(setOf(11), adapted.selectedPersonIds)
        assertEquals(setOf(11), adapted.faceOnlyTrackIds)
        assertTrue(adapted.escalatedFullBodyTrackIds.isEmpty())
        assertTrue(output.mask === face)
        assertEquals(original.id, output.id)
        assertEquals(original.bbox, output.bbox)
        assertEquals(original.state, output.state)
        assertEquals(original.occludedByTrackIds, output.occludedByTrackIds)
        assertEquals(original.framesSinceLastObservation, output.framesSinceLastObservation)
        assertEquals(original.footY, output.footY)
    }

    @Test
    fun `face only missing detector mask escalates to full body instead of exposing face`() {
        val body = mask(10, 10, 1..8, 1..8)
        val target = person(3, body)

        val adapted = PersonPrivacyPolicyAdapter.adapt(
            persons = listOf(target),
            modeByTrackId = mapOf(3 to PersonPrivacyMode.FACE_ONLY),
            faceMaskByTrackId = emptyMap()
        )

        assertTrue(adapted.readyForRender)
        assertEquals(setOf(3), adapted.escalatedFullBodyTrackIds)
        assertEquals(setOf(3), adapted.fullBodyTrackIds)
        assertTrue(adapted.persons.single().mask === body)
    }

    @Test
    fun `incompatible face mask escalates to full body`() {
        val body = mask(10, 10, 1..8, 1..8)
        val incompatible = mask(8, 8, 1..3, 1..3)
        val target = person(4, body)

        val adapted = PersonPrivacyPolicyAdapter.adapt(
            persons = listOf(target),
            modeByTrackId = mapOf(4 to PersonPrivacyMode.FACE_ONLY),
            faceMaskByTrackId = mapOf(4 to incompatible)
        )

        assertTrue(adapted.readyForRender)
        assertEquals(setOf(4), adapted.escalatedFullBodyTrackIds)
        assertTrue(adapted.persons.single().mask === body)
    }

    @Test
    fun `selected track with no face or body mask is explicitly unresolved`() {
        val target = person(5, null)
        val adapted = PersonPrivacyPolicyAdapter.adapt(
            persons = listOf(target),
            modeByTrackId = mapOf(5 to PersonPrivacyMode.FACE_ONLY),
            faceMaskByTrackId = emptyMap()
        )

        assertFalse(adapted.readyForRender)
        assertEquals(setOf(5), adapted.unresolvedSelectedTrackIds)
        assertEquals(setOf(5), adapted.selectedPersonIds)
    }

    @Test
    fun `none persons retain body masks for occlusion evidence without becoming selected`() {
        val targetBody = mask(10, 10, 1..8, 1..8)
        val targetFace = mask(10, 10, 2..4, 1..3)
        val unselectedBody = mask(10, 10, 4..7, 1..8)
        val target = person(1, targetBody)
        val unselected = person(2, unselectedBody)

        val adapted = PersonPrivacyPolicyAdapter.adapt(
            persons = listOf(target, unselected),
            modeByTrackId = mapOf(1 to PersonPrivacyMode.FACE_ONLY, 2 to PersonPrivacyMode.NONE),
            faceMaskByTrackId = mapOf(1 to targetFace)
        )

        assertEquals(setOf(1), adapted.selectedPersonIds)
        assertTrue(adapted.persons.first { it.id == 2 }.mask === unselectedBody)
        assertTrue(adapted.persons.first { it.id == 1 }.mask === targetFace)
    }

    @Test
    fun `mixed face and full body policies reuse existing resolver union`() {
        val faceTargetOriginal = mask(10, 10, 1..4, 1..8)
        val faceOnly = mask(10, 10, 2..3, 1..2)
        val fullBody = mask(10, 10, 7..8, 5..8)
        val unselected = mask(10, 10, 4..5, 4..7)

        val persons = listOf(
            person(1, faceTargetOriginal),
            person(2, fullBody),
            person(3, unselected)
        )
        val adapted = PersonPrivacyPolicyAdapter.adapt(
            persons = persons,
            modeByTrackId = mapOf(
                1 to PersonPrivacyMode.FACE_ONLY,
                2 to PersonPrivacyMode.FULL_BODY,
                3 to PersonPrivacyMode.NONE
            ),
            faceMaskByTrackId = mapOf(1 to faceOnly)
        )
        assertTrue(adapted.readyForRender)

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = adapted.persons,
            selectedPersonIds = adapted.selectedPersonIds,
            applyDilationToPrivacyTargets = false
        )
        val privacy = assertNotNull(resolved.privacyMask)

        assertEquals(255, pixel(privacy, 2, 1), "FACE_ONLY center must be private")
        assertEquals(0, pixel(privacy, 1, 7), "FACE_ONLY lower body must remain clear")
        assertEquals(255, pixel(privacy, 8, 7), "FULL_BODY target must remain private")
        assertEquals(0, pixel(privacy, 5, 6), "NONE person alone must not become privacy")
    }

    private fun person(
        id: Int,
        mask: NativeMask?,
        state: TrackState = TrackState.ACTIVE
    ) = TrackedPerson(
        id = id,
        bbox = FloatRect(10f, 10f, 90f, 90f),
        mask = mask,
        confidence = 0.9f,
        state = state,
        observedThisFrame = true,
        footY = 90f
    )

    private fun mask(
        width: Int,
        height: Int,
        xRange: IntRange,
        yRange: IntRange
    ): NativeMask {
        val buffer = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        repeat(width * height) { buffer.put(0) }
        for (y in yRange) {
            for (x in xRange) {
                if (x in 0 until width && y in 0 until height) {
                    buffer.put(y * width + x, 255.toByte())
                }
            }
        }
        buffer.rewind()
        return NativeMask(
            width = width,
            height = height,
            buffer = buffer,
            originalWidth = 100,
            originalHeight = 100,
            mapper = null
        )
    }

    private fun pixel(mask: NativeMask, x: Int, y: Int): Int =
        mask.buffer.get(y * mask.width + x).toInt() and 0xFF
}
