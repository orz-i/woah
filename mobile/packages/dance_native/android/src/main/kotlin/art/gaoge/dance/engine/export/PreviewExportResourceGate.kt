package art.gaoge.dance.engine.export

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Process-wide ownership of the preview/export GPU lane, not a job-status lock. */
class PreviewExportResourceGate {
    class PreviewOwner(val releaseResources: () -> Unit) {
        val closing = AtomicBoolean(false)
    }

    private val previews = Mutex()
    private val owners = linkedSetOf<PreviewOwner>()
    private val exportOwner = AtomicReference<String?>(null)
    private val cleanupFailed = AtomicBoolean(false)

    private fun requireHealthyLane() {
        check(!cleanupFailed.get()) { "Native resource cleanup failed; restart the application" }
    }

    private fun release(owner: PreviewOwner) {
        try {
            owner.releaseResources()
        } catch (error: Throwable) {
            cleanupFailed.set(true)
            throw error
        }
    }

    suspend fun <T> withPreview(owner: PreviewOwner, block: suspend () -> T): T = previews.withLock {
        requireHealthyLane()
        check(!owner.closing.get()) { "Preview owner is detached" }
        check(exportOwner.get() == null) { "Native resources are owned by an export" }
        owners.add(owner)
        block()
    }

    /** Reserve before waiting: new/queued previews cannot starve the export drain. */
    suspend fun reserveExport(jobId: String) {
        requireHealthyLane()
        check(exportOwner.compareAndSet(null, jobId)) { "Another export owns native resources" }
        try {
            previews.withLock {
                requireHealthyLane()
                for (owner in owners) release(owner)
            }
        } catch (error: Throwable) {
            exportOwner.compareAndSet(jobId, null)
            throw error
        }
    }

    fun ownsExport(jobId: String): Boolean = exportOwner.get() == jobId

    /** Call only after the actual worker and its model have drained, never on cancel notification. */
    fun finishExport(jobId: String) {
        exportOwner.compareAndSet(jobId, null)
    }

    suspend fun closePreview(owner: PreviewOwner) {
        owner.closing.set(true)
        previews.withLock {
            release(owner)
            owners.remove(owner)
        }
    }

    suspend fun <T> withPreviewResources(block: () -> T): T = previews.withLock {
        check(exportOwner.get() == null) { "Native resources are owned by an export" }
        block()
    }
}
