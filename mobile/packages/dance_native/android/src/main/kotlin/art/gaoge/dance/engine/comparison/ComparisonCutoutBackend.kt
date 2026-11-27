package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.bridge.DanceNativeException
import art.gaoge.dance.engine.litert.LiteRtAccelerator
import kotlinx.coroutines.CancellationException

/** Model backend is part of the identity evidence, not a per-frame preference. */
internal object ComparisonCutoutBackend {
    const val RESTART = "CUTOUT_BACKEND_RESTART"
    fun configured(debug:Boolean,debugGpuQa:Boolean,releaseCanvas:Boolean,releaseGpu:Boolean):LiteRtAccelerator =
        if ((debug && debugGpuQa) || (!debug && releaseCanvas && releaseGpu))
            LiteRtAccelerator.GPU else LiteRtAccelerator.CPU

    fun acceptsBothArchives(debug:Boolean,releaseCanvas:Boolean):Boolean = !debug && releaseCanvas

    fun requiresFullReselection(locked:LiteRtAccelerator?,preferred:LiteRtAccelerator):Boolean =
        locked==LiteRtAccelerator.GPU && preferred==LiteRtAccelerator.CPU
    fun restart() = DanceNativeException(RESTART, "Restart the complete canvas selection on CPU4")
    fun resolve(requested:LiteRtAccelerator,effective:LiteRtAccelerator,
                allowUnboundFallback:Boolean):LiteRtAccelerator {
        if(effective==requested)return effective
        if(allowUnboundFallback && requested==LiteRtAccelerator.GPU && effective==LiteRtAccelerator.CPU)
            return effective
        throw restart()
    }
    // Ignore wrapped cancellation and non-model failures. A decode failure,
    // missing identity, storage failure or invalid UI request is not a
    // reason to silently change accelerators.
    fun isModelFailure(error:Throwable):Boolean {
        val causes=generateSequence(error){it.cause?.takeUnless{cause->cause===it}}.take(16).toList()
        if(causes.any{it is CancellationException || it is InterruptedException})return false
        return causes.any{it is DanceNativeException && it.code in setOf(
            RESTART,DanceNativeException.MODEL_INIT_FAILED,
            DanceNativeException.MODEL_INFERENCE_FAILED,DanceNativeException.MODEL_OUTPUT_INVALID)}
    }
}
