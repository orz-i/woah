package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.PersonDetection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackManagerFastBackgroundExitTest {

    private fun det(left: Float, right: Float): PersonDetection = PersonDetection(
        bbox = FloatRect(left, 100f, right, 300f),
        confidence = 0.95f
    )

    @Test
    fun testFastBackgroundCrossingAndExitDoesNotStealForegroundIdentity() {
        val tracker = TrackManager()

        val initial = tracker.initialize(
            listOf(
                det(220f, 320f), // foreground/selected-like stable person
                det(0f, 80f)     // fast background mover
            )
        )
        val selectedId = initial[0].id
        val backgroundId = initial[1].id
        assertNotEquals(selectedId, backgroundId)

        val frames = listOf(
            // Alternate detection ordering while the background person moves
            // rapidly through and past the foreground person.
            listOf(det(100f, 180f), det(220f, 320f)),
            listOf(det(220f, 320f), det(180f, 270f)),
            listOf(det(270f, 360f), det(220f, 320f)),
            listOf(det(220f, 320f), det(390f, 480f)),
            listOf(det(520f, 610f), det(220f, 320f))
        )

        var timestampUs = 33_333L
        for ((frameIndex, detections) in frames.withIndex()) {
            val tracks = tracker.update(detections, timestampUs)
            timestampUs += 33_333L

            if (frameIndex <= 2) {
                assertTrue(
                    tracks.map { it.id }.all { it == selectedId || it == backgroundId },
                    "overlap phase must not mint duplicate identity at frame=$frameIndex; tracks=${tracks.map { Triple(it.id, it.state, it.bbox.centerX) }}"
                )
            }
            val selected = tracks.find { it.id == selectedId }
            assertNotNull(selected)
            assertTrue(
                selected.bbox.centerX in 200f..340f,
                "selected identity must stay on foreground person at frame=$frameIndex; tracks=${tracks.map { it.id to it.bbox.centerX }}"
            )

            // Once the fast background mover is spatially separated, it may be
            // assigned a fresh unselected ID after group ambiguity. Any such
            // fresh identity must remain far from the selected foreground person.
            if (frameIndex >= 3) {
                tracks.filter { it.id != selectedId && it.observedThisFrame }.forEach { other ->
                    assertTrue(
                        other.bbox.centerX < 200f || other.bbox.centerX > 340f,
                        "fresh background identity must not bind to selected foreground at frame=$frameIndex"
                    )
                }
            }
        }

        // Background person leaves the frame. Its LOST track may remain for the
        // configured privacy grace period, but it must never rebind to the only
        // remaining foreground detection.
        repeat(4) {
            val tracks = tracker.update(listOf(det(220f, 320f)), timestampUs)
            timestampUs += 33_333L
            val selected = tracks.find { it.id == selectedId }
            assertNotNull(selected)
            assertEquals(TrackState.ACTIVE, selected.state)
            assertTrue(selected.bbox.centerX in 250f..290f)

            tracks.filter { it.id != selectedId }.forEach { background ->
                assertTrue(
                    !background.observedThisFrame,
                    "departed background identity must not steal the only foreground detection"
                )
            }
        }
    }
}
