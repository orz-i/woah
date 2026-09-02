package com.danceanon.native.pipeline

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
}
