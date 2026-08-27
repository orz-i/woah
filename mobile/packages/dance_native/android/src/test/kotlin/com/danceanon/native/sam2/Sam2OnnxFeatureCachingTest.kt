package com.danceanon.native.sam2

import com.danceanon.native.inference.FloatRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Sam2OnnxFeatureCachingTest {

    private fun findModelsDir(): File? {
        val candidates = listOf(
            File("../../../../tools/sam2_onnx/.generated"),
            File("../../../tools/sam2_onnx/.generated"),
            File("../../tools/sam2_onnx/.generated"),
            File("../tools/sam2_onnx/.generated"),
            File("tools/sam2_onnx/.generated")
        )
        return candidates.firstOrNull { it.exists() && File(it, Sam2TensorContract.MODEL_IMAGE_FEATURES).exists() }
    }

    @Test
    fun testDirectRgbaPreprocessingAndFeatureCaching() {
        val modelsDir = findModelsDir() ?: return
        val bundle = Sam2OnnxModelLoader.loadFromDirectory(modelsDir)
        val tracker = Sam2OnnxVideoTracker(bundle, encoderStride = 2)

        val size = Sam2TensorContract.IMAGE_SIZE
        val rgbaBuffer = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        
        // Fill sample test gradient RGBA
        for (i in 0 until size * size) {
            rgbaBuffer.put(128.toByte()) // R
            rgbaBuffer.put(200.toByte()) // G
            rgbaBuffer.put(50.toByte())  // B
            rgbaBuffer.put(255.toByte()) // A
        }
        rgbaBuffer.flip()

        // Frame 0: Init with RGBA
        val initRes = tracker.initializeWithRgba(
            rgbaBuffer = rgbaBuffer,
            width = 640,
            height = 480,
            objectId = 0,
            bbox = FloatRect(100f, 100f, 300f, 300f)
        )
        assertNotNull(initRes)
        assertEquals(0, initRes.frameIndex)
        assertTrue(initRes.softMask.isNotEmpty())

        // Frame 1: Stride 2 (Cached step)
        val stepRes1 = tracker.stepWithRgba(rgbaBuffer, frameIndex = 1)
        assertEquals(1, stepRes1.size)
        assertEquals(1, stepRes1[0].frameIndex)
        assertTrue(stepRes1[0].softMask.isNotEmpty())

        // Frame 2: Stride 2 (Re-encoded step)
        val stepRes2 = tracker.stepWithRgba(rgbaBuffer, frameIndex = 2)
        assertEquals(1, stepRes2.size)
        assertEquals(2, stepRes2[0].frameIndex)
        assertTrue(stepRes2[0].softMask.isNotEmpty())

        // Reset and close
        tracker.reset()
        tracker.close()
    }
}
