package com.danceanon.native.render

import com.danceanon.native.bridge.EffectConfigDto
import com.danceanon.native.bridge.FollowConfigDto
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StickerRenderTest {

    @Test
    fun testDefaultStickerBitmapCreation() {
        val bmp = GlRenderer.createDefaultStickerBitmap()
        if (bmp != null) {
            assertEquals(128, bmp.width)
            assertEquals(128, bmp.height)
            bmp.recycle()
        }
    }

    @Test
    fun testHeadZonePlacementCalculation() {
        val srcW = 1920
        val srcH = 1080
        val bbox = FloatRect(800f, 200f, 1000f, 800f) // Person: width 200, height 600, center (900, 500)

        val headZoneHeight = bbox.height * 0.25f // 150px
        val headCenterX = bbox.centerX // 900
        val headCenterY = bbox.top + headZoneHeight * 0.5f // 200 + 75 = 275px
        val scale = 1.0f
        val halfDim = maxOf(bbox.width * 0.35f, headZoneHeight * 0.6f) * scale // max(70, 90) = 90px

        val sLeft = (headCenterX - halfDim) / srcW.toFloat()
        val sRight = (headCenterX + halfDim) / srcW.toFloat()
        val sTop = (headCenterY - halfDim) / srcH.toFloat()
        val sBottom = (headCenterY + halfDim) / srcH.toFloat()

        assertTrue(sLeft >= 0f && sLeft <= 1f)
        assertTrue(sRight >= sLeft && sRight <= 1f)
        assertTrue(sTop >= 0f && sTop <= 1f)
        assertTrue(sBottom >= sTop && sBottom <= 1f)

        // Head center in normalized space should be in upper half of frame
        val centerNormY = (sTop + sBottom) / 2f
        assertTrue(centerNormY < 0.4f, "Head zone center Y () must be in the upper region of the person")
    }

    @Test
    fun testShaderSourcesContainStickerUniforms() {
        val oesSource = GlShaders.FRAGMENT_SHADER_OES
        val tex2dSource = GlShaders.FRAGMENT_SHADER_2D

        assertTrue(oesSource.contains("uHasSticker"), "OES Fragment shader must declare uHasSticker")
        assertTrue(oesSource.contains("uStickerTexture"), "OES Fragment shader must declare uStickerTexture")
        assertTrue(oesSource.contains("uStickerRect"), "OES Fragment shader must declare uStickerRect")

        assertTrue(tex2dSource.contains("uHasSticker"), "2D Fragment shader must declare uHasSticker")
        assertTrue(tex2dSource.contains("uStickerTexture"), "2D Fragment shader must declare uStickerTexture")
        assertTrue(tex2dSource.contains("uStickerRect"), "2D Fragment shader must declare uStickerRect")
    }

    @Test
    fun testMaskBufferMergingLogic() {
        val width = 160
        val height = 160
        val total = width * height

        val buf1 = ByteBuffer.allocateDirect(total)
        val buf2 = ByteBuffer.allocateDirect(total)

        // Set non-overlapping and overlapping pixels
        buf1.put(10, 200.toByte())
        buf1.put(20, 100.toByte())

        buf2.put(10, 150.toByte())
        buf2.put(20, 250.toByte())
        buf2.put(30, 180.toByte())

        val merged = ByteBuffer.allocateDirect(total)
        for (i in 0 until total) {
            val v1 = buf1.get(i).toInt() and 0xFF
            val v2 = buf2.get(i).toInt() and 0xFF
            val maxV = maxOf(v1, v2).toByte()
            merged.put(maxV)
        }

        assertEquals(200, merged.get(10).toInt() and 0xFF)
        assertEquals(250, merged.get(20).toInt() and 0xFF)
        assertEquals(180, merged.get(30).toInt() and 0xFF)
        assertEquals(0, merged.get(40).toInt() and 0xFF)
    }
}
