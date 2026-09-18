package com.danceanon.native.render

import kotlin.test.Test
import kotlin.test.assertTrue

class LegStretchRenderTest {

    @Test
    fun legStretchUsesIndependentProtagonistMask() {
        val oes = GlShaders.FRAGMENT_SHADER_OES
        val tex2d = GlShaders.FRAGMENT_SHADER_2D

        listOf(oes, tex2d).forEach { source ->
            assertTrue(source.contains("uLegMaskTexture"))
            assertTrue(source.contains("uHasLegMask"))
            assertTrue(source.contains("uLegRect"))
            assertTrue(source.contains("vLegMaskTexCoord"))
            assertTrue(source.contains("uHasMask == 0 && uHasLegMask == 0"))
            assertTrue(source.contains("rectHeight * uLegZoneTop"))
            assertTrue(source.contains("rectHeight * uLegZoneBottom"))
        }
    }

    @Test
    fun legStretchNoLongerUsesPrivacyMaskAsBeautyMask() {
        val source = GlShaders.FRAGMENT_SHADER_2D
        assertTrue(source.contains("legMaskVal = texture2D(uLegMaskTexture"))
        assertTrue(source.contains("beautyAlpha = smoothstep"))
        assertTrue(source.contains("color = mix(originalColor, warpedColor, beautyAlpha)"))
    }
}
