package com.danceanon.native.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaceOnlyDormantReactivationPolicyTest {
    @Test
    fun `dormant probe requires current fresh motion and hidden trusted face`() {
        assertTrue(
            FaceOnlyDormantReactivationPolicy.shouldProbe(
                wasDormant = true,
                observedThisFrame = false,
                hasFreshBodyMotion = true,
                hasTrustedFace = true
            )
        )
        assertFalse(
            FaceOnlyDormantReactivationPolicy.shouldProbe(
                wasDormant = true,
                observedThisFrame = false,
                hasFreshBodyMotion = false,
                hasTrustedFace = true
            )
        )
        assertFalse(
            FaceOnlyDormantReactivationPolicy.shouldProbe(
                wasDormant = true,
                observedThisFrame = false,
                hasFreshBodyMotion = true,
                hasTrustedFace = false
            )
        )
        assertFalse(
            FaceOnlyDormantReactivationPolicy.shouldProbe(
                wasDormant = true,
                observedThisFrame = true,
                hasFreshBodyMotion = true,
                hasTrustedFace = true
            )
        )
    }

    @Test
    fun `dormant detector hit cannot enlarge hidden trusted face size`() {
        val oversizedDetection = FacePrivacyEllipse(
            centerX = 300f,
            centerY = 120f,
            radiusX = 90f,
            radiusY = 110f,
            source = FacePrivacyRegionSource.DETECTED_FACE
        )

        val preserved = FaceOnlyDormantReactivationPolicy.preserveTrustedSize(
            detected = oversizedDetection,
            trustedRadiusX = 28f,
            trustedRadiusY = 31f
        )

        assertEquals(28f, preserved.radiusX)
        assertEquals(31f, preserved.radiusY)
        assertEquals(oversizedDetection.centerX, preserved.centerX)
        assertEquals(oversizedDetection.centerY, preserved.centerY)
    }

    @Test
    fun `dormant probe rejects body translation larger than half trusted face diameter`() {
        assertTrue(
            FaceOnlyDormantReactivationPolicy.isProbeTranslationSafe(
                dx = 30f,
                dy = 0f,
                trustedRadiusX = 40f,
                trustedRadiusY = 40f
            )
        )
        assertFalse(
            FaceOnlyDormantReactivationPolicy.isProbeTranslationSafe(
                dx = 45f,
                dy = 0f,
                trustedRadiusX = 40f,
                trustedRadiusY = 40f
            )
        )
    }
}
