package com.danceanon.native.sam2

import com.danceanon.native.inference.FloatRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Sam2ZeroAllocationTest {

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
    fun testZeroAllocationBufferReuseAcrossMultipleInferences() {
        val modelsDir = findModelsDir() ?: return
        val bundle = Sam2OnnxModelLoader.loadFromDirectory(modelsDir)
        val tracker = Sam2OnnxVideoTracker(bundle, encoderStride = 1)

        val size = Sam2TensorContract.IMAGE_SIZE
        val rgbaBuffer = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        
        for (i in 0 until size * size) {
            rgbaBuffer.put((i % 256).toByte())
            rgbaBuffer.put(((i * 2) % 256).toByte())
            rgbaBuffer.put(((i * 3) % 256).toByte())
            rgbaBuffer.put(255.toByte())
        }
        rgbaBuffer.flip()

        // Init
        val initRes = tracker.initializeWithRgba(
            rgbaBuffer = rgbaBuffer,
            width = 640,
            height = 480,
            objectId = 0,
            bbox = FloatRect(50f, 50f, 200f, 200f)
        )
        assertNotNull(initRes)
        assertTrue(initRes.softMask.isNotEmpty())

        // Multiple steps reusing preallocated buffers
        for (f in 1..5) {
            val stepRes = tracker.stepWithRgba(rgbaBuffer, frameIndex = f)
            assertEquals(1, stepRes.size)
            assertEquals(f, stepRes[0].frameIndex)
            assertEquals(640 * 480, stepRes[0].softMask.size)
            assertTrue(stepRes[0].softMask.all { !it.isNaN() })
        }


        tracker.reset()
        tracker.close()
    }
}
