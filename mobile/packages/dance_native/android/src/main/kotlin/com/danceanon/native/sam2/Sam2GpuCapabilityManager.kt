package com.danceanon.native.sam2

import android.content.Context
import android.util.Log
import com.danceanon.native.diagnostics.NativeDiagnostics
import com.danceanon.native.litert.LiteRtAccelerator
import com.danceanon.native.litert.LiteRtModelRunner
import com.danceanon.native.litert.LiteRtRunnerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

    @Volatile
    private var failedStage: String? = null

    @Volatile
    private var failedAtWallTime: String? = null

    @Volatile
    private var probeAttemptCount: Int = 0

    private val mutex = Mutex()

    fun getState(): Sam2GpuState = currentState

    fun isAvailable(): Boolean = (currentState == Sam2GpuState.AVAILABLE)

    fun getUnavailableReason(): String? = unavailableReason

    fun getFailedStage(): String? = failedStage

    fun getProbeAttemptCount(): Int = probeAttemptCount

    fun markUnavailable(reason: String) {
        currentState = Sam2GpuState.UNAVAILABLE
        unavailableReason = reason
        failedStage = "explicit_mark"
        failedAtWallTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        Log.w(TAG, "[SAM2-GATE] Explicitly marked UNAVAILABLE: $reason")

        NativeDiagnostics.event(
            level = "WARN",
            component = "Sam2GpuCapabilityManager",
            event = "SAM_GPU_UNAVAILABLE",
            fields = mapOf(
                "state" to "UNAVAILABLE",
                "reason" to reason,
                "stage" to "explicit_mark",
                "attempt_count" to probeAttemptCount
            )
        )
        persistCapabilities()
    }

    fun resetForTesting() {
        currentState = Sam2GpuState.UNKNOWN
        unavailableReason = null
        failedStage = null
        failedAtWallTime = null
        probeAttemptCount = 0
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

            probeAttemptCount++
            currentState = Sam2GpuState.PROBING
            var probeRunner: LiteRtModelRunner? = null
            var probeStage = "compile"

            NativeDiagnostics.event(
                level = "INFO",
                component = "Sam2GpuCapabilityManager",
                event = "SAM_GPU_PROBE_START",
                fields = mapOf(
                    "model" to Sam2TensorContract.MODEL_IMAGE_FEATURES,
                    "attempt_count" to probeAttemptCount
                )
            )

            try {
                // 1. Strict GPU-only runner for image_features.tflite
                probeRunner = LiteRtModelRunner.fromAsset(
                    context = context,
                    assetPath = Sam2TensorContract.MODEL_IMAGE_FEATURES,
                    policy = LiteRtRunnerPolicy.STRICT_GPU
                )

                NativeDiagnostics.event(
                    level = "INFO",
                    component = "Sam2GpuCapabilityManager",
                    event = "SAM_GPU_ASSET_READY",
                    fields = mapOf(
                        "model" to Sam2TensorContract.MODEL_IMAGE_FEATURES
                    )
                )

                probeStage = "initialize"
                NativeDiagnostics.breadcrumb(
                    component = "SAM2",
                    stage = "SAM_GPU_COMPILE",
                    fields = mapOf("model" to Sam2TensorContract.MODEL_IMAGE_FEATURES)
                )
                NativeDiagnostics.event(
                    level = "INFO",
                    component = "Sam2GpuCapabilityManager",
                    event = "SAM_GPU_COMPILE_START",
                    fields = mapOf("model" to Sam2TensorContract.MODEL_IMAGE_FEATURES)
                )

                probeRunner.initialize()

                NativeDiagnostics.event(
                    level = "INFO",
                    component = "Sam2GpuCapabilityManager",
                    event = "SAM_GPU_COMPILE_OK",
                    fields = mapOf(
                        "model" to Sam2TensorContract.MODEL_IMAGE_FEATURES,
                        "effective_accelerator" to probeRunner.effectiveAccelerator.name,
                        "compile_ms" to (probeRunner.runtimeInfo?.compileMs ?: 0L)
                    )
                )

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

                NativeDiagnostics.event(
                    level = "INFO",
                    component = "Sam2GpuCapabilityManager",
                    event = "SAM_GPU_BUFFERS_READY",
                    fields = mapOf(
                        "input_count" to inBufs.size,
                        "output_count" to outBufs.size
                    )
                )

                // 4. Write legal [1, 3, 1024, 1024] FP32 dummy input with valid normalized floats
                probeStage = "run"
                val dummyInput = FloatArray(1 * 3 * 1024 * 1024) { 0.5f }
                inBufs[0].writeFloat(dummyInput)

                NativeDiagnostics.event(
                    level = "INFO",
                    component = "Sam2GpuCapabilityManager",
                    event = "SAM_GPU_INPUT_WRITTEN",
                    fields = mapOf("elements" to dummyInput.size)
                )

                // 5. Execute real GPU inference and measure warmup time
                NativeDiagnostics.breadcrumb(
                    component = "SAM2",
                    stage = "SAM_GPU_RUN",
                    fields = mapOf("model" to Sam2TensorContract.MODEL_IMAGE_FEATURES)
                )
                NativeDiagnostics.event(
                    level = "INFO",
                    component = "Sam2GpuCapabilityManager",
                    event = "SAM_GPU_RUN_START",
                    fields = mapOf("model" to Sam2TensorContract.MODEL_IMAGE_FEATURES)
                )

                val t0 = System.currentTimeMillis()
                probeRunner.runInference()
                val warmupMs = System.currentTimeMillis() - t0

                NativeDiagnostics.event(
                    level = "INFO",
                    component = "Sam2GpuCapabilityManager",
                    event = "SAM_GPU_RUN_OK",
                    fields = mapOf(
                        "model" to Sam2TensorContract.MODEL_IMAGE_FEATURES,
                        "warmup_ms" to warmupMs
                    )
                )

                // 6. Confirm output buffer readability
                probeStage = "readback"
                NativeDiagnostics.breadcrumb(
                    component = "SAM2",
                    stage = "SAM_GPU_READBACK",
                    fields = mapOf("model" to Sam2TensorContract.MODEL_IMAGE_FEATURES)
                )

                val out0 = outBufs[0].readFloat()
                if (out0.isEmpty()) {
                    throw IllegalStateException("SAM2 image features returned empty output float array")
                }

                NativeDiagnostics.event(
                    level = "INFO",
                    component = "Sam2GpuCapabilityManager",
                    event = "SAM_GPU_READBACK_OK",
                    fields = mapOf("out0_size" to out0.size)
                )

                val compileMs = probeRunner.runtimeInfo?.compileMs ?: 0L

                currentState = Sam2GpuState.AVAILABLE
                unavailableReason = null
                failedStage = null
                failedAtWallTime = null

                NativeDiagnostics.breadcrumb(
                    component = "SAM2",
                    stage = "SAM_GPU_AVAILABLE_DONE"
                )

                NativeDiagnostics.event(
                    level = "INFO",
                    component = "Sam2GpuCapabilityManager",
                    event = "SAM_GPU_AVAILABLE",
                    fields = mapOf(
                        "state" to "AVAILABLE",
                        "model" to "sam2_image_features.tflite",
                        "accelerator" to "GPU",
                        "compile_ms" to compileMs,
                        "warmup_ms" to warmupMs
                    )
                )

                Log.i(
                    TAG,
                    "[SAM2-GATE]\nstate=AVAILABLE\nmodel=sam2_image_features.tflite\naccelerator=GPU\ncompile_ms=$compileMs\nwarmup_ms=$warmupMs"
                )
            } catch (e: Throwable) {
                val root = NativeDiagnostics.rootCause(e)
                val reasonMsg = "${root.javaClass.simpleName}: ${root.message}"
                currentState = Sam2GpuState.UNAVAILABLE
                unavailableReason = "Stage $probeStage failed: $reasonMsg"
                failedStage = probeStage
                failedAtWallTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                NativeDiagnostics.event(
                    level = "WARN",
                    component = "Sam2GpuCapabilityManager",
                    event = "SAM_GPU_UNAVAILABLE",
                    fields = mapOf(
                        "state" to "UNAVAILABLE",
                        "stage" to probeStage,
                        "reason" to unavailableReason,
                        "attempt_count" to probeAttemptCount
                    ),
                    throwable = e
                )

                Log.w(
                    TAG,
                    "[SAM2-GATE]\nstate=UNAVAILABLE\nstage=$probeStage\nreason=$unavailableReason",
                    e
                )
            } finally {
                try {
                    probeRunner?.close()
                } catch (_: Throwable) {}
                persistCapabilities()
            }

            currentState
        }
    }

    private fun persistCapabilities() {
        NativeDiagnostics.recordCapabilities(
            mapOf(
                "state" to currentState.name,
                "is_available" to (currentState == Sam2GpuState.AVAILABLE),
                "unavailable_reason" to (unavailableReason ?: ""),
                "failed_stage" to (failedStage ?: ""),
                "failed_at_wall_time" to (failedAtWallTime ?: ""),
                "probe_attempt_count" to probeAttemptCount
            )
        )
    }
}
