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
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val modelBytes = if (modelFile != null && modelFile.exists()) {
                modelFile.readBytes()
            } else {
                try {
                    context.assets.open("yolo11n-seg.onnx").use { it.readBytes() }
                } catch (e: Exception) {
                    throw DanceNativeException(
                        DanceNativeException.MODEL_NOT_FOUND,
                        "Asset model 'yolo11n-seg.onnx' not found in APK assets",
                        e
                    )
                }
            }

            if (modelBytes.isEmpty()) {
                throw DanceNativeException(
                    DanceNativeException.MODEL_NOT_FOUND,
                    "ONNX model file is empty"
                )
            }

            ortSession = ortEnv!!.createSession(modelBytes, sessionOptions)
            android.util.Log.i("YoloOnnxSegmenter", "✅ ONNX Runtime Session created successfully with yolo11n-seg.onnx (${modelBytes.size / 1024 / 1024} MB)")
        } catch (e: DanceNativeException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("YoloOnnxSegmenter", "Failed to initialize ONNX Runtime: ${e.message}", e)
            throw DanceNativeException(
                DanceNativeException.MODEL_INIT_FAILED,
                "Failed to initialize ONNX Runtime: ${e.message}",
                e
            )
        }
    }

    private val workspace = PreprocessorWorkspace(640)

    fun segmentRgbaSync(
        rgbaBuffer: ByteBuffer,
        mapper: com.danceanon.native.geometry.ModelCoordinateMapper,
        timestampUs: Long = 0
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

        // 1. Preprocess directly from RGBA ByteBuffer into reusable NCHW FloatBuffer
        val preprocess = YoloPreprocessor.processRgbaBuffer(
            rgbaBuffer = rgbaBuffer,
            mapper = mapper,
            workspace = workspace
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
