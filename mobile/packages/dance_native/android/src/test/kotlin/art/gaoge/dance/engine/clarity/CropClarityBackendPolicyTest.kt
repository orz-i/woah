package art.gaoge.dance.engine.clarity

import kotlin.test.Test
import kotlin.test.assertEquals

class CropClarityBackendPolicyTest {
    @Test
    fun `scale one disables restoration even when model exists`() {
        val decision = CropClarityBackendPolicy.decide(
            requestedScale = 1.0,
            modelAssetPresent = true,
            gpuCompileReady = true
        )
        assertEquals(CropClarityBackend.OFF, decision.backend)
        assertEquals("scale_not_required", decision.reason)
    }

    @Test
    fun `mild upscale keeps deterministic shader even when neural is available`() {
        val decision = CropClarityBackendPolicy.decide(
            requestedScale = 1.18,
            modelAssetPresent = true,
            gpuCompileReady = true
        )
        assertEquals(CropClarityBackend.DETERMINISTIC_SHADER, decision.backend)
        assertEquals("scale_below_neural_threshold", decision.reason)
    }

    @Test
    fun `missing model keeps deterministic shader fallback`() {
        val decision = CropClarityBackendPolicy.decide(
            requestedScale = 1.78,
            modelAssetPresent = false,
            gpuCompileReady = false
        )
        assertEquals(CropClarityBackend.DETERMINISTIC_SHADER, decision.backend)
        assertEquals("neural_model_missing", decision.reason)
    }

    @Test
    fun `gpu compile failure keeps deterministic shader fallback`() {
        val decision = CropClarityBackendPolicy.decide(
            requestedScale = 2.0,
            modelAssetPresent = true,
            gpuCompileReady = false
        )
        assertEquals(CropClarityBackend.DETERMINISTIC_SHADER, decision.backend)
        assertEquals("gpu_compile_unavailable", decision.reason)
    }

    @Test
    fun `valid model plus gpu compile selects neural candidate`() {
        val decision = CropClarityBackendPolicy.decide(
            requestedScale = 1.5,
            modelAssetPresent = true,
            gpuCompileReady = true
        )
        assertEquals(CropClarityBackend.NEURAL_LITERT_GPU, decision.backend)
        assertEquals("gpu_compile_ready", decision.reason)
    }
}
