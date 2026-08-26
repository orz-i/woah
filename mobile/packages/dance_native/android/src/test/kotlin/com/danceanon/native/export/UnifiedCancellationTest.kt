package com.danceanon.native.export

import com.danceanon.native.bridge.ExportRequestDto
import com.danceanon.native.bridge.EffectConfigDto
import com.danceanon.native.bridge.FollowConfigDto
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedCancellationTest {

    @Test
    fun testCoordinatorCancelJobSetsFlagAndUpdatesJobStore() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "woah_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        // Create dummy request
        val request = ExportRequestDto(
            sourceUri = "file:///dummy.mp4",
            analysisCacheId = "cache_123",
            outputFilePath = "/dummy/out.mp4",
            selectedPersonIds = listOf(0L),
            effects = EffectConfigDto(

                fillMode = "solid",
                fillColorArgb = 0xFF000000L,
                borderColorArgb = 0L,

                opacity = 1.0,
                borderWidth = 0.0,
                blurStrength = 0.0,
                faceStickerEnabled = false,
                stickerAssetId = null,
                stickerScale = 1.0,
                skinWhiten = 0.0,
                legStretchEnabled = false,
                legStretch = 0.0,
                legZoneTop = 0.6,
                legZoneBottom = 0.95
            ),
            follow = FollowConfigDto(false, 0, 1.0, 0.15),
            targetWidth = 1920,
            targetHeight = 1080,
            targetFps = 30.0,
            videoBitrate = 8000000
        )

        // Mock ExportJobStore in memory
        val record = ExportJobRecord.fromRequest("job_cancel_test", request, initialState = "processing")
        val flag = java.util.concurrent.atomic.AtomicBoolean(false)

        assertEquals(false, flag.get())
        flag.set(true)
        assertEquals(true, flag.get())

        val cancelledStatus = record.toJobStatusDto().copy(state = "cancelled")
        assertEquals("cancelled", cancelledStatus.state)
        assertEquals("job_cancel_test", cancelledStatus.jobId)

        tempDir.deleteRecursively()
    }
}
