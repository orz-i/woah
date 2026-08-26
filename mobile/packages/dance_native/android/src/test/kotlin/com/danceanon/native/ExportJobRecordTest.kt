package com.danceanon.native

import com.danceanon.native.bridge.JobStatusDto
import com.danceanon.native.export.ExportJobRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.json.JSONObject

class ExportJobRecordTest {

    @Test
    fun testSerializationDeserializationRoundtrip() {
        val record = ExportJobRecord(
            jobId = "job_12345",
            state = "processing",
            sourceUri = "file:///videos/dance.mp4",
            outputPath = "/data/user/0/com.danceanon/files/output.mp4",
            analysisCacheId = "cache_999",
            targetWidth = 1920,
            targetHeight = 1080,
            targetFps = 30.0,
            videoBitrate = 8000000,
            selectedPersonIds = listOf(1, 3),
            progress = 0.55,
            currentFrame = 165L,
            totalFrames = 300L,
            fps = 29.8
        )

        val json = record.toJson()
        val restored = ExportJobRecord.fromJson(json)

        assertEquals("job_12345", restored.jobId)
        assertEquals("processing", restored.state)
        assertEquals("file:///videos/dance.mp4", restored.sourceUri)
        assertEquals("/data/user/0/com.danceanon/files/output.mp4", restored.outputPath)
        assertEquals("cache_999", restored.analysisCacheId)
        assertEquals(1920, restored.targetWidth)
        assertEquals(1080, restored.targetHeight)
        assertEquals(30.0, restored.targetFps)
        assertEquals(8000000, restored.videoBitrate)
        assertEquals(listOf(1, 3), restored.selectedPersonIds)
        assertEquals(0.55, restored.progress)
        assertEquals(165L, restored.currentFrame)
        assertEquals(300L, restored.totalFrames)
        assertEquals(29.8, restored.fps)

        val statusDto = restored.toJobStatusDto()
        assertEquals("job_12345", statusDto.jobId)
        assertEquals("processing", statusDto.state)
        assertEquals(0.55, statusDto.progress)
    }
}
