package com.danceanon.native.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class YoloLiteRtSegmenter(
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
                context.assets.open("yolo11n-seg.onnx").use { it.readBytes() }
            }

            ortSession = ortEnv!!.createSession(modelBytes, sessionOptions)
            android.util.Log.i("YoloLiteRtSegmenter", "✅ ONNX Runtime Session created successfully with yolo11n-seg.onnx (${modelBytes.size / 1024 / 1024} MB)")
        } catch (e: Exception) {
            android.util.Log.e("YoloLiteRtSegmenter", "Failed to initialize ONNX Runtime: ${e.message}", e)
        }
    }

    suspend fun segmentBitmap(bitmap: Bitmap, timestampUs: Long = 0): SegmentationFrame = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // 1. Preprocess
        val preprocess = YoloPreprocessor.processBitmap(bitmap)

        val detections = if (ortSession != null && ortEnv != null) {
            try {
                val inputTensor = OnnxTensor.createTensor(
                    ortEnv,
                    preprocess.byteBuffer.asFloatBuffer(),
                    longArrayOf(1, 3, 640, 640)
                )
                val results = ortSession!!.run(mapOf("images" to inputTensor))

                val out0Tensor = results.get("output0").get() as OnnxTensor
                val out1Tensor = results.get("output1").get() as OnnxTensor

                val resultList = YoloPostprocessor.postprocessBuffer(
                    output0Buffer = out0Tensor.floatBuffer,
                    output1Buffer = out1Tensor.floatBuffer,
                    preprocess = preprocess,
                    confThreshold = 0.25f,
                    iouThreshold = 0.50f
                )

                inputTensor.close()
                results.close()
                resultList
            } catch (e: Exception) {
                android.util.Log.e("YoloLiteRtSegmenter", "Inference error: ${e.message}", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        val elapsed = System.currentTimeMillis() - startTime
        SegmentationFrame(
            timestampUs = timestampUs,
            persons = detections,
            inferenceTimeMs = elapsed
        )
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

        val rotatedBitmap = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            bitmap.recycle()
            rotated
        } else {
            bitmap
        }

        val result = segmentBitmap(rotatedBitmap, timestampUs)
        rotatedBitmap.recycle()
        result
    }

    override fun close() {
        ortSession?.close()
        ortSession = null
        ortEnv?.close()
        ortEnv = null
    }
}
