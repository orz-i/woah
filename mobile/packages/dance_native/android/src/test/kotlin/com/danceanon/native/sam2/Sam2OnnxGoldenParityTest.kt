package com.danceanon.native.sam2

import com.danceanon.native.inference.FloatRect
import java.io.File
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class Sam2OnnxGoldenParityTest {

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir", ".")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "tools/sam2_onnx").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return File("D:/dance-anonymizer")
    }

    private fun loadNpyFloatArray(file: File): Pair<IntArray, FloatArray> {
        val bytes = file.readBytes()
        var headerEnd = -1
        for (i in 0 until min(bytes.size, 1024)) {
            if (bytes[i] == 0x0A.toByte()) {
                headerEnd = i + 1
                break
            }
        }
        val headerStr = String(bytes, 0, headerEnd)
        val shapeStart = headerStr.indexOf("'shape': (") + 10
        val shapeEnd = headerStr.indexOf(")", shapeStart)
        val shapeTokens = headerStr.substring(shapeStart, shapeEnd).split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }
        val dims = shapeTokens.map { it.toInt() }.toIntArray()

        val floatCount = dims.fold(1) { acc, d -> acc * d }
        val byteBuf = ByteBuffer.wrap(bytes, headerEnd, floatCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        val floatBuf = byteBuf.asFloatBuffer()
        val floats = FloatArray(floatCount)
        floatBuf.get(floats)
        return Pair(dims, floats)
    }

    private fun loadNpyIntArray(file: File): Pair<IntArray, IntArray> {
        val bytes = file.readBytes()
        var headerEnd = -1
        for (i in 0 until min(bytes.size, 1024)) {
            if (bytes[i] == 0x0A.toByte()) {
                headerEnd = i + 1
                break
            }
        }
        val headerStr = String(bytes, 0, headerEnd)
        val shapeStart = headerStr.indexOf("'shape': (") + 10
        val shapeEnd = headerStr.indexOf(")", shapeStart)
        val shapeTokens = headerStr.substring(shapeStart, shapeEnd).split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }
        val dims = shapeTokens.map { it.toInt() }.toIntArray()

        val intCount = dims.fold(1) { acc, d -> acc * d }
        val byteBuf = ByteBuffer.wrap(bytes, headerEnd, intCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        val intBuf = byteBuf.asIntBuffer()
        val ints = IntArray(intCount)
        intBuf.get(ints)
        return Pair(dims, ints)
    }

    private fun computeMaskIoU(m1: FloatArray, m2: FloatArray, threshold: Float = 0.0f): Float {
        var inter = 0
        var union = 0
        for (i in m1.indices) {
            val b1 = m1[i] > threshold
            val b2 = m2[i] > threshold
            if (b1 && b2) inter++
            if (b1 || b2) union++
        }
        if (union == 0) return if (inter == 0) 1.0f else 0.0f
        return inter.toFloat() / union.toFloat()
    }

    @Test
    fun testAndroidGoldenParity40Frames() {
        val root = findProjectRoot()
        val modelsDir = File(root, "tools/sam2_onnx/.generated")
        val refInputsDir = File(root, "tools/sam2_onnx/reference_inputs")
        val goldenDir = File(root, "tools/sam2_onnx/reference_golden")
        val reportsDir = File(root, "tools/sam2_onnx/reports")
        reportsDir.mkdirs()

        if (!modelsDir.exists()) {
            println("Skipping parity test: modelsDir not found at ${modelsDir.absolutePath}")
            return
        }

        println("[Android ORT Test] Loading models from ${modelsDir.absolutePath}...")
        val bundle = Sam2OnnxModelLoader.loadFromDirectory(modelsDir)
        val tracker = Sam2OnnxVideoTracker(bundle)

        // Read meta
        val metaFile = File(refInputsDir, "reference_meta.json")
        val metaText = metaFile.readText()
        val json = org.json.JSONObject(metaText)
        val numFrames = json.getInt("frame_count")
        val srcW = json.getInt("video_width")
        val srcH = json.getInt("video_height")

        val bboxArr = json.getJSONArray("first_bbox")
        val firstBbox = FloatRect(
            bboxArr.getDouble(0).toFloat(),
            bboxArr.getDouble(1).toFloat(),
            bboxArr.getDouble(2).toFloat(),
            bboxArr.getDouble(3).toFloat()
        )

        val perFrameRecords = mutableListOf<String>()
        val ious = mutableListOf<Float>()
        val centerErrors = mutableListOf<Float>()


        println("[Android ORT Test] Starting 40-frame sequential tracking...")
        for (fIdx in 0 until numFrames) {
            val argbFile = File(refInputsDir, String.format("%05d_argb.npy", fIdx))
            val (_, pixels) = loadNpyIntArray(argbFile)

            val trackRes: Sam2TrackResult
            if (fIdx == 0) {
                trackRes = tracker.initializeWithPixels(
                    pixels = pixels,
                    width = srcW,
                    height = srcH,
                    objectId = 1,
                    bbox = firstBbox
                )
            } else {
                val stepResults = tracker.stepWithPixels(
                    pixels = pixels,
                    width = srcW,
                    height = srcH,
                    frameIndex = fIdx
                )
                trackRes = stepResults.firstOrNull() ?: throw IllegalStateException("Empty results at frame $fIdx")
            }

            // Load golden reference mask
            val goldMaskFile = File(goldenDir, "masks/frame_${String.format("%04d", fIdx)}_mask.npy")
            val (_, goldMaskFloats) = loadNpyFloatArray(goldMaskFile)

            // Convert gold logits to prob
            for (i in goldMaskFloats.indices) {
                goldMaskFloats[i] = 1.0f / (1.0f + kotlin.math.exp(-goldMaskFloats[i]))
            }

            val iou = computeMaskIoU(trackRes.softMask, goldMaskFloats, threshold = 0.50f)
            ious.add(iou)

            val goldBbox = Sam2MaskPostprocessor.computeBboxFromMask(goldMaskFloats, srcW, srcH)
            val cxOrt = (trackRes.bbox.left + trackRes.bbox.right) / 2f
            val cyOrt = (trackRes.bbox.top + trackRes.bbox.bottom) / 2f
            val cxGold = (goldBbox.left + goldBbox.right) / 2f
            val cyGold = (goldBbox.top + goldBbox.bottom) / 2f
            val centerErr = sqrt((cxOrt - cxGold) * (cxOrt - cxGold) + (cyOrt - cyGold) * (cyOrt - cyGold))
            centerErrors.add(centerErr)

            val line = String.format(
                "%d,%.6f,%.2f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d",
                fIdx, iou, centerErr,
                trackRes.bbox.left, trackRes.bbox.top, trackRes.bbox.right, trackRes.bbox.bottom, trackRes.maskArea,
                goldBbox.left, goldBbox.top, goldBbox.right, goldBbox.bottom, Sam2MaskPostprocessor.computeMaskArea(goldMaskFloats),
                trackRes.inferenceMs
            )
            perFrameRecords.add(line)

            if (fIdx % 10 == 0 || fIdx == numFrames - 1) {
                println(String.format("  [Android Frame %02d] IoU: %.4f | CenterErr: %.2fpx | Valid: %b | Ms: %dms", fIdx, iou, centerErr, trackRes.isValid, trackRes.inferenceMs))
            }
        }

        tracker.close()

        val meanIoU = ious.average().toFloat()
        val minIoU = ious.minOrNull() ?: 0f
        val meanCenterErr = centerErrors.average().toFloat()
        val maxCenterErr = centerErrors.maxOrNull() ?: 0f

        var has3FrameDivergence = false
        var consec = 0
        for (iou in ious) {
            if (iou < 0.50f) {
                consec++
                if (consec >= 3) {
                    has3FrameDivergence = true
                    break
                }
            } else {
                consec = 0
            }
        }

        // Save CSV report
        val csvFile = File(reportsDir, "android_video_parity.csv")
        FileWriter(csvFile).use { writer ->
            writer.write("frame,mask_iou,bbox_center_err,ort_x1,ort_y1,ort_x2,ort_y2,ort_area,gold_x1,gold_y1,gold_x2,gold_y2,gold_area,total_ms\n")
            for (line in perFrameRecords) {
                writer.write(line + "\n")
            }
        }

        // Save JSON metrics
        val metricsJsonFile = File(reportsDir, "android_runtime_metrics.json")
        metricsJsonFile.writeText(
            """
            {
              "total_frames": $numFrames,
              "mean_mask_iou": $meanIoU,
              "min_mask_iou": $minIoU,
              "has_3_frame_divergence": $has3FrameDivergence,
              "mean_bbox_center_error": $meanCenterErr,
              "max_bbox_center_error": $maxCenterErr,
              "runtime_telemetry": ${tracker.metrics.summaryJson()}
            }
            """.trimIndent()
        )

        println("============================================================")
        println("[Android ORT GATE RESULTS]")
        println("  Frames Tracked: $numFrames/$numFrames")
        println(String.format("  Mean Mask IoU:  %.6f (Gate: >= 0.90)", meanIoU))
        println(String.format("  Min Mask IoU:   %.6f", minIoU))
        println("  3-Frame Divergence: " + (if (has3FrameDivergence) "YES (FAIL)" else "NO (PASS)"))
        println(String.format("  Mean BBox Center Error: %.2f px", meanCenterErr))
        println(String.format("  Max BBox Center Error:  %.2f px", maxCenterErr))
        println("============================================================")

        assertTrue(meanIoU >= 0.90f, "Android Mean Mask IoU $meanIoU < 0.90")
        assertTrue(!has3FrameDivergence, "Android 3-frame divergence detected")
        println("[Android ORT] ALL GATES PASSED!")
    }
}
