package art.gaoge.dance.engine.clarity

/** Runtime backend selected for crop clarity restoration. */
enum class CropClarityBackend {
    OFF,
    DETERMINISTIC_SHADER,
    NEURAL_LITERT_GPU
}

data class CropClarityBackendDecision(
    val backend: CropClarityBackend,
    val reason: String
)

/**
 * Pure policy kept separate from runtime initialization so it can be regression
 * tested without Android or LiteRT native state.
 */
object CropClarityBackendPolicy {
    const val MIN_NEURAL_SCALE = 1.25

    fun decide(
        requestedScale: Double,
        modelAssetPresent: Boolean,
        gpuCompileReady: Boolean
    ): CropClarityBackendDecision {
        if (!requestedScale.isFinite() || requestedScale <= 1.001) {
            return CropClarityBackendDecision(CropClarityBackend.OFF, "scale_not_required")
        }
        if (requestedScale < MIN_NEURAL_SCALE) {
            return CropClarityBackendDecision(
                CropClarityBackend.DETERMINISTIC_SHADER,
                "scale_below_neural_threshold"
            )
        }
        if (!modelAssetPresent) {
            return CropClarityBackendDecision(
                CropClarityBackend.DETERMINISTIC_SHADER,
                "neural_model_missing"
            )
        }
        if (!gpuCompileReady) {
            return CropClarityBackendDecision(
                CropClarityBackend.DETERMINISTIC_SHADER,
                "gpu_compile_unavailable"
            )
        }
        return CropClarityBackendDecision(
            CropClarityBackend.NEURAL_LITERT_GPU,
            "gpu_compile_ready"
        )
    }
}
