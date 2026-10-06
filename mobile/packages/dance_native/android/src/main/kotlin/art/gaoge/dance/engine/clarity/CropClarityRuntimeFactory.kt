package art.gaoge.dance.engine.clarity

import android.content.Context

sealed class CropClarityRuntime : AutoCloseable {
    abstract val decision: CropClarityBackendDecision

    data class Shader(
        override val decision: CropClarityBackendDecision
    ) : CropClarityRuntime() {
        override fun close() = Unit
    }

    data class Neural(
        override val decision: CropClarityBackendDecision,
        val restorer: LiteRtCropClarityRestorer
    ) : CropClarityRuntime() {
        override fun close() = restorer.close()
    }
}

/** Initializes the neural candidate only when a packaged model accepts the GPU target. */
object CropClarityRuntimeFactory {
    suspend fun create(context: Context, requestedScale: Double): CropClarityRuntime {
        val present = LiteRtCropClarityRestorer.assetPresent(context)
        if (
            !present ||
            !requestedScale.isFinite() ||
            requestedScale < CropClarityBackendPolicy.MIN_NEURAL_SCALE
        ) {
            val decision = CropClarityBackendPolicy.decide(
                requestedScale = requestedScale,
                modelAssetPresent = present,
                gpuCompileReady = false
            )
            return CropClarityRuntime.Shader(decision)
        }

        val restorer = LiteRtCropClarityRestorer(context)
        return try {
            restorer.initialize()
            val decision = CropClarityBackendPolicy.decide(
                requestedScale = requestedScale,
                modelAssetPresent = true,
                gpuCompileReady = true
            )
            CropClarityRuntime.Neural(decision, restorer)
        } catch (_: Throwable) {
            restorer.close()
            val decision = CropClarityBackendPolicy.decide(
                requestedScale = requestedScale,
                modelAssetPresent = true,
                gpuCompileReady = false
            )
            CropClarityRuntime.Shader(decision)
        }
    }
}
