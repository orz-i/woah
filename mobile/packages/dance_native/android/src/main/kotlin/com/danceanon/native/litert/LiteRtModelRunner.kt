package com.danceanon.native.litert

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.danceanon.native.bridge.DanceNativeException
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LiteRtModelRunner(
    val modelName: String,
    private val modelFile: File? = null,
    private val assetPath: String? = null,
    private val assetManager: AssetManager? = null,
    val requestedAccelerator: LiteRtAccelerator = LiteRtAccelerator.GPU
) : AutoCloseable {

    private var compiledModel: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()

    var runtimeInfo: LiteRtRuntimeInfo? = null
        private set

    val effectiveAccelerator: LiteRtAccelerator
        get() = runtimeInfo?.effectiveAccelerator ?: requestedAccelerator

    fun getInputBuffers(): List<TensorBuffer> {
        checkInitialized()
        return inputBuffers
    }

    fun getOutputBuffers(): List<TensorBuffer> {
        checkInitialized()
        return outputBuffers
    }

    fun runInference() {
        checkInitialized()
        val model = compiledModel ?: throw DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,
            "[$modelName] Model runner not initialized"
        )
        model.run(inputBuffers, outputBuffers)
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (compiledModel != null) return@withContext

        val startTotalMs = System.currentTimeMillis()
        var effective = LiteRtAccelerator.CPU
        var fallbackReason: String? = null
        var compileMs = 0L
        var warmupMs = 0L

        if (requestedAccelerator == LiteRtAccelerator.GPU) {
            val compileStart = System.currentTimeMillis()
            try {
                val gpuOptions = CompiledModel.Options(setOf(Accelerator.GPU))
                val gpuModel = createCompiledModel(gpuOptions)
                val inBufs = gpuModel.createInputBuffers()
                val outBufs = gpuModel.createOutputBuffers()
                compileMs = System.currentTimeMillis() - compileStart

                warmupMs = tryWarmup(gpuModel, inBufs, outBufs)

                compiledModel = gpuModel
                inputBuffers = inBufs
                outputBuffers = outBufs
                effective = LiteRtAccelerator.GPU
            } catch (gpuEx: Throwable) {
                fallbackReason = "${gpuEx.javaClass.simpleName}: ${gpuEx.message}"
                Log.w(
                    TAG,
                    "[LiteRT] model=$modelName requested=GPU gpu_compile_failed=$fallbackReason -> falling back to CPU",
                    gpuEx
                )
                closeQuietly()
            }
        }

        if (compiledModel == null) {
            val compileStart = System.currentTimeMillis()
            try {
                val cpuOptions = CompiledModel.Options(setOf(Accelerator.CPU))
                val cpuModel = createCompiledModel(cpuOptions)
                val inBufs = cpuModel.createInputBuffers()
                val outBufs = cpuModel.createOutputBuffers()
                compileMs = System.currentTimeMillis() - compileStart

                warmupMs = tryWarmup(cpuModel, inBufs, outBufs)

                compiledModel = cpuModel
                inputBuffers = inBufs
                outputBuffers = outBufs
                effective = LiteRtAccelerator.CPU
            } catch (cpuEx: Throwable) {
                closeQuietly()
                Log.e(TAG, "[LiteRT] model=$modelName CPU initialization failed: ${cpuEx.message}", cpuEx)
                throw DanceNativeException(
                    DanceNativeException.MODEL_INIT_FAILED,
                    "Failed to initialize LiteRT model '$modelName' (GPU & CPU failed): ${cpuEx.message}",
                    cpuEx
                )
            }
        }

        val inShapes = extractInputShapes()
        val outShapes = extractOutputShapes()

        val info = LiteRtRuntimeInfo(
            modelName = modelName,
            requestedAccelerator = requestedAccelerator,
            effectiveAccelerator = effective,
            compileMs = compileMs,
            warmupMs = warmupMs,
            fallbackReason = fallbackReason,
            inputShapes = inShapes,
            outputShapes = outShapes
        )
        runtimeInfo = info

        if (effective == LiteRtAccelerator.GPU) {
            Log.i(
                TAG,
                "[LiteRT]\nmodel=$modelName\nruntime=LiteRT\nrequested=GPU\neffective=GPU\ncompile_ms=$compileMs\nwarmup_ms=$warmupMs\ninputs=$inShapes\noutputs=$outShapes"
            )
        } else {
            Log.i(
                TAG,
                "[LiteRT]\nmodel=$modelName\nruntime=LiteRT\nrequested=$requestedAccelerator\ngpu_compile_failed=${fallbackReason ?: "N/A"}\neffective=CPU\ncpu_compile_ms=$compileMs\nwarmup_ms=$warmupMs\ninputs=$inShapes\noutputs=$outShapes"
            )
        }
    }

    private fun tryWarmup(model: CompiledModel, inBufs: List<TensorBuffer>, outBufs: List<TensorBuffer>): Long {
        return try {
            val t0 = System.currentTimeMillis()
            model.run(inBufs, outBufs)
            System.currentTimeMillis() - t0
        } catch (warmupEx: Throwable) {
            Log.w(TAG, "[LiteRT] model=$modelName warmup skipped (${warmupEx.message})")
            0L
        }
    }

    private fun createCompiledModel(options: CompiledModel.Options): CompiledModel {
        return when {
            modelFile != null && modelFile.exists() && modelFile.length() > 0L -> {
                CompiledModel.create(modelFile.absolutePath, options)
            }
            assetManager != null && !assetPath.isNullOrEmpty() -> {
                CompiledModel.create(assetManager, assetPath, options)
            }
            else -> {
                throw DanceNativeException(
                    DanceNativeException.MODEL_NOT_FOUND,
                    "No valid model source provided for LiteRT runner '$modelName'"
                )
            }
        }
    }

    private fun extractInputShapes(): List<List<Int>> {
        return try {
            val count = inputBuffers.size
            (0 until count).map { idx ->
                // Try signature default if available
                emptyList<Int>()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun extractOutputShapes(): List<List<Int>> {
        return try {
            val count = outputBuffers.size
            (0 until count).map { idx ->
                emptyList<Int>()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun checkInitialized() {
        if (compiledModel == null) {
            throw DanceNativeException(
                DanceNativeException.MODEL_INIT_FAILED,
                "[$modelName] Model runner is not initialized. Call initialize() first."
            )
        }
    }

    private fun closeQuietly() {
        for (b in inputBuffers) {
            try { b.close() } catch (_: Throwable) {}
        }
        inputBuffers = emptyList()
        for (b in outputBuffers) {
            try { b.close() } catch (_: Throwable) {}
        }
        outputBuffers = emptyList()
        try { compiledModel?.close() } catch (_: Throwable) {}
        compiledModel = null
    }

    override fun close() {
        closeQuietly()
        runtimeInfo = null
    }

    companion object {
        private const val TAG = "LiteRtModelRunner"

        fun fromAsset(
            context: Context,
            assetPath: String,
            modelName: String = File(assetPath).name,
            requestedAccelerator: LiteRtAccelerator = LiteRtAccelerator.GPU
        ): LiteRtModelRunner {
            return LiteRtModelRunner(
                modelName = modelName,
                assetPath = assetPath,
                assetManager = context.assets,
                requestedAccelerator = requestedAccelerator
            )
        }

        fun fromFile(
            modelFile: File,
            modelName: String = modelFile.name,
            requestedAccelerator: LiteRtAccelerator = LiteRtAccelerator.GPU
        ): LiteRtModelRunner {
            return LiteRtModelRunner(
                modelName = modelName,
                modelFile = modelFile,
                requestedAccelerator = requestedAccelerator
            )
        }
    }
}
