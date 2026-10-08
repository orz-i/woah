package art.gaoge.dance.engine.clarity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CropClarityQualityGateTest {
    @Test fun boundedStrength() {
        assertEquals(0f, CropClarityQualityGate.strength(Double.NaN))
        assertEquals(0f, CropClarityQualityGate.strength(1.0))
        assertEquals(0.55f, CropClarityQualityGate.strength(2.0))
        assertEquals(0.55f, CropClarityQualityGate.strength(40.0))
        assertTrue(kotlin.math.abs(CropClarityQualityGate.strength(1.5) - 0.275f) < 1e-6)
    }

    @Test fun debugAndCropRequired() {
        assertFalse(CropClarityQualityGate.eligible(debug = false, postCrop = true, hasPrivacyTargets = true, requestedScale = 2.0))
        assertFalse(CropClarityQualityGate.eligible(debug = true, postCrop = false, hasPrivacyTargets = true, requestedScale = 2.0))
        assertFalse(CropClarityQualityGate.eligible(debug = true, postCrop = true, hasPrivacyTargets = false, requestedScale = 2.0))
        assertFalse(CropClarityQualityGate.eligible(debug = true, postCrop = true, hasPrivacyTargets = true, requestedScale = 1.0))
        assertTrue(CropClarityQualityGate.eligible(debug = true, postCrop = true, hasPrivacyTargets = true, requestedScale = 2.0))
    }

    @Test fun firstMiddleNearEndAreUniqueAndBounded() {
        assertEquals(setOf(1), CropClarityQualityGate.sampleFrames(1))
        assertEquals(setOf(1, 2), CropClarityQualityGate.sampleFrames(2))
        assertEquals(setOf(1, 325, 326, 645), CropClarityQualityGate.sampleFrames(649))
        assertTrue(CropClarityQualityGate.sampleFrames(1000).size <= CropClarityQualityGate.MAX_PAIRS)
    }

    @Test fun metadataOverestimateDoesNotSkipFinalSample() {
        // Production diagnostic: 650 estimated frames, 649 actually decoded.
        val targets = CropClarityQualityGate.sampleFrames(650)
        assertEquals(setOf(1, 325, 326, 646), targets)
        assertTrue(targets.all { it <= 649 })
        assertTrue(targets.none { it == 650 })
        assertEquals(4, targets.size)
        assertEquals(emptyList(), CropClarityQualityGate.missingFrames(targets, (1..649).toList()))
        assertEquals(listOf(646), CropClarityQualityGate.missingFrames(targets, listOf(1, 325, 326)))
    }
}
