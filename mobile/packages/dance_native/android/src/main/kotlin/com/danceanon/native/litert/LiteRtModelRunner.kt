package com.danceanon.native.litert

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.danceanon.native.bridge.DanceNativeException
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class LiteRtModelRunner(
    val modelName: String,
    private val modelFile: File? = null,
    private val assetPath: String? = null,
    private val assetManager: AssetManager? = null,
    val policy: LiteRtRunnerPolicy = LiteRtRunnerPolicy.STRICT_GPU
) : AutoCloseable {

    constructor(
        modelName: String,
        modelFile: File? = null,
        assetPath: String? = null,
        assetManager: AssetManager? = null,
        requestedAccelerator: LiteRtAccelerator
    ) : this(
        modelName = modelName,
        modelFile = modelFile,
        assetPath = assetPath,
        assetManager = assetManager,
        policy = if (requestedAccelerator == LiteRtAccelerator.GPU) {
            LiteRtRunnerPolicy.GPU_WITH_CPU_FALLBACK
        } else {
            LiteRtRunnerPolicy.STRICT_CPU
        }
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LiteRtRunner-").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var compiledModel: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()

    var runtimeInfo: LiteRtRuntimeInfo? = null
        private set

    val requestedAccelerator: LiteRtAccelerator
        get() = policy.requestedAccelerator

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
        executor.submit(Callable {
            val model = compiledModel ?: throw DanceNativeException(
                DanceNativeException.MODEL_INIT_FAILED,
                "Model runner not initialized for '$modelName'"
            )
            model.run(inputBuffers, outputBuffers)
        }).get()
    }

    suspend fun initialize() = withContext(dispatcher) {
        if (compiledModel != null) return@withContext

        var effective = LiteRtAccelerator.CPU
        var fallbackReason: String? = null
        var compileMs = 0L
        var warmupMs = 0L

        if (policy.requestedAccelerator == LiteRtAccelerator.GPU) {
            val compileStart = System.currentTimeMillis()
            var gpuModel: CompiledModel? = null
            var inBufs: List<TensorBuffer> = emptyList()
            var outBufs: List<TensorBuffer> = emptyList()
            try {
                val gpuOptions = CompiledModel.Options(setOf(Accelerator.GPU))
                gpuModel = createCompiledModel(gpuOptions)
                inBufs = gpuModel.createInputBuffers()
                outBufs = gpuModel.createOutputBuffers()
                compileMs = System.currentTimeMillis() - compileStart

                if (policy.requireWarmupSuccess) {
                    val t0 = System.currentTimeMillis()
                    gpuModel.run(inBufs, outBufs)
                    warmupMs = System.currentTimeMillis() - t0
                }

                compiledModel = gpuModel
                inputBuffers = inBufs
                outputBuffers = outBufs
                effective = LiteRtAccelerator.GPU
            } catch (gpuEx: Throwable) {
                fallbackReason = "${gpuEx.javaClass.simpleName}: ${gpuEx.message}"
                closeResources(gpuModel, inBufs, outBufs)
                if (!policy.allowCpuFallback) {
                    Log.e(
                        TAG,
                        "[LiteRT] model=$modelName requested=GPU strict GPU initialization failed: ${gpuEx.message}",
                        gpuEx
                    )
                    throw DanceNativeException(
                        DanceNativeException.MODEL_INIT_FAILED,
                        "Failed strict GPU initialization for LiteRT model '$modelName': ${gpuEx.message}",
                        gpuEx
                    )
                } else {
                    Log.w(
                        TAG,
                        "[LiteRT] model=$modelName requested=GPU gpu_compile_failed=$fallbackReason -> falling back to CPU",
                        gpuEx
                    )
                }
            }
        }

        if (compiledModel == null) {
            val compileStart = System.currentTimeMillis()
            var cpuModel: CompiledModel? = null
            var inBufs: List<TensorBuffer> = emptyList()
            var outBufs: List<TensorBuffer> = emptyList()
            try {
                val cpuOptions = CompiledModel.Options(setOf(Accelerator.CPU))
                cpuModel = createCompiledModel(cpuOptions)
                inBufs = cpuModel.createInputBuffers()
                outBufs = cpuModel.createOutputBuffers()
                compileMs = System.currentTimeMillis() - compileStart

                if (policy.requireWarmupSuccess) {
                    val t0 = System.currentTimeMillis()
                    cpuModel.run(inBufs, outBufs)
                    warmupMs = System.currentTimeMillis() - t0
                }

                compiledModel = cpuModel
                inputBuffers = inBufs
                outputBuffers = outBufs
                effective = LiteRtAccelerator.CPU
            } catch (cpuEx: Throwable) {
                closeResources(cpuModel, inBufs, outBufs)
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
            requestedAccelerator = policy.requestedAccelerator,
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
                "[LiteRT]\nmodel=$modelName\nruntime=LiteRT\nrequested=${policy.requestedAccelerator}\ngpu_compile_failed=$fallbackReason\neffective=CPU\ncpu_compile_ms=$compileMs\nwarmup_ms=$warmupMs\ninputs=$inShapes\noutputs=$outShapes"
            )
        }
    }

    private fun createCompiledModel(options: CompiledModel.Options): CompiledModel {
        if (modelFile != null && modelFile.exists() && modelFile.length() > 0L) {
            return CompiledModel.create(modelFile.absolutePath, options)
        }
        if (assetManager != null && !assetPath.isNullOrEmpty()) {
            return CompiledModel.create(assetManager, assetPath, options)
        }
        throw DanceNativeException(
            DanceNativeException.MODEL_NOT_FOUND,
            "No valid model source provided for LiteRT runner '$modelName'"
        )
    }

    private fun extractInputShapes(): List<List<Int>> {
        return try {
            val count = inputBuffers.size
            (0 until count).map { idx ->
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
                "Model runner is not initialized for '$modelName'. Call initialize() first."
            )
        }
    }

    private fun closeResources(
        model: CompiledModel?,
        inBufs: List<TensorBuffer>,
        outBufs: List<TensorBuffer>
    ) {
        for (b in inBufs) {
            try { b.close() } catch (_: Throwable) {}
        }
        for (b in outBufs) {
            try { b.close() } catch (_: Throwable) {}
        }
        try { model?.close() } catch (_: Throwable) {}
    }

    private fun closeQuietly() {
        closeResources(compiledModel, inputBuffers, outputBuffers)
        compiledModel = null
        inputBuffers = emptyList()
        outputBuffers = emptyList()
    }

    override fun close() {
        try {
            executor.submit {
                closeQuietly()
                runtimeInfo = null
            }.get()
        } catch (_: Throwable) {
            closeQuietly()
            runtimeInfo = null
        } finally {
            executor.shutdown()
        }
    }

    companion object {
        private const val TAG = "LiteRtModelRunner"

        fun ensureAssetExtracted(context: Context, assetPath: String): File {
            val modelsDir = File(context.filesDir, "litert_models").apply { mkdirs() }
            val fileName = File(assetPath).name
            val targetFile = File(modelsDir, fileName)

            try {
                val assetFd = try { context.assets.openFd(assetPath) } catch (_: Throwable) { null }
                val assetLen = assetFd?.length ?: -1L
                assetFd?.close()

                if (!targetFile.exists() || (assetLen > 0 && targetFile.length() != assetLen) || targetFile.length() == 0L) {
                    val tempFile = File(modelsDir, "${fileName}.tmp")
                    context.assets.open(assetPath).use { input ->
                        java.io.FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.exists() && tempFile.length() > 0L) {
                        if (targetFile.exists()) targetFile.delete()
                        tempFile.renameTo(targetFile)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Asset extraction to disk info for '$assetPath': ${t.message}")
            }

            return if (targetFile.exists() && targetFile.length() > 0L) targetFile else File(assetPath)
        }

        fun fromAsset(
            context: Context,
            assetPath: String,
            modelName: String = File(assetPath).name,
            policy: LiteRtRunnerPolicy = LiteRtRunnerPolicy.STRICT_GPU
        ): LiteRtModelRunner {
            val extracted = ensureAssetExtracted(context, assetPath)
            return if (extracted.exists() && extracted.length() > 0L) {
                LiteRtModelRunner(
                    modelName = modelName,
                    modelFile = extracted,
                    assetPath = assetPath,
                    assetManager = context.assets,
                    policy = policy
                )
            } else {
                LiteRtModelRunner(
                    modelName = modelName,
                    assetPath = assetPath,
                    assetManager = context.assets,
                    policy = policy
                )
            }
        }

        fun fromAsset(
            context: Context,
            assetPath: String,
            modelName: String = File(assetPath).name,
            requestedAccelerator: LiteRtAccelerator
        ): LiteRtModelRunner {
            val extracted = ensureAssetExtracted(context, assetPath)
            return if (extracted.exists() && extracted.length() > 0L) {
                LiteRtModelRunner(
                    modelName = modelName,
                    modelFile = extracted,
                    assetPath = assetPath,
                    assetManager = context.assets,
                    requestedAccelerator = requestedAccelerator
                )
            } else {
                LiteRtModelRunner(
                    modelName = modelName,
                    assetPath = assetPath,
                    assetManager = context.assets,
                    requestedAccelerator = requestedAccelerator
                )
            }
        }

        fun fromFile(
            modelFile: File,
            modelName: String = modelFile.name,
            policy: LiteRtRunnerPolicy = LiteRtRunnerPolicy.STRICT_GPU
        ): LiteRtModelRunner {
            return LiteRtModelRunner(
                modelName = modelName,
                modelFile = modelFile,
                policy = policy
            )
        }

        fun fromFile(
            modelFile: File,
            modelName: String = modelFile.name,
            requestedAccelerator: LiteRtAccelerator
        ): LiteRtModelRunner {
            return LiteRtModelRunner(
                modelName = modelName,
                modelFile = modelFile,
                requestedAccelerator = requestedAccelerator
            )
        }
    }
}
