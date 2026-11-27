package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.bridge.DanceNativeException
import art.gaoge.dance.engine.litert.LiteRtAccelerator
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class ComparisonCutoutBackendTest {
    @Test fun onlyAnUnboundSelectionCanAdoptTheActualCpuFallback() {
        val gpu=LiteRtAccelerator.GPU
        val cpu=LiteRtAccelerator.CPU
        assertEquals(cpu,ComparisonCutoutBackend.resolve(gpu,cpu,true))
        assertEquals(gpu,ComparisonCutoutBackend.resolve(gpu,gpu,false))
        assertEquals(cpu,ComparisonCutoutBackend.resolve(cpu,cpu,false))
        assertEquals(ComparisonCutoutBackend.RESTART,assertFailsWith<DanceNativeException>{
            ComparisonCutoutBackend.resolve(gpu,cpu,false)
        }.code)
        assertFailsWith<DanceNativeException>{ComparisonCutoutBackend.resolve(cpu,gpu,true)}
    }
    @Test fun onlyModelFailureRequiresACompleteRestart() {
        val error=DanceNativeException(DanceNativeException.MODEL_INFERENCE_FAILED,"QA")
        assertTrue(ComparisonCutoutBackend.isModelFailure(error))
        assertTrue(ComparisonCutoutBackend.isModelFailure(IllegalStateException("wrapped",error)))
        assertFalse(ComparisonCutoutBackend.isModelFailure(IllegalArgumentException("touch")))
        assertFalse(ComparisonCutoutBackend.isModelFailure(DanceNativeException(DanceNativeException.DECODE_FRAME_FAILED,"QA")))
        assertFalse(ComparisonCutoutBackend.isModelFailure(DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,"QA",CancellationException("cancel"))))
    }
}
