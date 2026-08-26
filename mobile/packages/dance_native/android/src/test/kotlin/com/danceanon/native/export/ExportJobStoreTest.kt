package com.danceanon.native.export

import com.danceanon.native.bridge.ExportRequestDto
import com.danceanon.native.bridge.JobStatusDto
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportJobStoreTest {

    private fun createDummyRequest(): ExportRequestDto {
        return ExportRequestDto(
            sourceUri = "file:///dummy.mp4",
            analysisCacheId = "cache_123",
            outputFilePath = "/dummy/out.mp4",
            selectedPersonIds = listOf(0L),
            effects = com.danceanon.native.bridge.EffectConfigDto(
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
            follow = com.danceanon.native.bridge.FollowConfigDto(false, 0, 1.0, 0.15),
            targetWidth = 1920,
            targetHeight = 1080,
            targetFps = 30.0,
            videoBitrate = 8000000
        )
    }

    @Test
    fun testThrottledDiskWritesAndImmediateTerminalPersistence() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "job_store_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val store = ExportJobStore(filesDir = tempDir, diskPersistIntervalMs = 5000L)

            val record = ExportJobRecord.fromRequest("job_throttle_test", createDummyRequest(), "preparing")
            store.saveJob(record)
            val initialWrites = store.diskWriteCount

            // 100 rapid progress updates within milliseconds
            for (i in 1..100) {
                val progressStatus = JobStatusDto(
                    jobId = "job_throttle_test",
                    state = "processing",
                    currentFrame = i.toLong(),
                    totalFrames = 100L,
                    fps = 30.0,
                    progress = i / 100.0,
                    outputUri = null,
                    errorCode = null,
                    errorMessage = null
                )
                store.updateStatus("job_throttle_test", progressStatus)
            }

            // High frequency progress updates should NOT have triggered 100 disk writes
            assertEquals(initialWrites, store.diskWriteCount, "Disk writes must be throttled during active processing")

            // Memory cache should immediately reflect the latest frame
            val cached = store.getJob("job_throttle_test")
            assertEquals(100L, cached?.currentFrame)

            // Terminal state update (completed) must write immediately
            val completedStatus = JobStatusDto(
                jobId = "job_throttle_test",
                state = "completed",
                currentFrame = 100L,
                totalFrames = 100L,
                fps = 30.0,
                progress = 1.0,
                outputUri = "/dummy/out.mp4",
                errorCode = null,
                errorMessage = null
            )
            store.updateStatus("job_throttle_test", completedStatus)
            assertEquals(initialWrites + 1, store.diskWriteCount, "Terminal state must write to disk immediately")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
