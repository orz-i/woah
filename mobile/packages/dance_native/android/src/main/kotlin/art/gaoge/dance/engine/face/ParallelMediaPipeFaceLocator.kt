package art.gaoge.dance.engine.face

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.Delegate
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Two independent MediaPipe IMAGE-mode detectors used only to overlap detector
 * calls that are already scheduled for the same frame. Each detector is created,
 * invoked and closed on its own thread, including single-request calls, so GPU
 * delegate thread affinity is preserved. Cadence and candidate policy are unchanged.
 */
class ParallelMediaPipeFaceLocator(
    context: Context,
    private val workerCount: Int = DEFAULT_WORKER_COUNT,
    minDetectionConfidence: Float = MediaPipeFaceLocator.DEFAULT_MIN_DETECTION_CONFIDENCE,
    preferredDelegate: Delegate = Delegate.CPU
) : FaceLocator {
    private val workers = mutableListOf<Worker>()
    private var closed = false

    init {
        require(workerCount >= 1) { "workerCount must be >= 1" }
        try {
            repeat(workerCount) { index ->
                workers += Worker(context.applicationContext, minDetectionConfidence, preferredDelegate, index)
            }
        } catch (failure: Throwable) {
            workers.forEach { try { it.close() } catch (_: Throwable) { } }
            throw failure
        }
    }

    val effectiveDelegates: List<Delegate>
        get() = workers.map { it.effectiveDelegate }

    override val supportsParallelBatch: Boolean
        get() = workerCount > 1

    @Synchronized
    override fun detectRgbaTopDown(
        rgba: ByteBuffer,
        width: Int,
        height: Int
    ): FaceLocatorResult {
        check(!closed) { "ParallelMediaPipeFaceLocator is closed" }
        return workers[0].submit(FaceLocatorRequest(rgba, width, height)).get()
    }

    @Synchronized
    override fun detectBatchRgbaTopDown(
        requests: List<FaceLocatorRequest>
    ): List<FaceLocatorResult> {
        check(!closed) { "ParallelMediaPipeFaceLocator is closed" }
        if (requests.isEmpty()) return emptyList()
        if (workerCount == 1 || requests.size == 1) {
            return requests.map { request ->
                workers[0].submit(request).get()
            }
        }

        val results = ArrayList<FaceLocatorResult>(requests.size)
        var offset = 0
        while (offset < requests.size) {
            val waveSize = minOf(workerCount, requests.size - offset)
            val futures = ArrayList<java.util.concurrent.Future<FaceLocatorResult>>(waveSize)
            for (workerIndex in 0 until waveSize) {
                val request = requests[offset + workerIndex]
                futures += workers[workerIndex].submit(request)
            }
            val waveResults = arrayOfNulls<FaceLocatorResult>(waveSize)
            var waveFailure: Throwable? = null
            futures.forEachIndexed { index, future ->
                try {
                    waveResults[index] = future.get()
                } catch (t: Throwable) {
                    if (waveFailure == null) waveFailure = t
                }
            }
            if (waveFailure != null) {
                throw IllegalStateException("Parallel face detector worker failed", waveFailure)
            }
            waveResults.forEach { result -> results += requireNotNull(result) }
            offset += waveSize
        }
        return results
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        workers.forEach { worker ->
            try {
                worker.close()
            } catch (_: Throwable) {
            }
        }
    }

    private class Worker(
        private val context: Context,
        private val minDetectionConfidence: Float,
        preferredDelegate: Delegate,
        index: Int
    ) {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FaceLocator-$index").apply { isDaemon = true }
        }
        @Volatile var effectiveDelegate = preferredDelegate
            private set
        private var locator: MediaPipeFaceLocator

        init {
            try {
                locator = executor.submit(Callable {
                    try {
                        createLocator(preferredDelegate)
                    } catch (failure: RuntimeException) {
                        if (preferredDelegate != Delegate.GPU) throw failure
                        Log.w(TAG, "FACE_LOCATOR_GPU_INIT_FALLBACK")
                        effectiveDelegate = Delegate.CPU
                        createLocator(Delegate.CPU)
                    }
                }).get()
                Log.i(TAG, "FACE_LOCATOR_READY worker=$index delegate=$effectiveDelegate")
            } catch (failure: Throwable) {
                executor.shutdownNow()
                throw failure
            }
        }

        private fun createLocator(delegate: Delegate) = MediaPipeFaceLocator(
            context, minDetectionConfidence, delegate
        )

        fun submit(request: FaceLocatorRequest): Future<FaceLocatorResult> = executor.submit(Callable {
            try {
                locator.detectRgbaTopDown(request.rgba, request.width, request.height)
            } catch (failure: RuntimeException) {
                if (effectiveDelegate != Delegate.GPU) throw failure
                Log.w(TAG, "FACE_LOCATOR_GPU_RUNTIME_FALLBACK")
                try { locator.close() } catch (_: RuntimeException) { }
                effectiveDelegate = Delegate.CPU
                locator = createLocator(Delegate.CPU)
                locator.detectRgbaTopDown(request.rgba, request.width, request.height)
            }
        })

        fun close() {
            try {
                executor.submit { locator.close() }.get()
            } finally {
                executor.shutdownNow()
            }
        }
    }

    companion object {
        const val DEFAULT_WORKER_COUNT = 2
        private const val TAG = "ParallelFaceLocator"
    }
}
