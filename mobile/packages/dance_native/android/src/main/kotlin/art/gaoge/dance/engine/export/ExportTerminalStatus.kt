package art.gaoge.dance.engine.export

import art.gaoge.dance.engine.bridge.JobStatusDto
import java.io.File

/**
 * Classifies native export teardown without converting a user-requested
 * cancellation into a transient failure. The current export worker can still
 * throw while decoders and GPU resources are closing after cancellation.
 */
internal object ExportTerminalStatus {
    fun afterException(
        previous: JobStatusDto,
        cancelRequested: Boolean,
        errorCode: String,
        errorMessage: String
    ): JobStatusDto = if (cancelRequested) {
        previous.copy(
            state = "cancelled",
            outputUri = null,
            currentPreviewPath = null,
            errorCode = null,
            errorMessage = null
        )
    } else {
        previous.copy(
            state = "failed",
            outputUri = null,
            currentPreviewPath = null,
            errorCode = errorCode,
            errorMessage = errorMessage
        )
    }
    /** The renderer supplies the actual path, including names allocated in its export cache. */
    fun discardCancelledCompletion(completion: JobStatusDto, storedState: String): Boolean {
        if (completion.state != "completed" || storedState != "cancelled") return true
        val output = completion.outputUri?.let(::File) ?: return true
        return output.delete() || !output.exists()
    }
}
