package com.danceanon.native.sam2

import android.content.Context
import android.util.Log
import com.danceanon.native.litert.LiteRtAccelerator
import com.danceanon.native.litert.LiteRtModelRunner
import com.danceanon.native.litert.LiteRtRunnerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Sam2GpuState {
    UNKNOWN,
    PROBING,
    AVAILABLE,
    UNAVAILABLE
}

object Sam2GpuCapabilityManager {
    private const val TAG = "Sam2GpuCapabilityManager"

    @Volatile
    private var currentState: Sam2GpuState = Sam2GpuState.UNKNOWN

    @Volatile
    private var unavailableReason: String? = null

    private val mutex = Mutex()

    fun getState(): Sam2GpuState = currentState

    fun isAvailable(): Boolean = (currentState == Sam2GpuState.AVAILABLE)

    fun getUnavailableReason(): String? = unavailableReason

    fun markUnavailable(reason: String) {
        currentState = Sam2GpuState.UNAVAILABLE
        unavailableReason = reason
        Log.w(TAG, "[SAM2-GATE] Explicitly marked UNAVAILABLE: $reason")
    }

    fun resetForTesting() {
        currentState = Sam2GpuState.UNKNOWN
        unavailableReason = null
    }

    fun setForTesting(state: Sam2GpuState, reason: String? = null) {
        currentState = state
        unavailableReason = reason
    }

    suspend fun probe(context: Context): Sam2GpuState = withContext(Dispatchers.Default) {
        if (currentState == Sam2GpuState.AVAILABLE || currentState == Sam2GpuState.UNAVAILABLE) {
            return@withContext currentState
        }

        mutex.withLock {
            if (currentState == Sam2GpuState.AVAILABLE || currentState == Sam2GpuState.UNAVAILABLE) {
                return@withLock currentState
            }

            currentState = Sam2GpuState.PROBING
            var probeRunner: LiteRtModelRunner? = null
            var probeStage = "compile"

            try {
                // 1. Strict GPU-only runner for image_features.tflite
                probeRunner = LiteRtModelRunner.fromAsset(
                    context = context,
                    assetPath = Sam2TensorContract.MODEL_IMAGE_FEATURES,
                    policy = LiteRtRunnerPolicy.STRICT_GPU
                )

                probeStage = "initialize"
                probeRunner.initialize()

                // 2. Validate effective accelerator is strictly GPU
                if (probeRunner.effectiveAccelerator != LiteRtAccelerator.GPU) {
                    throw IllegalStateException("LiteRT image_features runner effective accelerator was not GPU (${probeRunner.effectiveAccelerator})")
                }

                // 3. Validate input/output buffers
                probeStage = "buffer"
                val inBufs = probeRunner.getInputBuffers()
                val outBufs = probeRunner.getOutputBuffers()

                if (inBufs.isEmpty() || outBufs.size < 4) {
                    throw IllegalStateException("Unexpected buffer counts for SAM2 image features: in=${inBufs.size}, out=${outBufs.size} (expected 1 in, >=4 out)")
                }

                // 4. Write legal [1, 3, 1024, 1024] FP32 dummy input with valid normalized floats
                probeStage = "run"
                val dummyInput = FloatArray(1 * 3 * 1024 * 1024) { 0.5f }
                inBufs[0].writeFloat(dummyInput)

                // 5. Execute real GPU inference and measure warmup time
                val t0 = System.currentTimeMillis()
                probeRunner.runInference()
                val warmupMs = System.currentTimeMillis() - t0

                // 6. Confirm output buffer readability
                probeStage = "readback"
                val out0 = outBufs[0].readFloat()
                if (out0.isEmpty()) {
                    throw IllegalStateException("SAM2 image features returned empty output float array")
                }

                val compileMs = probeRunner.runtimeInfo?.compileMs ?: 0L

                currentState = Sam2GpuState.AVAILABLE
                unavailableReason = null

                Log.i(
                    TAG,
                    "[SAM2-GATE]\nstate=AVAILABLE\nmodel=sam2_image_features.tflite\naccelerator=GPU\ncompile_ms=$compileMs\nwarmup_ms=$warmupMs"
                )
            } catch (e: Throwable) {
                val reasonMsg = "${e.javaClass.simpleName}: ${e.message}"
                currentState = Sam2GpuState.UNAVAILABLE
                unavailableReason = "Stage $probeStage failed: $reasonMsg"

                Log.w(
                    TAG,
                    "[SAM2-GATE]\nstate=UNAVAILABLE\nstage=$probeStage\nreason=$unavailableReason",
                    e
                )
            } finally {
                try {
                    probeRunner?.close()
                } catch (_: Throwable) {}
            }

            currentState
        }
    }
}
