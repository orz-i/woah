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

class FaceTrustedMaskFallbackTest {
    private val mapper = ModelCoordinateMapper(640, 640, 640, 160)

    @Test
    fun `fresh mask moves trusted seed toward current head without following body box center`() {
        val currentPerson = FloatRect(150f, 80f, 450f, 580f)
        val mask = maskOf(
            FloatRect(295f, 112f, 345f, 182f),
            FloatRect(225f, 178f, 395f, 570f)
        )
        val resolved = assertNotNull(
            FaceTrustedMaskFallback.resolve(
                mask = mask,
                currentPersonBbox = currentPerson,
                trusted = FacePrivacyTrustedGeometry(
                    centerX = 300f,
                    centerY = 150f,
                    radiusX = 40f,
                    radiusY = 50f,
                    trustedPersonBbox = FloatRect(180f, 80f, 420f, 580f)
                ),
                radiusExpansion = 1.10f
            )
        )
        assertTrue(resolved.centerX > 300f)
        assertTrue(resolved.centerY in 125f..205f)
        assertTrue(resolved.radiusX in 43.9f..44.1f)
        assertTrue(resolved.radiusY in 54.9f..55.1f)
    }

    @Test
    fun `stale trusted seed cannot render without current head like mask support`() {
        val empty = maskOf()
        assertNull(
            FaceTrustedMaskFallback.resolve(
                mask = empty,
                currentPersonBbox = FloatRect(150f, 80f, 450f, 580f),
                trusted = FacePrivacyTrustedGeometry(
                    centerX = 300f,
                    centerY = 150f,
                    radiusX = 40f,
                    radiusY = 50f,
                    trustedPersonBbox = FloatRect(180f, 80f, 420f, 580f)
                ),
                radiusExpansion = 1.10f
            )
        )
    }

    private fun maskOf(vararg sourceRects: FloatRect): NativeMask {
        val buf = ByteBuffer.allocateDirect(160 * 160)
        repeat(160 * 160) { buf.put(0) }
        sourceRects.forEach { rect ->
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
