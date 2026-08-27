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
        var sessionOptions = createOptimalSessionOptions(4)

        try {
            println("[Loader] Loading image_features: ${imgFeatFile.absolutePath} (${imgFeatFile.length() / 1024 / 1024} MB)...")
            val sessImg = env.createSession(imgFeatFile.absolutePath, sessionOptions)
            println("[Loader] Loading init_step: ${initStepFile.absolutePath} (${initStepFile.length() / 1024 / 1024} MB)...")
            val sessInit = env.createSession(initStepFile.absolutePath, sessionOptions)
            println("[Loader] Loading temporal_step: ${tempStepFile.absolutePath} (${tempStepFile.length() / 1024 / 1024} MB)...")
            val sessTemp = env.createSession(tempStepFile.absolutePath, sessionOptions)
            println("[Loader] All 3 ONNX sessions loaded successfully!")

            return Sam2OnnxSessionBundle(
                env = env,
                imageFeaturesSession = sessImg,
                initStepSession = sessInit,
                temporalStepSession = sessTemp
            )
        } catch (e: Throwable) {
            println("[Loader] Preferred session creation failed (${e.message}), trying standard CPU fallback...")
            try {
                sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setIntraOpNumThreads(4)
                }
                val sessImg = env.createSession(imgFeatFile.absolutePath, sessionOptions)
                val sessInit = env.createSession(initStepFile.absolutePath, sessionOptions)
                val sessTemp = env.createSession(tempStepFile.absolutePath, sessionOptions)
                println("[Loader] All 3 ONNX sessions loaded successfully via CPU fallback!")

                return Sam2OnnxSessionBundle(
                    env = env,
                    imageFeaturesSession = sessImg,
                    initStepSession = sessInit,
                    temporalStepSession = sessTemp
                )
            } catch (fallbackErr: Throwable) {
                println("[Loader] ERROR: ${fallbackErr.message}")
                throw IllegalStateException("Failed to create ONNX session for models in ${modelsDir.absolutePath}: ${fallbackErr.message}", fallbackErr)
            }
        }
    }

    fun createOptimalSessionOptions(numThreads: Int = 4): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setIntraOpNumThreads(numThreads)
        }

        try {
            val qnnMethod = options.javaClass.methods.firstOrNull { it.name == "addQnn" }
            if (qnnMethod != null) {
                val qnnOptions = mapOf(
                    "backend_type" to "HTP",
                    "htp_performance_mode" to "burst",
                    "htp_precision" to "fp16"
                )
                qnnMethod.invoke(options, qnnOptions)
                println("[Loader] ✅ Qualcomm QNN Execution Provider (HTP) configured")
            }
        } catch (t: Throwable) {
            println("[Loader] QNN EP probe note: ${t.message}")
        }

        return options
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

