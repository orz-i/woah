package com.danceanon.native.sam2

import android.content.Context
import android.util.Log
import com.danceanon.native.bridge.DanceNativeException
import com.danceanon.native.litert.LiteRtAccelerator
import com.danceanon.native.litert.LiteRtModelRunner
import com.danceanon.native.litert.LiteRtRunnerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Encapsulates the 3 App-bundled LiteRT models required for SAM2 Hiera Tiny tracking.
 * Production policy:
 * - SAM2 image_features MUST use LiteRT GPU (strict GPU requirement).
 * - SAM2 init_step uses LiteRT CPU.
 * - SAM2 temporal_step uses LiteRT CPU.
 */
class Sam2LiteRtModelBundle(
    val imageFeaturesRunner: LiteRtModelRunner,
    val initStepRunner: LiteRtModelRunner,
    val temporalStepRunner: LiteRtModelRunner
) : AutoCloseable {

    override fun close() {
        try { imageFeaturesRunner.close() } catch (_: Throwable) {}
        try { initStepRunner.close() } catch (_: Throwable) {}
        try { temporalStepRunner.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "Sam2LiteRtModelBundle"

        fun loadFromAssets(
            context: Context
        ): Sam2LiteRtModelBundle = runBlocking(Dispatchers.IO) {
            loadFromAssetsAsync(context)
        }

        suspend fun loadFromAssetsAsync(
            context: Context
        ): Sam2LiteRtModelBundle = withContext(Dispatchers.IO) {
            var imgRunner: LiteRtModelRunner? = null
            var initRunner: LiteRtModelRunner? = null
            var tempRunner: LiteRtModelRunner? = null

            try {
                // 1. image_features MUST be strict GPU
                imgRunner = LiteRtModelRunner.fromAsset(
                    context = context,
                    assetPath = Sam2TensorContract.MODEL_IMAGE_FEATURES,
                    policy = LiteRtRunnerPolicy.STRICT_GPU
                )
                imgRunner.initialize()

                if (imgRunner.effectiveAccelerator != LiteRtAccelerator.GPU) {
                    throw DanceNativeException(
                        DanceNativeException.SAM2_GPU_UNAVAILABLE,
                        "SAM2 requires a verified LiteRT GPU accelerator on this device (image_features effective accelerator was )"
                    )
                }

                // 2. init_step is CPU
                initRunner = LiteRtModelRunner.fromAsset(
                    context = context,
                    assetPath = Sam2TensorContract.MODEL_INIT_STEP,
                    policy = LiteRtRunnerPolicy.STRICT_CPU
                )
                initRunner.initialize()

                // 3. temporal_step is CPU
                tempRunner = LiteRtModelRunner.fromAsset(
                    context = context,
                    assetPath = Sam2TensorContract.MODEL_TEMPORAL_STEP,
                    policy = LiteRtRunnerPolicy.STRICT_CPU
                )
                tempRunner.initialize()

                Log.i(
                    TAG,
                    "[SAM2]\nimage_features=GPU\ninit_step=CPU\ntemporal_step=CPU"
                )

                Sam2LiteRtModelBundle(
                    imageFeaturesRunner = imgRunner,
                    initStepRunner = initRunner,
                    temporalStepRunner = tempRunner
                )
            } catch (e: Throwable) {
                try { imgRunner?.close() } catch (_: Throwable) {}
                try { initRunner?.close() } catch (_: Throwable) {}
                try { tempRunner?.close() } catch (_: Throwable) {}

                Sam2GpuCapabilityManager.markUnavailable("Bundle init failed: ")

                if (e is DanceNativeException && e.code == DanceNativeException.SAM2_GPU_UNAVAILABLE) {
                    throw e
                }

                throw DanceNativeException(
                    DanceNativeException.SAM2_GPU_UNAVAILABLE,
                    "SAM2 requires a verified LiteRT GPU accelerator on this device: ",
                    e
                )
            }
        }

        fun loadFromDirectory(
            modelsDir: File
        ): Sam2LiteRtModelBundle = runBlocking(Dispatchers.IO) {
            loadFromDirectoryAsync(modelsDir)
        }

        suspend fun loadFromDirectoryAsync(
            modelsDir: File
        ): Sam2LiteRtModelBundle = withContext(Dispatchers.IO) {
            val imgFile = File(modelsDir, File(Sam2TensorContract.MODEL_IMAGE_FEATURES).name)
            val initFile = File(modelsDir, File(Sam2TensorContract.MODEL_INIT_STEP).name)
            val tempFile = File(modelsDir, File(Sam2TensorContract.MODEL_TEMPORAL_STEP).name)

            var imgRunner: LiteRtModelRunner? = null
            var initRunner: LiteRtModelRunner? = null
            var tempRunner: LiteRtModelRunner? = null

            try {
                imgRunner = LiteRtModelRunner.fromFile(
                    modelFile = imgFile,
                    policy = LiteRtRunnerPolicy.STRICT_GPU
                )
                imgRunner.initialize()

                if (imgRunner.effectiveAccelerator != LiteRtAccelerator.GPU) {
                    throw DanceNativeException(
                        DanceNativeException.SAM2_GPU_UNAVAILABLE,
                        "SAM2 requires a verified LiteRT GPU accelerator on this device (image_features effective accelerator was )"
                    )
                }

                initRunner = LiteRtModelRunner.fromFile(
                    modelFile = initFile,
                    policy = LiteRtRunnerPolicy.STRICT_CPU
                )
                initRunner.initialize()

                tempRunner = LiteRtModelRunner.fromFile(
                    modelFile = tempFile,
                    policy = LiteRtRunnerPolicy.STRICT_CPU
                )
                tempRunner.initialize()

                Log.i(
                    TAG,
                    "[SAM2]\nimage_features=GPU\ninit_step=CPU\ntemporal_step=CPU"
                )

                Sam2LiteRtModelBundle(
                    imageFeaturesRunner = imgRunner,
                    initStepRunner = initRunner,
                    temporalStepRunner = tempRunner
                )
            } catch (e: Throwable) {
                try { imgRunner?.close() } catch (_: Throwable) {}
                try { initRunner?.close() } catch (_: Throwable) {}
                try { tempRunner?.close() } catch (_: Throwable) {}

                Sam2GpuCapabilityManager.markUnavailable("Bundle file init failed: ")

                if (e is DanceNativeException && e.code == DanceNativeException.SAM2_GPU_UNAVAILABLE) {
                    throw e
                }

                throw DanceNativeException(
                    DanceNativeException.SAM2_GPU_UNAVAILABLE,
                    "SAM2 requires a verified LiteRT GPU accelerator on this device: ",
                    e
                )
            }
        }
    }
}
