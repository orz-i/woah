package art.gaoge.dance.engine.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Jobs owned by one Service instance; never cancel another instance's reservation. */
class OwnedExportJobs {
    private val jobs = ConcurrentHashMap<String, Job>()
    // Android advances startId even for duplicate start/progress Intents.
    private var latestStartId = 0
    fun recordStart(startId: Int) { latestStartId = startId }
    fun idleStopId(): Int? = if (jobs.isEmpty()) latestStartId else null
    fun cancelledStopId(cancelled: List<Job>): Int? =
        if (jobs.values.all { it in cancelled }) latestStartId else null

    fun contains(jobId: String): Boolean = jobs.containsKey(jobId)
    fun isEmpty(): Boolean = jobs.isEmpty()
    fun snapshot(): List<Job> = jobs.values.toList()
    fun cancelOwned(requestCancel: (String) -> Unit) {
        jobs.keys.toList().forEach(requestCancel)
    }

    fun launch(
        scope: CoroutineScope,
        jobId: String,
        cleanup: () -> Unit,
        onCompletion: (Throwable?, Boolean) -> Unit,
        block: suspend CoroutineScope.() -> Unit
    ): Boolean {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        if (jobs.putIfAbsent(jobId, job) != null) {
            job.cancel()
            return false
        }
        job.invokeOnCompletion { cause ->
            // Completion cannot run before the body (including native blocking calls) exits.
            // It also runs when cancellation prevents body entry, so empty resources drain.
            val cleanupResult = runCatching(cleanup)
            jobs.remove(jobId, job)
            onCompletion(cleanupResult.exceptionOrNull() ?: cause, cleanupResult.isSuccess)
        }
        job.start()
        return true
    }
}
