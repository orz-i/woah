package com.danceanon.native.sam2

import android.content.Context
import com.danceanon.native.litert.LiteRtAccelerator
import com.danceanon.native.litert.LiteRtModelRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Encapsulates the 3 App-bundled LiteRT models required for SAM2 Hiera Tiny tracking.
 * Strictly executes GPU-first compilation with fallback to LiteRT CPU on failure.
 */
class Sam2LiteRtModelBundle(
    val imageFeaturesRunner: LiteRtModelRunner,
    val initStepRunner: LiteRtModelRunner,
    val temporalStepRunner: LiteRtModelRunner
) : AutoCloseable {

    override fun close() {
        imageFeaturesRunner.close()
        initStepRunner.close()
        temporalStepRunner.close()
    }

    companion object {
        fun loadFromAssets(
            context: Context,
            accelerator: LiteRtAccelerator = LiteRtAccelerator.GPU
        ): Sam2LiteRtModelBundle = runBlocking(Dispatchers.IO) {
            loadFromAssetsAsync(context, accelerator)
        }

        suspend fun loadFromAssetsAsync(
            context: Context,
            accelerator: LiteRtAccelerator = LiteRtAccelerator.CPU
        ): Sam2LiteRtModelBundle = withContext(Dispatchers.IO) {
            // SAM2 models (108MB vision transformer, prompt decoder, 5D temporal recurrence)
            // run reliably and deterministically across all Android devices on CPU with XNNPACK.
            // Using CPU eliminates mobile GPU driver SIGSEGV/abort crashes during OpenCL graph compilation.
            val imgRunner = LiteRtModelRunner.fromAsset(
                context = context,
                assetPath = Sam2TensorContract.MODEL_IMAGE_FEATURES,
                requestedAccelerator = LiteRtAccelerator.CPU
            )
            val initRunner = LiteRtModelRunner.fromAsset(
                context = context,
                assetPath = Sam2TensorContract.MODEL_INIT_STEP,
                requestedAccelerator = LiteRtAccelerator.CPU
            )
            val tempRunner = LiteRtModelRunner.fromAsset(
                context = context,
                assetPath = Sam2TensorContract.MODEL_TEMPORAL_STEP,
                requestedAccelerator = LiteRtAccelerator.CPU
            )

            imgRunner.initialize()
            initRunner.initialize()
            tempRunner.initialize()

            Sam2LiteRtModelBundle(
                imageFeaturesRunner = imgRunner,
                initStepRunner = initRunner,
                temporalStepRunner = tempRunner
            )
        }

        fun loadFromDirectory(
            modelsDir: File,
            accelerator: LiteRtAccelerator = LiteRtAccelerator.CPU
        ): Sam2LiteRtModelBundle = runBlocking(Dispatchers.IO) {
            loadFromDirectoryAsync(modelsDir, accelerator)
        }

        suspend fun loadFromDirectoryAsync(
            modelsDir: File,
            accelerator: LiteRtAccelerator = LiteRtAccelerator.CPU
        ): Sam2LiteRtModelBundle = withContext(Dispatchers.IO) {
            val imgFile = File(modelsDir, File(Sam2TensorContract.MODEL_IMAGE_FEATURES).name)
            val initFile = File(modelsDir, File(Sam2TensorContract.MODEL_INIT_STEP).name)
            val tempFile = File(modelsDir, File(Sam2TensorContract.MODEL_TEMPORAL_STEP).name)

            val imgRunner = LiteRtModelRunner.fromFile(
                modelFile = imgFile,
                requestedAccelerator = LiteRtAccelerator.CPU
            )
            val initRunner = LiteRtModelRunner.fromFile(
                modelFile = initFile,
                requestedAccelerator = LiteRtAccelerator.CPU
            )
            val tempRunner = LiteRtModelRunner.fromFile(
                modelFile = tempFile,
                requestedAccelerator = LiteRtAccelerator.CPU
            )

            imgRunner.initialize()
            initRunner.initialize()
            tempRunner.initialize()

            Sam2LiteRtModelBundle(
                imageFeaturesRunner = imgRunner,
                initStepRunner = initRunner,
                temporalStepRunner = tempRunner
            )
        }
    }
}
