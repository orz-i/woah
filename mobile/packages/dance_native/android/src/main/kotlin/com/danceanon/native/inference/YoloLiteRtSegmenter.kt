package com.danceanon.native.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.danceanon.native.bridge.DanceNativeException
import com.danceanon.native.litert.LiteRtAccelerator
import com.danceanon.native.litert.LiteRtModelRunner
import com.danceanon.native.litert.LiteRtRunnerPolicy
import com.danceanon.native.litert.LiteRtRuntimeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class YoloLiteRtSegmenter(
    private val context: Context,
    private val modelFile: File? = null,
    private val assetPath: String = DEFAULT_ASSET_PATH,
    private val requestedAccelerator: LiteRtAccelerator = LiteRtAccelerator.GPU
) : Segmenter {

    private var runner: LiteRtModelRunner? = null
    private var tensorAdapter: YoloLiteRtTensorAdapter? = null
    private val workspace = PreprocessorWorkspace(640)
    private val reusableInputFloats = FloatArray(1 * 3 * 640 * 640)

    val runtimeInfo: LiteRtRuntimeInfo?
        get() = runner?.runtimeInfo

    val effectiveAccelerator: LiteRtAccelerator
        get() = runner?.effectiveAccelerator ?: requestedAccelerator

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (runner != null) return@withContext

        try {
            val policy = if (requestedAccelerator == LiteRtAccelerator.GPU) {
                LiteRtRunnerPolicy.GPU_WITH_CPU_FALLBACK
            } else {
                LiteRtRunnerPolicy.STRICT_CPU
            }

            val modelRunner = if (modelFile != null && modelFile.exists() && modelFile.length() > 0L) {
                LiteRtModelRunner.fromFile(
                    modelFile = modelFile,
                    modelName = modelFile.name,
                    policy = policy
                )
            } else {
                LiteRtModelRunner.fromAsset(
                    context = context,
                    assetPath = assetPath,
                    modelName = File(assetPath).name,
                    policy = policy
                )
            }

            modelRunner.initialize()
            runner = modelRunner

            val inShapes = modelRunner.runtimeInfo?.inputShapes ?: emptyList()
            val outShapes = modelRunner.runtimeInfo?.outputShapes ?: emptyList()
            val out0Shape = if (outShapes.isNotEmpty()) outShapes[0] else emptyList()
            val out1Shape = if (outShapes.size > 1) outShapes[1] else emptyList()

            tensorAdapter = YoloLiteRtTensorAdapter(out0Shape, out1Shape)

            Log.i(
                TAG,
                "[LiteRT YOLO] Initialized successfully model=${modelRunner.modelName} with requested: $requestedAccelerator, effective accelerator: ${modelRunner.effectiveAccelerator}"
            )
        } catch (e: DanceNativeException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "[LiteRT YOLO] Failed to initialize: ", e)
            throw DanceNativeException(
                DanceNativeException.MODEL_INIT_FAILED,
                "Failed to initialize LiteRT YOLO Segmenter: ${e.javaClass.simpleName}: ${e.message}",
                e
            )
        }
    }

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
        val modelRunner = runner ?: throw DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,
            "YoloLiteRtSegmenter not initialized. Call initialize() first."
        )
        val adapter = tensorAdapter ?: throw DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,
            "YoloLiteRtTensorAdapter not initialized."
        )

        val startTime = System.currentTimeMillis()

        // 1. Preprocess directly into reusable workspace FloatBuffer
        val preprocess = YoloPreprocessor.processRgbaBuffer(
            rgbaBuffer = rgbaBuffer,
            mapper = mapper,
            workspace = workspace,
            rowOrder = rowOrder,
            colOrder = colOrder
        )

        // 2. Write input buffer and run inference
        val detections = try {
            val inputBufs = modelRunner.getInputBuffers()
            val outputBufs = modelRunner.getOutputBuffers()

            if (inputBufs.isEmpty() || outputBufs.size < 2) {
                throw DanceNativeException(
                    DanceNativeException.MODEL_OUTPUT_INVALID,
                    "Expected 1 input buffer and at least 2 output buffers from LiteRT YOLO model"
                )
            }

            val inBuf = inputBufs[0]
            val floatBuf = preprocess.floatBuffer
            floatBuf.position(0)
            val count = minOf(floatBuf.remaining(), reusableInputFloats.size)
            floatBuf.get(reusableInputFloats, 0, count)
            inBuf.writeFloat(reusableInputFloats)

            modelRunner.runInference()

            val out0Floats = outputBufs[0].readFloat()
            val out1Floats = outputBufs[1].readFloat()

            val out0Buf = java.nio.FloatBuffer.wrap(out0Floats)
            val out1Buf = java.nio.FloatBuffer.wrap(out1Floats)

            adapter.parseDetections(
                output0Buffer = out0Buf,
                output1Buffer = out1Buf,
                preprocess = preprocess,
                confThreshold = 0.25f,
                iouThreshold = 0.50f
            )
        } catch (e: DanceNativeException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "[LiteRT YOLO] Inference error: ", e)
            throw DanceNativeException(
                DanceNativeException.MODEL_INFERENCE_FAILED,
                "Inference execution failed on LiteRT YOLO: ${e.javaClass.simpleName}: ${e.message}",
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
        val modelRunner = runner ?: throw DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,
            "YoloLiteRtSegmenter not initialized. Call initialize() first."
        )
        val adapter = tensorAdapter ?: throw DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,
            "YoloLiteRtTensorAdapter not initialized."
        )

        val startTime = System.currentTimeMillis()

        val preprocess = YoloPreprocessor.processBitmap(
            bitmap = bitmap,
            origWidth = origWidth,
            origHeight = origHeight
        )

        val detections = try {
            val inputBufs = modelRunner.getInputBuffers()
            val outputBufs = modelRunner.getOutputBuffers()

            if (inputBufs.isEmpty() || outputBufs.size < 2) {
                throw DanceNativeException(
                    DanceNativeException.MODEL_OUTPUT_INVALID,
                    "Expected 1 input buffer and at least 2 output buffers from LiteRT YOLO model"
                )
            }

            val inBuf = inputBufs[0]
            val floatBuf = preprocess.floatBuffer
            floatBuf.position(0)
            val count = minOf(floatBuf.remaining(), reusableInputFloats.size)
            floatBuf.get(reusableInputFloats, 0, count)
            inBuf.writeFloat(reusableInputFloats)

            modelRunner.runInference()

            val out0Floats = outputBufs[0].readFloat()
            val out1Floats = outputBufs[1].readFloat()

            val out0Buf = java.nio.FloatBuffer.wrap(out0Floats)
            val out1Buf = java.nio.FloatBuffer.wrap(out1Floats)

            adapter.parseDetections(
                output0Buffer = out0Buf,
                output1Buffer = out1Buf,
                preprocess = preprocess,
                confThreshold = 0.25f,
                iouThreshold = 0.50f
            )
        } catch (e: DanceNativeException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "[LiteRT YOLO] Inference error: ", e)
            throw DanceNativeException(
                DanceNativeException.MODEL_INFERENCE_FAILED,
                "Inference execution failed on LiteRT YOLO: ${e.javaClass.simpleName}: ${e.message}",
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
        runner?.close()
        runner = null
        tensorAdapter = null
    }

    companion object {
        private const val TAG = "YoloLiteRtSegmenter"
        const val DEFAULT_ASSET_PATH = "models/litert/yolo11n-seg-fp16.tflite"
    }
}
