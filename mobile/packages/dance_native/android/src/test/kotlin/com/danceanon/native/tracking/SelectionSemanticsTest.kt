package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionSemanticsTest {

    private fun createDummyMask(): NativeMask {
        val buf = ByteBuffer.allocateDirect(160 * 160)
        for (i in 0 until 160 * 160) buf.put(255.toByte())
        buf.rewind()
        return NativeMask(160, 160, buf, 640, 640)
    }

    @Test
    fun testEmptySelectionDoesNotAnonymizeAnyone() {
        val persons = listOf(
            TrackedPerson(id = 0, bbox = FloatRect(100f, 100f, 200f, 300f), mask = createDummyMask(), confidence = 0.9f),
            TrackedPerson(id = 1, bbox = FloatRect(300f, 100f, 400f, 300f), mask = createDummyMask(), confidence = 0.9f),
            TrackedPerson(id = 2, bbox = FloatRect(500f, 100f, 600f, 300f), mask = createDummyMask(), confidence = 0.9f)
        )

        val selectedIds = emptySet<Int>()

        // GlRenderer selection filter logic
        val toAnonymize = persons.filter { selectedIds.contains(it.id) && it.mask != null }
        assertTrue(toAnonymize.isEmpty(), "Empty selectedPersonIds must yield an empty anonymization set")
    }

    @Test
    fun testSinglePersonSelectedNeverLeaksToOthersEvenWhenLost() {
        val tracker = TrackManager()

        val det0 = PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.9f, createDummyMask())
        val det1 = PersonDetection(FloatRect(300f, 100f, 400f, 300f), 0.9f, createDummyMask())
        val det2 = PersonDetection(FloatRect(500f, 100f, 600f, 300f), 0.9f, createDummyMask())

        // Initial frame: 3 people
        val frame0 = tracker.initialize(listOf(det0, det1, det2))
        assertEquals(3, frame0.size)

        val selectedPersonIds = setOf(1) // Only Person 1 is selected by user

        // Verify initial selection
        val anon0 = frame0.filter { selectedPersonIds.contains(it.id) && it.mask != null }
        assertEquals(1, anon0.size)
        assertEquals(1, anon0[0].id)

        // Frame 1: Person 1 becomes LOST (occluded or missing detection)
        val frame1Detections = listOf(det0, det2)
        val frame1Tracks = tracker.update(frame1Detections, 33333L)
        assertEquals(3, frame1Tracks.size) // 0: ACTIVE, 1: LOST, 2: ACTIVE

        val anon1 = frame1Tracks.filter { selectedPersonIds.contains(it.id) && it.mask != null }
        assertEquals(1, anon1.size)
        assertEquals(1, anon1[0].id, "Only Person 1 can be in the anonymize set")
        assertEquals(TrackState.LOST, anon1[0].state)

        // Person 0 and Person 2 must NEVER be in anonymize set
        val nonSelectedInAnon = anon1.filter { it.id == 0 || it.id == 2 }
        assertTrue(nonSelectedInAnon.isEmpty(), "Person 0 and 2 must never be masked")

        // Frame 2: Person 1 recovers detection
        val frame2Detections = listOf(det0, det1, det2)
        val frame2Tracks = tracker.update(frame2Detections, 66666L)

        val anon2 = frame2Tracks.filter { selectedPersonIds.contains(it.id) && it.mask != null }
        assertEquals(1, anon2.size)
        assertEquals(1, anon2[0].id)
        assertEquals(TrackState.ACTIVE, anon2[0].state)
    }

    @Test
    fun testMultiPersonSelectiveAnonymization() {
        val persons = listOf(
            TrackedPerson(id = 0, bbox = FloatRect(100f, 100f, 200f, 300f), mask = createDummyMask(), confidence = 0.9f),
            TrackedPerson(id = 1, bbox = FloatRect(300f, 100f, 400f, 300f), mask = createDummyMask(), confidence = 0.9f),
            TrackedPerson(id = 2, bbox = FloatRect(500f, 100f, 600f, 300f), mask = createDummyMask(), confidence = 0.9f)
        )

        val selectedIds = setOf(0, 2)
        val anon = persons.filter { selectedIds.contains(it.id) && it.mask != null }
        assertEquals(2, anon.size)
        val anonIds = anon.map { it.id }.toSet()
        assertEquals(setOf(0, 2), anonIds)
    }
}
