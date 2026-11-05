package art.gaoge.dance.engine.export

import art.gaoge.dance.engine.bridge.JobStatusDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportTerminalStatusTest {
    private fun processingStatus(): JobStatusDto = JobStatusDto(
        jobId = "cancel-race",
        state = "processing",
        currentFrame = 42L,
        totalFrames = 100L,
        fps = 30.0,
        progress = 0.42,
        outputUri = null,
        currentPreviewPath = "/tmp/last-preview.jpg",
        errorCode = "PREVIOUS_TRANSIENT",
        errorMessage = "old error"
    )

    @Test
    fun cancellationDuringNativeCleanupNeverBecomesFailure() {
        val result = ExportTerminalStatus.afterException(
            previous = processingStatus(),
            cancelRequested = true,
            errorCode = "EXPORT_FAILED",
            errorMessage = "CancellationException from decoder cleanup"
        )

        assertEquals("cancelled", result.state)
        assertEquals("cancel-race", result.jobId)
        assertEquals(42L, result.currentFrame)
        assertEquals(0.42, result.progress)
        assertNull(result.outputUri)
        assertNull(result.currentPreviewPath)
        assertNull(result.errorCode)
        assertNull(result.errorMessage)
    }

    @Test
    fun unexpectedFailureStillReportsAnActionableError() {
        val result = ExportTerminalStatus.afterException(
            previous = processingStatus(),
            cancelRequested = false,
            errorCode = "ENCODER_FAILED",
            errorMessage = "Unexpected encoder failure"
        )

        assertEquals("failed", result.state)
        assertEquals("ENCODER_FAILED", result.errorCode)
        assertEquals("Unexpected encoder failure", result.errorMessage)
        assertNull(result.currentPreviewPath)
        assertEquals(42L, result.currentFrame)
    }

    @Test
    fun lateCompletedFileIsRemovedOnlyWhenCancellationWon() {
        val output = kotlin.io.path.createTempFile("allocated_export_", ".mp4").toFile()
        try {
            val completion = processingStatus().copy(state = "completed", outputUri = output.absolutePath)
            assertTrue(ExportTerminalStatus.discardCancelledCompletion(completion, "completed"))
            assertTrue(output.isFile, "Never delete a completed job's published file")
            assertTrue(ExportTerminalStatus.discardCancelledCompletion(completion, "cancelled"))
            assertFalse(output.exists(), "Clean the renderer's actual file after a rejected completion")
        } finally {
            output.delete()
        }
    }
}
