package art.gaoge.dance.engine.clarity

import android.content.Context
import art.gaoge.dance.engine.litert.LiteRtAccelerator
import art.gaoge.dance.engine.litert.LiteRtModelRunner
import art.gaoge.dance.engine.litert.LiteRtRunnerPolicy

/**
 * GPU-targeted neural x2 tile runner.
 *
 * Woah requests only the GPU accelerator and never retries the neural path on
 * CPU. LiteRT CompiledModel may still apply internal fallback/partitioning, so
 * initialization success alone is not proof of full GPU residency. The static
 * op allowlist plus a real-device profiler gate are both required before this
 * backend can be promoted into production export.
 *
 * This class deliberately exposes RGB float tiles rather than doing GL readback.
 * Kotlin TensorBuffer exposes typed read/write only; production export must use
 * the C++ zero-copy GL texture/buffer interop through a small JNI bridge instead
 * of glReadPixels -> FloatArray -> writeFloat per tile.
 */
class LiteRtCropClarityRestorer(
    context: Context
) : AutoCloseable {
    private val runner = LiteRtModelRunner(
        modelName = CropClarityModelContract.MODEL_NAME,
        assetPath = CropClarityModelContract.ASSET_PATH,
        assetManager = context.assets,
        policy = LiteRtRunnerPolicy(
            requestedAccelerator = LiteRtAccelerator.GPU,
            allowCpuFallback = false,
            requireWarmupSuccess = true
        )
    )

    var initialized: Boolean = false
        private set

    suspend fun initialize() {
        runner.initialize()
        check(runner.effectiveAccelerator == LiteRtAccelerator.GPU) {
            "Crop clarity neural backend failed to initialize with the requested GPU target"
        }
        val inputs = runner.getInputBuffers()
        val outputs = runner.getOutputBuffers()
        check(inputs.size == 1 && outputs.size == 1) {
            "Expected one crop-clarity input/output tensor, got ${inputs.size}/${outputs.size}"
        }
        initialized = true
    }

    fun restoreTileRgb(input: FloatArray): FloatArray {
        check(initialized) { "Call initialize() before crop-clarity inference" }
        require(input.size == CropClarityModelContract.INPUT_FLOAT_COUNT) {
            "Expected ${CropClarityModelContract.INPUT_FLOAT_COUNT} RGB floats, got ${input.size}"
        }
        val inputBuffer = runner.getInputBuffers().single()
        val outputBuffer = runner.getOutputBuffers().single()
        inputBuffer.writeFloat(input)
        runner.runInference()
        val output = outputBuffer.readFloat()
        check(output.size == CropClarityModelContract.OUTPUT_FLOAT_COUNT) {
            "Expected ${CropClarityModelContract.OUTPUT_FLOAT_COUNT} output floats, got ${output.size}"
        }
        return output
    }

    override fun close() {
        initialized = false
        runner.close()
    }

    companion object {
        fun assetPresent(context: Context): Boolean = runCatching {
            context.assets.open(CropClarityModelContract.ASSET_PATH).use { stream ->
                stream.read() >= 0
            }
        }.getOrDefault(false)
    }
}
