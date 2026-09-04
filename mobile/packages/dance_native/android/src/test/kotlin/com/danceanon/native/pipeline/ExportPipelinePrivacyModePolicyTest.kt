package com.danceanon.native.pipeline

import com.danceanon.native.storage.AnalysisMetadata
import com.danceanon.native.storage.CachedBBox
import com.danceanon.native.storage.CachedPerson
import com.danceanon.native.inference.FloatRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExportPipelinePrivacyModePolicyTest {
    @Test
    fun `fresh full body class primary is restricted to full body only exports`() {
        assertTrue(
            ExportPipeline.shouldUseFreshFullBodyClassPrimary(
                fullBodyPersonIds = setOf(4),
                faceOnlyPersonIds = emptySet()
            )
        )
        assertFalse(
            ExportPipeline.shouldUseFreshFullBodyClassPrimary(
                fullBodyPersonIds = setOf(4),
                faceOnlyPersonIds = setOf(1, 2, 3, 5, 6)
            )
        )
        assertFalse(
            ExportPipeline.shouldUseFreshFullBodyClassPrimary(
                fullBodyPersonIds = emptySet(),
                faceOnlyPersonIds = setOf(1)
            )
        )
        assertFalse(
            ExportPipeline.shouldUseFreshFullBodyClassPrimary(
                fullBodyPersonIds = emptySet(),
                faceOnlyPersonIds = emptySet()
            )
        )
    }

    @Test
    fun `deterministic cpu primary is restricted to debug face only yolo exports`() {
        assertTrue(
            ExportPipeline.shouldPreferDebugFaceDeterministicCpuPrimary(
                isDebugBuild = true,
                isSam2Mode = false,
                fullBodyPersonIds = emptySet(),
                faceOnlyPersonIds = setOf(1, 3, 6)
            )
        )
        assertFalse(
            ExportPipeline.shouldPreferDebugFaceDeterministicCpuPrimary(
                isDebugBuild = false,
                isSam2Mode = false,
                fullBodyPersonIds = emptySet(),
                faceOnlyPersonIds = setOf(1)
            )
        )
        assertFalse(
            ExportPipeline.shouldPreferDebugFaceDeterministicCpuPrimary(
                isDebugBuild = true,
                isSam2Mode = true,
                fullBodyPersonIds = emptySet(),
                faceOnlyPersonIds = setOf(1)
            )
        )
        assertFalse(
            ExportPipeline.shouldPreferDebugFaceDeterministicCpuPrimary(
                isDebugBuild = true,
                isSam2Mode = false,
                fullBodyPersonIds = setOf(4),
                faceOnlyPersonIds = setOf(1)
            )
        )
        assertFalse(
            ExportPipeline.shouldPreferDebugFaceDeterministicCpuPrimary(
                isDebugBuild = true,
                isSam2Mode = false,
                fullBodyPersonIds = emptySet(),
                faceOnlyPersonIds = emptySet()
            )
        )
    }

    @Test
    fun `face reference canonicalization collapses subpixel cpu noise without touching tracker policy`() {
        assertEquals(100.5f, ExportPipeline.canonicalizeFaceReferenceCoordinate(100.47f))
        assertEquals(100.5f, ExportPipeline.canonicalizeFaceReferenceCoordinate(100.53f))

        assertEquals(
            FloatRect(100.5f, 200.0f, 300.5f, 500.0f),
            ExportPipeline.canonicalizeFaceReferenceBbox(
                FloatRect(100.47f, 200.03f, 300.53f, 499.97f)
            )
        )
    }

    @Test
    fun `face only identity roots include credible unselected analysis neighbors`() {
        val metadata = AnalysisMetadata(
            sourceUri = "test.mp4",
            persons = listOf(
                cachedPerson(id = 0, confidence = 0.91),
                cachedPerson(id = 1, confidence = 0.84),
                cachedPerson(id = 2, confidence = 0.84),
                cachedPerson(id = 3, confidence = 0.78),
                cachedPerson(id = 4, confidence = 0.56),
                cachedPerson(id = 5, confidence = 0.82),
                cachedPerson(id = 6, confidence = 0.79)
            )
        )

        assertEquals(
            setOf(0, 1, 2, 3, 5, 6),
            ExportPipeline.resolveFaceOnlyIdentityProtectedIds(
                metadata = metadata,
                privacyTargetIds = setOf(0, 1, 3, 5, 6)
            )
        )
    }

    @Test
    fun `face only identity roots fail safe to selected ids without analysis metadata`() {
        assertEquals(
            setOf(1, 3, 5),
            ExportPipeline.resolveFaceOnlyIdentityProtectedIds(
                metadata = null,
                privacyTargetIds = setOf(1, 3, 5)
            )
        )
    }

    private fun cachedPerson(id: Int, confidence: Double): CachedPerson = CachedPerson(
        id = id,
        bbox = CachedBBox(
            left = id * 0.1,
            top = 0.1,
            right = id * 0.1 + 0.08,
            bottom = 0.9
        ),
        confidence = confidence
    )
}
