package com.danceanon.native.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.danceanon.native.bridge.DanceNativeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class YoloOnnxSegmenter(
    private val context: Context,
    private val modelFile: File? = null,
    private val numThreads: Int = 4
) : Segmenter {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (ortSession != null) return@withContext

        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(numThreads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            if (modelFile != null && modelFile.exists() && modelFile.length() > 0L) {
                ortSession = ortEnv!!.createSession(modelFile.absolutePath, sessionOptions)
                android.util.Log.i("YoloOnnxSegmenter", "✅ Loaded ONNX session from file: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024} MB)")
                return@withContext
            }

            // Extract asset model to internal files directory to enable mmap (0-copy, avoids OOM and asset decompression limits)
            val modelsDir = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
            val extractedModelFile = File(modelsDir, "yolo11n-seg.onnx")

            val assetSize = try {
                context.assets.openFd("yolo11n-seg.onnx").use { it.length }
            } catch (_: Throwable) {
                -1L
            }

            val needsCopy = !extractedModelFile.exists() ||
                    extractedModelFile.length() == 0L ||
                    (assetSize > 0L && extractedModelFile.length() != assetSize)

            if (needsCopy) {
                val tempFile = File(modelsDir, "yolo11n-seg.onnx.tmp")
                try {
                    context.assets.open("yolo11n-seg.onnx").use { input ->
                        java.io.FileOutputStream(tempFile).use { output ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    }
                    if (extractedModelFile.exists()) extractedModelFile.delete()
                    tempFile.renameTo(extractedModelFile)
                } catch (e: Exception) {
                    tempFile.delete()
                    android.util.Log.e("YoloOnnxSegmenter", "Failed to extract model from assets: ${e.message}", e)
                    throw DanceNativeException(
                        DanceNativeException.MODEL_NOT_FOUND,
                        "Asset model 'yolo11n-seg.onnx' could not be extracted: ${e.message}",
                        e
                    )
                }
            }

            if (!extractedModelFile.exists() || extractedModelFile.length() == 0L) {
                throw DanceNativeException(
                    DanceNativeException.MODEL_NOT_FOUND,
                    "Extracted model file is missing or empty: ${extractedModelFile.absolutePath}"
                )
            }

            ortSession = ortEnv!!.createSession(extractedModelFile.absolutePath, sessionOptions)
            android.util.Log.i("YoloOnnxSegmenter", "✅ ONNX Runtime Session created successfully via mmap (${extractedModelFile.length() / 1024 / 1024} MB)")
        } catch (e: DanceNativeException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("YoloOnnxSegmenter", "Failed to initialize ONNX Runtime: ${e.message}", e)
            throw DanceNativeException(
                DanceNativeException.MODEL_INIT_FAILED,
                "Failed to initialize ONNX Runtime: ${e.message}",
                e
            )
        }
    }


    private val workspace = PreprocessorWorkspace(640)

    fun segmentGlReadbackRgbaSync(
        rgbaBuffer: ByteBuffer,
        mapper: com.danceanon.native.geometry.ModelCoordinateMapper,
        timestampUs: Long = 0,
        colOrder: RgbaColOrder = RgbaColOrder.LEFT_TO_RIGHT
    ): SegmentationFrame {
        return segmentRgbaSync(
            rgbaBuffer = rgbaBuffer,
            mapper = mapper,
            timestampUs = timestampUs,
            rowOrder = RgbaRowOrder.BOTTOM_TO_TOP,
            colOrder = colOrder
        )
    }

    fun segmentRgbaSync(
        rgbaBuffer: ByteBuffer,
        mapper: com.danceanon.native.geometry.ModelCoordinateMapper,
        timestampUs: Long = 0,
        rowOrder: RgbaRowOrder = RgbaRowOrder.TOP_TO_BOTTOM,
        colOrder: RgbaColOrder = RgbaColOrder.LEFT_TO_RIGHT
    ): SegmentationFrame {
        val session = ortSession ?: throw DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,
            "YoloOnnxSegmenter session not initialized. Call initialize() first."
        )
        val env = ortEnv ?: throw DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,
            "OrtEnvironment not initialized"
        )

        val startTime = System.currentTimeMillis()

        // 1. Preprocess directly from RGBA ByteBuffer into reusable NCHW FloatBuffer with explicit row/col ordering
        val preprocess = YoloPreprocessor.processRgbaBuffer(
            rgbaBuffer = rgbaBuffer,
            mapper = mapper,
            workspace = workspace,
            rowOrder = rowOrder,
            colOrder = colOrder
        )



        // 2. Run Inference
        val detections = try {
            OnnxTensor.createTensor(
                env,
                preprocess.byteBuffer.asFloatBuffer(),
                longArrayOf(1, 3, 640, 640)
            ).use { inputTensor ->
                session.run(mapOf("images" to inputTensor)).use { results ->
                    val out0Opt = results.get("output0")
                    val out1Opt = results.get("output1")

                    if (!out0Opt.isPresent || !out1Opt.isPresent) {
                        throw DanceNativeException(
                            DanceNativeException.MODEL_OUTPUT_INVALID,
                            "Model output0 or output1 missing from ONNX inference results"
                        )
                    }

                    val out0Tensor = out0Opt.get() as OnnxTensor
                    val out1Tensor = out1Opt.get() as OnnxTensor

                    YoloPostprocessor.postprocessBuffer(
                        output0Buffer = out0Tensor.floatBuffer,
                        output1Buffer = out1Tensor.floatBuffer,
                        preprocess = preprocess,
                        confThreshold = 0.25f,
                        iouThreshold = 0.50f
                    )
                }
            }
        } catch (e: DanceNativeException) {

            throw e
        } catch (e: Exception) {
            android.util.Log.e("YoloOnnxSegmenter", "Inference error: ${e.message}", e)
            throw DanceNativeException(
                DanceNativeException.MODEL_INFERENCE_FAILED,
                "Inference execution failed: ${e.message}",
                e
            )
        }

        val elapsed = System.currentTimeMillis() - startTime
        return SegmentationFrame(
            timestampUs = timestampUs,
            persons = detections,
            inferenceTimeMs = elapsed
        )
    }

    fun segmentBitmapSync(
        bitmap: Bitmap,
        timestampUs: Long = 0,
        origWidth: Int = bitmap.width,
        origHeight: Int = bitmap.height
    ): SegmentationFrame {
        val session = ortSession ?: throw DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,
            "YoloOnnxSegmenter session not initialized. Call initialize() first."
        )
        val env = ortEnv ?: throw DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,
            "OrtEnvironment not initialized"
        )

        val startTime = System.currentTimeMillis()

        // 1. Preprocess
        val preprocess = YoloPreprocessor.processBitmap(
            bitmap = bitmap,
            origWidth = origWidth,
            origHeight = origHeight
        )

        // 2. Run Inference
        val detections = try {
            OnnxTensor.createTensor(
                env,
                preprocess.byteBuffer.asFloatBuffer(),
                longArrayOf(1, 3, 640, 640)
            ).use { inputTensor ->
                session.run(mapOf("images" to inputTensor)).use { results ->
                    val out0Opt = results.get("output0")
                    val out1Opt = results.get("output1")

                    if (!out0Opt.isPresent || !out1Opt.isPresent) {
                        throw DanceNativeException(
                            DanceNativeException.MODEL_OUTPUT_INVALID,
                            "Model output0 or output1 missing from ONNX inference results"
                        )
                    }

                    val out0Tensor = out0Opt.get() as OnnxTensor
                    val out1Tensor = out1Opt.get() as OnnxTensor

                    YoloPostprocessor.postprocessBuffer(
                        output0Buffer = out0Tensor.floatBuffer,
                        output1Buffer = out1Tensor.floatBuffer,
                        preprocess = preprocess,
                        confThreshold = 0.25f,
                        iouThreshold = 0.50f
                    )
                }
            }
        } catch (e: DanceNativeException) {

            throw e
        } catch (e: Exception) {
            android.util.Log.e("YoloOnnxSegmenter", "Inference error: ${e.message}", e)
            throw DanceNativeException(
                DanceNativeException.MODEL_INFERENCE_FAILED,
                "Inference execution failed: ${e.message}",
                e
            )
        }

        val elapsed = System.currentTimeMillis() - startTime
        return SegmentationFrame(
            timestampUs = timestampUs,
            persons = detections,
            inferenceTimeMs = elapsed
        )
    }

    suspend fun segmentBitmap(bitmap: Bitmap, timestampUs: Long = 0): SegmentationFrame = withContext(Dispatchers.Default) {
        segmentBitmapSync(bitmap, timestampUs)
    }

    override suspend fun segment(
        rgbBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rotation: Int,
        timestampUs: Long
    ): SegmentationFrame = withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(rgbBuffer)
        val result = segmentBitmapSync(bitmap, timestampUs)
        bitmap.recycle()
        result
    }

    override fun close() {
        try {
            ortSession?.close()
            ortSession = null
        } catch (_: Exception) {}
    }
}
