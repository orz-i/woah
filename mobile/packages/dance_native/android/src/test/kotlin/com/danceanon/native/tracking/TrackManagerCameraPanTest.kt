package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackManagerCameraPanTest {

    private lateinit var tracker: TrackManager

    @BeforeTest
    fun setUp() {
        tracker = TrackManager(TrackingConfig())
    }

    private fun createDummyMask(): NativeMask {
        val size = 64
        val buf = ByteBuffer.allocateDirect(size * size)
        for (i in 0 until size * size) {
            buf.put(255.toByte())
        }
        buf.rewind()
        return NativeMask(
            width = size,
            height = size,
            buffer = buf,
            originalWidth = 640,
            originalHeight = 640
        )
    }

    @Test
    fun testCameraPanGlobalMotionCompensationPreservesIdentities() {
        // Frame 1: Three people at x=100, x=250, x=400
        val p1 = PersonDetection(bbox = FloatRect(100f, 100f, 160f, 300f), confidence = 0.95f, mask = createDummyMask())
        val p2 = PersonDetection(bbox = FloatRect(250f, 100f, 310f, 300f), confidence = 0.95f, mask = createDummyMask())
        val p3 = PersonDetection(bbox = FloatRect(400f, 100f, 460f, 300f), confidence = 0.95f, mask = createDummyMask())

        val init = tracker.initialize(listOf(p1, p2, p3))
        val id1 = init[0].id
        val id2 = init[1].id
        val id3 = init[2].id

        // Frame 2: Camera pans right -> all persons shift +70px in image coordinates (x=170, x=320, x=470)
        val p1_f2 = PersonDetection(bbox = FloatRect(170f, 100f, 230f, 300f), confidence = 0.95f, mask = createDummyMask())
        val p2_f2 = PersonDetection(bbox = FloatRect(320f, 100f, 380f, 300f), confidence = 0.95f, mask = createDummyMask())
        val p3_f2 = PersonDetection(bbox = FloatRect(470f, 100f, 530f, 300f), confidence = 0.95f, mask = createDummyMask())

        val tracks_f2 = tracker.update(listOf(p1_f2, p2_f2, p3_f2), timestampUs = 33_333L)
        assertEquals(3, tracks_f2.size)

        val t1 = tracks_f2.find { it.id == id1 }
        val t2 = tracks_f2.find { it.id == id2 }
        val t3 = tracks_f2.find { it.id == id3 }

        assertEquals(170f, t1?.bbox?.left)
        assertEquals(320f, t2?.bbox?.left)
        assertEquals(470f, t3?.bbox?.left)
    }
}
