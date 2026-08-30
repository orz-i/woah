package com.danceanon.native.privacy

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyMaskFaceHeadEstimatorTest {
    private val mapper = ModelCoordinateMapper(640, 640, 640, 160)

    @Test
    fun `current body mask pulls fallback toward shifted head silhouette`() {
        val person = FloatRect(180f, 80f, 420f, 580f)
        val mask = maskOf(
            FloatRect(315f, 115f, 365f, 180f), // head shifted right
            FloatRect(230f, 175f, 385f, 560f)  // torso
        )

        val estimate = assertNotNull(
            BodyMaskFaceHeadEstimator.estimate(
                mask = mask,
                personBbox = person,
                seedCenterX = 295f,
                seedCenterY = 155f,
                seedRadiusX = 45f,
                seedRadiusY = 55f
            )
        )
        assertTrue(estimate.x > 305f, "mask-guided head center should follow the shifted head: $estimate")
        assertTrue(estimate.y in 130f..205f)
    }

    @Test
    fun `distant raised arm cannot pull local face fallback away from head seed`() {
        val person = FloatRect(120f, 60f, 500f, 600f)
        val mask = maskOf(
            FloatRect(275f, 105f, 335f, 175f), // head
            FloatRect(230f, 170f, 385f, 590f), // torso
            FloatRect(130f, 55f, 175f, 250f)   // raised arm far from face seed
        )

        val estimate = assertNotNull(
            BodyMaskFaceHeadEstimator.estimate(
                mask = mask,
                personBbox = person,
                seedCenterX = 300f,
                seedCenterY = 145f,
                seedRadiusX = 45f,
                seedRadiusY = 55f
            )
        )
        assertTrue(estimate.x > 270f, "raised arm must not steal local head fallback: $estimate")
    }

    @Test
    fun `arm crossing local head window cannot turn body silhouette into face centroid`() {
        val person = FloatRect(120f, 60f, 500f, 600f)
        val mask = maskOf(
            FloatRect(275f, 105f, 335f, 180f), // real head
            FloatRect(225f, 175f, 390f, 590f), // shoulders / torso
            FloatRect(185f, 112f, 390f, 145f)  // arm crossing through the head window
        )

        val estimate = assertNotNull(
            BodyMaskFaceHeadEstimator.estimate(
                mask = mask,
                personBbox = person,
                seedCenterX = 303f,
                seedCenterY = 145f,
                seedRadiusX = 45f,
                seedRadiusY = 55f
            )
        )
        assertTrue(
            estimate.x in 275f..340f,
            "wide arm union must be rejected in favor of narrow head-like rows: $estimate"
        )
        assertTrue(
            estimate.y < 190f,
            "wide shoulder/torso rows must not pull FACE_ONLY placement downward: $estimate"
        )
    }

    @Test
    fun `missing mapper refuses mask geometry instead of guessing letterbox`() {
        val buf = ByteBuffer.allocateDirect(160 * 160)
        val mask = NativeMask(160, 160, buf, 640, 640, mapper = null)
        assertNull(
            BodyMaskFaceHeadEstimator.estimate(
                mask = mask,
                personBbox = FloatRect(100f, 50f, 300f, 500f),
                seedCenterX = 200f,
                seedCenterY = 120f,
                seedRadiusX = 40f,
                seedRadiusY = 50f
            )
        )
    }

    private fun maskOf(vararg sourceRects: FloatRect): NativeMask {
        val buf = ByteBuffer.allocateDirect(160 * 160)
        repeat(160 * 160) { buf.put(0) }
        for (rect in sourceRects) {
            val left = mapper.sourceToProtoX(rect.left).roundToInt().coerceIn(0, 159)
            val top = mapper.sourceToProtoY(rect.top).roundToInt().coerceIn(0, 159)
            val right = mapper.sourceToProtoX(rect.right).roundToInt().coerceIn(left + 1, 160)
            val bottom = mapper.sourceToProtoY(rect.bottom).roundToInt().coerceIn(top + 1, 160)
            for (y in top until bottom) {
                for (x in left until right) {
                    buf.put(y * 160 + x, 255.toByte())
                }
            }
        }
        buf.rewind()
        return NativeMask(
            width = 160,
            height = 160,
            buffer = buf,
            originalWidth = 640,
            originalHeight = 640,
            mapper = mapper
        )
    }
}
