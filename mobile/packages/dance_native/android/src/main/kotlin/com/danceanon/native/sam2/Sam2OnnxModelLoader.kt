package com.danceanon.native.sam2

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Loader for SAM2 ONNX session bundle.
 */
object Sam2OnnxModelLoader {

    fun loadFromDirectory(modelsDir: File): Sam2OnnxSessionBundle {
        val imgFeatFile = File(modelsDir, Sam2TensorContract.MODEL_IMAGE_FEATURES)
        val initStepFile = File(modelsDir, Sam2TensorContract.MODEL_INIT_STEP)
        val tempStepFile = File(modelsDir, Sam2TensorContract.MODEL_TEMPORAL_STEP)

        if (!imgFeatFile.exists()) {
            throw java.io.FileNotFoundException("Missing model: ${imgFeatFile.absolutePath}")
        }
        if (!initStepFile.exists()) {
            throw java.io.FileNotFoundException("Missing model: ${initStepFile.absolutePath}")
        }
        if (!tempStepFile.exists()) {
            throw java.io.FileNotFoundException("Missing model: ${tempStepFile.absolutePath}")
        }

        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
        }

        val sessImg = env.createSession(imgFeatFile.absolutePath, sessionOptions)
        val sessInit = env.createSession(initStepFile.absolutePath, sessionOptions)
        val sessTemp = env.createSession(tempStepFile.absolutePath, sessionOptions)

        return Sam2OnnxSessionBundle(
            env = env,
            imageFeaturesSession = sessImg,
            initStepSession = sessInit,
            temporalStepSession = sessTemp
        )
    }

    fun loadFromAssets(context: Context, assetPrefix: String = "models/sam2_onnx"): Sam2OnnxSessionBundle {
        val cacheDir = File(context.filesDir, "models/sam2_onnx")
        cacheDir.mkdirs()

        val models = listOf(
            Sam2TensorContract.MODEL_IMAGE_FEATURES,
            Sam2TensorContract.MODEL_INIT_STEP,
            Sam2TensorContract.MODEL_TEMPORAL_STEP
        )

        for (modelName in models) {
            val dest = File(cacheDir, modelName)
            if (!dest.exists() || dest.length() == 0L) {
                val candidatePaths = listOfNotNull(
                    if (assetPrefix.isNotEmpty()) "$assetPrefix/$modelName" else null,
                    "models/sam2_onnx/$modelName",
                    "sam2_onnx/$modelName",
                    "models/$modelName",
                    modelName
                ).distinct()

                var copied = false
                var lastErr: Throwable? = null
                for (cand in candidatePaths) {
                    try {
                        context.assets.open(cand).use { input ->
                            FileOutputStream(dest).use { output ->
                                input.copyTo(output)
                            }
                        }
                        copied = true
                        break
                    } catch (e: Throwable) {
                        lastErr = e
                    }
                }

                if (!copied || dest.length() == 0L) {
                    throw java.io.FileNotFoundException(
                        "Required SAM2 ONNX model '$modelName' not found in assets (${candidatePaths.joinToString()}). " +
                        "Please ensure sam2_image_features.onnx, sam2_init_step.onnx, and sam2_temporal_step.onnx " +
                        "are placed in src/main/assets/models/sam2_onnx/. Underlying error: ${lastErr?.message}"
                    )
                }
            }
        }

        return loadFromDirectory(cacheDir)
    }
}

