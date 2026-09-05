package com.danceanon.native.face

import android.content.Context
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Two independent MediaPipe IMAGE-mode detectors used only to overlap detector
 * calls that are already scheduled for the same frame. Each worker preserves the
 * exact [MediaPipeFaceLocator] model/delegate/confidence configuration; batching
 * changes execution overlap only, never detector cadence or candidate policy.
 */
class ParallelMediaPipeFaceLocator(
    context: Context,
    private val workerCount: Int = DEFAULT_WORKER_COUNT,
    minDetectionConfidence: Float = MediaPipeFaceLocator.DEFAULT_MIN_DETECTION_CONFIDENCE
) : FaceLocator {
    init {
        require(workerCount >= 1) { "workerCount must be >= 1" }
    }

    private val workers = List(workerCount) {
        MediaPipeFaceLocator(
            context = context.applicationContext,
            minDetectionConfidence = minDetectionConfidence
        )
    }
    private val executor = Executors.newFixedThreadPool(workerCount) { runnable ->
        Thread(runnable, "FaceLocatorPool").apply { isDaemon = true }
    }
    private var closed = false

    override val supportsParallelBatch: Boolean
        get() = workerCount > 1

    @Synchronized
    override fun detectRgbaTopDown(
        rgba: ByteBuffer,
        width: Int,
        height: Int
    ): FaceLocatorResult {
        check(!closed) { "ParallelMediaPipeFaceLocator is closed" }
        return workers[0].detectRgbaTopDown(rgba, width, height)
    }

    @Synchronized
    override fun detectBatchRgbaTopDown(
        requests: List<FaceLocatorRequest>
    ): List<FaceLocatorResult> {
        check(!closed) { "ParallelMediaPipeFaceLocator is closed" }
        if (requests.isEmpty()) return emptyList()
        if (workerCount == 1 || requests.size == 1) {
            return requests.map { request ->
                workers[0].detectRgbaTopDown(request.rgba, request.width, request.height)
            }
        }

        val results = ArrayList<FaceLocatorResult>(requests.size)
        var offset = 0
        while (offset < requests.size) {
            val waveSize = minOf(workerCount, requests.size - offset)
            val futures = ArrayList<java.util.concurrent.Future<FaceLocatorResult>>(waveSize)
            for (workerIndex in 0 until waveSize) {
                val request = requests[offset + workerIndex]
                val worker = workers[workerIndex]
                futures += executor.submit(
                    Callable {
                        worker.detectRgbaTopDown(request.rgba, request.width, request.height)
                    }
                )
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
        executor.shutdownNow()
    }

    companion object {
        const val DEFAULT_WORKER_COUNT = 2
    }
}
