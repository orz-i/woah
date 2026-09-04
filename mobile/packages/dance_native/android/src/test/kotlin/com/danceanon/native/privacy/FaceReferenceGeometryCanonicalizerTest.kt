package com.danceanon.native.privacy

import com.danceanon.native.face.FaceHeadRoiPlan
import com.danceanon.native.inference.FloatRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FaceReferenceGeometryCanonicalizerTest {
    @Test
    fun `subpixel face regions collapse to the same half pixel lattice`() {
        val a = FacePrivacyEllipse(100.47f, 200.03f, 20.47f, 21.03f, FacePrivacyRegionSource.DETECTED_FACE)
        val b = FacePrivacyEllipse(100.53f, 199.97f, 20.53f, 20.97f, FacePrivacyRegionSource.DETECTED_FACE)

        assertEquals(
            FaceReferenceGeometryCanonicalizer.ellipse(a),
            FaceReferenceGeometryCanonicalizer.ellipse(b)
        )
    }

    @Test
    fun `subpixel roi plans collapse before canonical sampling`() {
        val a = FaceHeadRoiPlan(
            sourceRect = FloatRect(123.47f, 45.03f, 311.47f, 233.03f),
            anchorX = 0.5001f,
            anchorY = 0.4999f
        )
        val b = FaceHeadRoiPlan(
            sourceRect = FloatRect(123.53f, 44.97f, 311.53f, 232.97f),
            anchorX = 0.4999f,
            anchorY = 0.5001f
        )

        assertEquals(
            FaceReferenceGeometryCanonicalizer.plan(a),
            FaceReferenceGeometryCanonicalizer.plan(b)
        )
    }
}
