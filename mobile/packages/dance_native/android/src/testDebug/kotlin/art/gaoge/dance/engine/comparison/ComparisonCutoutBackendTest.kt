package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.bridge.DanceNativeException
import art.gaoge.dance.engine.litert.LiteRtAccelerator
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class ComparisonCutoutBackendTest {
    @Test fun releasePolicyIsPackageIndependentGpuFirstWithExplicitRollback() {
        val gpu=LiteRtAccelerator.GPU;val cpu=LiteRtAccelerator.CPU
        assertEquals(gpu,ComparisonCutoutBackend.configured(false,false,true,true))
        assertEquals(cpu,ComparisonCutoutBackend.configured(false,true,true,false))
        assertEquals(cpu,ComparisonCutoutBackend.configured(false,true,false,true))
        assertEquals(cpu,ComparisonCutoutBackend.configured(true,false,true,true))
        assertEquals(gpu,ComparisonCutoutBackend.configured(true,true,false,false))
        assertTrue(ComparisonCutoutBackend.acceptsBothArchives(false,true))
        assertFalse(ComparisonCutoutBackend.acceptsBothArchives(true,true))
        assertFalse(ComparisonCutoutBackend.acceptsBothArchives(false,false))
        // A GPU archive is still playable after a CPU rollback, but cannot
        // acquire CPU observations in its local repair.
        assertTrue(ComparisonCutoutBackend.requiresFullReselection(gpu,cpu))
        assertFalse(ComparisonCutoutBackend.requiresFullReselection(cpu,gpu))
        assertFalse(ComparisonCutoutBackend.requiresFullReselection(null,cpu))
    }
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
