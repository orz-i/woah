package com.danceanon.native.sam2

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * Real production and standalone ONNX Runtime video tracker for SAM2 Hiera Tiny.
 * Strictly adheres to the SAM2 temporal state and memory attention tensor contracts.
 */
class Sam2OnnxVideoTracker(
    val bundle: Sam2OnnxSessionBundle
) : ISam2VideoTracker {

    val metrics = Sam2OnnxRuntimeMetrics()
    private val activeStates = mutableMapOf<Int, Sam2OnnxVideoState>()
    private var sourceWidth = 0
    private var sourceHeight = 0

    // Pre-allocated NCHW direct image input buffer (1024x1024 RGB)
    private val inputImageBuffer: FloatBuffer = ByteBuffer.allocateDirect(1 * 3 * Sam2TensorContract.IMAGE_SIZE * Sam2TensorContract.IMAGE_SIZE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    override fun initialize(request: Sam2InitRequest): Sam2TrackResult {
        val t0 = System.currentTimeMillis()
        sourceWidth = request.sourceWidth
        sourceHeight = request.sourceHeight

        val state = Sam2OnnxVideoState(request.objectId)
        activeStates[request.objectId] = state

        // 1. Image Encoder
        val tEnc0 = System.currentTimeMillis()
        Sam2Preprocessor.preprocessBitmapToBuffer(request.frame, inputImageBuffer)
        val imgTensor = OnnxTensor.createTensor(
            bundle.env,
            inputImageBuffer,
            longArrayOf(1, 3, Sam2TensorContract.IMAGE_SIZE.toLong(), Sam2TensorContract.IMAGE_SIZE.toLong())
        )

        val imgResult = bundle.imageFeaturesSession.run(mapOf("image" to imgTensor))
        val tEnc1 = System.currentTimeMillis()
        val imgEncMs = tEnc1 - tEnc0

        // Extract image features
        val topFeat = imgResult.get("top_vision_feature").get() as OnnxTensor
        val high0 = imgResult.get("high_res_feature_0").get() as OnnxTensor
        val high1 = imgResult.get("high_res_feature_1").get() as OnnxTensor

        // 2. Prepare Prompt Inputs
        val tStep0 = System.currentTimeMillis()
        val modelBbox = Sam2Preprocessor.transformBboxPrompt(
            request.bbox,
            request.sourceWidth,
            request.sourceHeight
        )
        val ptsBuffer = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(modelBbox[0])
            put(modelBbox[1])
            put(modelBbox[2])
            put(modelBbox[3])
            flip()
        }
        val labelsBuffer = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
            put(2)
            put(3)
            flip()
        }

        val ptsTensor = OnnxTensor.createTensor(bundle.env, ptsBuffer, longArrayOf(1, 2, 2))
        val labelsTensor = OnnxTensor.createTensor(bundle.env, labelsBuffer, longArrayOf(1, 2))

        val initInputs = mapOf(
            "top_vision_feature" to topFeat,
            "high_res_feature_0" to high0,
            "high_res_feature_1" to high1,
            "point_coords" to ptsTensor,
            "point_labels" to labelsTensor
        )

        val initResult = bundle.initStepSession.run(initInputs)
        val tStep1 = System.currentTimeMillis()
        val stepMs = tStep1 - tStep0

        // Extract outputs
        val highResMaskTensor = initResult.get("high_res_mask").get() as OnnxTensor
        val memFeatTensor = initResult.get("memory_features").get() as OnnxTensor
        val memPosTensor = initResult.get("memory_pos_enc").get() as OnnxTensor
        val objPtrTensor = initResult.get("obj_ptr").get() as OnnxTensor

        val memFeatFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        memFeatTensor.floatBuffer.get(memFeatFloats)

        val memPosFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        memPosTensor.floatBuffer.get(memPosFloats)

        val objPtrFloats = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)
        objPtrTensor.floatBuffer.get(objPtrFloats)

        // Update State
        state.addConditioningFrame(0, memFeatFloats, memPosFloats, objPtrFloats)

        // Postprocess Mask
        val raw1024Mask = FloatArray(Sam2TensorContract.MASK_1024_ELEMS)
        highResMaskTensor.floatBuffer.get(raw1024Mask)

        val softMask = Sam2MaskPostprocessor.resizeMaskBilinear(
            raw1024Mask,
            Sam2TensorContract.IMAGE_SIZE,
            Sam2TensorContract.IMAGE_SIZE,
            sourceWidth,
            sourceHeight
        )
        Sam2MaskPostprocessor.sigmoidInPlace(softMask)


        val derivedBboxStrict = Sam2MaskPostprocessor.computeBboxFromMaskStrict(
            softMask,
            sourceWidth,
            sourceHeight
        )
        val isValid = derivedBboxStrict != null
        val finalBbox = derivedBboxStrict ?: FloatRect(0f, 0f, 0f, 0f)
        val maskArea = Sam2MaskPostprocessor.computeMaskArea(softMask)

        // Close per-step ONNX resources
        imgTensor.close()
        ptsTensor.close()
        labelsTensor.close()
        imgResult.close()
        initResult.close()

        val totalMs = System.currentTimeMillis() - t0
        metrics.recordFrame(imgEncMs, stepMs)
        metrics.stateMemoryBytes = state.computeStateMemoryBytes()

        return Sam2TrackResult(
            frameIndex = 0,
            objectId = request.objectId,
            softMask = softMask,
            bbox = finalBbox,
            maskArea = maskArea,
            inferenceMs = totalMs,
            isValid = isValid
        )
    }

    override fun step(frame: Bitmap, frameIndex: Int): List<Sam2TrackResult> {
        val t0 = System.currentTimeMillis()
        val results = mutableListOf<Sam2TrackResult>()

        if (activeStates.isEmpty()) {
            return results
        }

        // 1. Single Image Encoder pass per frame
        val tEnc0 = System.currentTimeMillis()
        Sam2Preprocessor.preprocessBitmapToBuffer(frame, inputImageBuffer)
        val imgTensor = OnnxTensor.createTensor(
            bundle.env,
            inputImageBuffer,
            longArrayOf(1, 3, Sam2TensorContract.IMAGE_SIZE.toLong(), Sam2TensorContract.IMAGE_SIZE.toLong())
        )

        val imgResult = bundle.imageFeaturesSession.run(mapOf("image" to imgTensor))
        val tEnc1 = System.currentTimeMillis()
        val imgEncMs = tEnc1 - tEnc0

        val topFeat = imgResult.get("top_vision_feature").get() as OnnxTensor
        val topPos = imgResult.get("top_vision_pos_enc").get() as OnnxTensor
        val high0 = imgResult.get("high_res_feature_0").get() as OnnxTensor
        val high1 = imgResult.get("high_res_feature_1").get() as OnnxTensor

        var totalStepMs = 0L

        // 2. Track active objects
        for ((objId, state) in activeStates) {
            if (!state.isInitialized) continue

            val tStep0 = System.currentTimeMillis()
            val sel = Sam2OnnxStateSelector.selectForFrame(state, frameIndex)

            val selMemFeatTensor = OnnxTensor.createTensor(
                bundle.env,
                sel.memoryFeaturesBuffer,
                longArrayOf(sel.memoryCount.toLong(), 1, 64, 64, 64)
            )
            val selMemPosTensor = OnnxTensor.createTensor(
                bundle.env,
                sel.memoryPosBuffer,
                longArrayOf(sel.memoryCount.toLong(), 1, 64, 64, 64)
            )
            val tposBuffer = ByteBuffer.allocateDirect(sel.memoryCount * 8).order(ByteOrder.nativeOrder()).asLongBuffer().apply {
                put(sel.memoryTPosIndices)
                flip()
            }
            val tposTensor = OnnxTensor.createTensor(
                bundle.env,
                tposBuffer,
                longArrayOf(sel.memoryCount.toLong())
            )
            val selPtrsTensor = OnnxTensor.createTensor(
                bundle.env,
                sel.objPtrsBuffer,
                longArrayOf(sel.objPtrCount.toLong(), 1, 256)
            )

            val tempInputs = mapOf(
                "current_top_feature" to topFeat,
                "current_top_pos" to topPos,
                "current_high_res_0" to high0,
                "current_high_res_1" to high1,
                "selected_memory_features" to selMemFeatTensor,
                "selected_memory_pos" to selMemPosTensor,
                "memory_tpos_indices" to tposTensor,
                "selected_obj_ptrs" to selPtrsTensor
            )

            val tempResult = bundle.temporalStepSession.run(tempInputs)
            val tStep1 = System.currentTimeMillis()
            val stepMs = tStep1 - tStep0
            totalStepMs += stepMs

            val highResMaskTensor = tempResult.get("high_res_mask").get() as OnnxTensor
            val memFeatTensor = tempResult.get("memory_features").get() as OnnxTensor
            val memPosTensor = tempResult.get("memory_pos_enc").get() as OnnxTensor
            val objPtrTensor = tempResult.get("obj_ptr").get() as OnnxTensor

            val memFeatFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
            memFeatTensor.floatBuffer.get(memFeatFloats)

            val memPosFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
            memPosTensor.floatBuffer.get(memPosFloats)

            val objPtrFloats = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)
            objPtrTensor.floatBuffer.get(objPtrFloats)

            state.addNonConditioningFrame(frameIndex, memFeatFloats, memPosFloats, objPtrFloats)

            val raw1024Mask = FloatArray(Sam2TensorContract.MASK_1024_ELEMS)
            highResMaskTensor.floatBuffer.get(raw1024Mask)

            val softMask = Sam2MaskPostprocessor.resizeMaskBilinear(
                raw1024Mask,
                Sam2TensorContract.IMAGE_SIZE,
                Sam2TensorContract.IMAGE_SIZE,
                sourceWidth,
                sourceHeight
            )
            Sam2MaskPostprocessor.sigmoidInPlace(softMask)


            val derivedBboxStrict = Sam2MaskPostprocessor.computeBboxFromMaskStrict(
                softMask,
                sourceWidth,
                sourceHeight
            )
            val isValid = derivedBboxStrict != null
            val finalBbox = derivedBboxStrict ?: FloatRect(0f, 0f, 0f, 0f)
            val maskArea = Sam2MaskPostprocessor.computeMaskArea(softMask)

            selMemFeatTensor.close()
            selMemPosTensor.close()
            tposTensor.close()
            selPtrsTensor.close()
            tempResult.close()

            results.add(
                Sam2TrackResult(
                    frameIndex = frameIndex,
                    objectId = objId,
                    softMask = softMask,
                    bbox = finalBbox,
                    maskArea = maskArea,
                    inferenceMs = imgEncMs + stepMs,
                    isValid = isValid
                )
            )
        }

        imgTensor.close()
        imgResult.close()

        metrics.recordFrame(imgEncMs, totalStepMs)
        metrics.stateMemoryBytes = activeStates.values.sumOf { it.computeStateMemoryBytes() }

        return results
    }

    fun initializeWithPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        objectId: Int,
        bbox: FloatRect
    ): Sam2TrackResult {
        val t0 = System.currentTimeMillis()
        sourceWidth = width
        sourceHeight = height

        val state = Sam2OnnxVideoState(objectId)
        activeStates[objectId] = state

        // 1. Image Encoder
        val tEnc0 = System.currentTimeMillis()
        Sam2Preprocessor.preprocessRgbPixelsToBuffer(pixels, width, height, inputImageBuffer)
        val imgTensor = OnnxTensor.createTensor(
            bundle.env,
            inputImageBuffer,
            longArrayOf(1, 3, Sam2TensorContract.IMAGE_SIZE.toLong(), Sam2TensorContract.IMAGE_SIZE.toLong())
        )

        val imgResult = bundle.imageFeaturesSession.run(mapOf("image" to imgTensor))
        val tEnc1 = System.currentTimeMillis()
        val imgEncMs = tEnc1 - tEnc0

        val topFeat = imgResult.get("top_vision_feature").get() as OnnxTensor
        val high0 = imgResult.get("high_res_feature_0").get() as OnnxTensor
        val high1 = imgResult.get("high_res_feature_1").get() as OnnxTensor

        // 2. Prepare Prompt Inputs
        val tStep0 = System.currentTimeMillis()
        val modelBbox = Sam2Preprocessor.transformBboxPrompt(bbox, width, height)
        val ptsBuffer = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(modelBbox[0])
            put(modelBbox[1])
            put(modelBbox[2])
            put(modelBbox[3])
            flip()
        }
        val labelsBuffer = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
            put(2)
            put(3)
            flip()
        }

        val ptsTensor = OnnxTensor.createTensor(bundle.env, ptsBuffer, longArrayOf(1, 2, 2))
        val labelsTensor = OnnxTensor.createTensor(bundle.env, labelsBuffer, longArrayOf(1, 2))

        val initInputs = mapOf(
            "top_vision_feature" to topFeat,
            "high_res_feature_0" to high0,
            "high_res_feature_1" to high1,
            "point_coords" to ptsTensor,
            "point_labels" to labelsTensor
        )

        val initResult = bundle.initStepSession.run(initInputs)
        val tStep1 = System.currentTimeMillis()
        val stepMs = tStep1 - tStep0

        val highResMaskTensor = initResult.get("high_res_mask").get() as OnnxTensor
        val memFeatTensor = initResult.get("memory_features").get() as OnnxTensor
        val memPosTensor = initResult.get("memory_pos_enc").get() as OnnxTensor
        val objPtrTensor = initResult.get("obj_ptr").get() as OnnxTensor

        val memFeatFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        memFeatTensor.floatBuffer.get(memFeatFloats)

        val memPosFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        memPosTensor.floatBuffer.get(memPosFloats)

        val objPtrFloats = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)
        objPtrTensor.floatBuffer.get(objPtrFloats)

        state.addConditioningFrame(0, memFeatFloats, memPosFloats, objPtrFloats)

        val raw1024Mask = FloatArray(Sam2TensorContract.MASK_1024_ELEMS)
        highResMaskTensor.floatBuffer.get(raw1024Mask)

        val softMask = Sam2MaskPostprocessor.resizeMaskBilinear(
            raw1024Mask,
            Sam2TensorContract.IMAGE_SIZE,
            Sam2TensorContract.IMAGE_SIZE,
            sourceWidth,
            sourceHeight
        )
        Sam2MaskPostprocessor.sigmoidInPlace(softMask)


        val derivedBboxStrict = Sam2MaskPostprocessor.computeBboxFromMaskStrict(
            softMask,
            sourceWidth,
            sourceHeight
        )
        val isValid = derivedBboxStrict != null
        val finalBbox = derivedBboxStrict ?: FloatRect(0f, 0f, 0f, 0f)
        val maskArea = Sam2MaskPostprocessor.computeMaskArea(softMask)

        imgTensor.close()
        ptsTensor.close()
        labelsTensor.close()
        imgResult.close()
        initResult.close()

        val totalMs = System.currentTimeMillis() - t0
        metrics.recordFrame(imgEncMs, stepMs)
        metrics.stateMemoryBytes = state.computeStateMemoryBytes()

        return Sam2TrackResult(
            frameIndex = 0,
            objectId = objectId,
            softMask = softMask,
            bbox = finalBbox,
            maskArea = maskArea,
            inferenceMs = totalMs,
            isValid = isValid
        )
    }

    fun stepWithPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        frameIndex: Int
    ): List<Sam2TrackResult> {
        val t0 = System.currentTimeMillis()
        val results = mutableListOf<Sam2TrackResult>()

        if (activeStates.isEmpty()) {
            return results
        }

        // 1. Single Image Encoder pass per frame
        val tEnc0 = System.currentTimeMillis()
        Sam2Preprocessor.preprocessRgbPixelsToBuffer(pixels, width, height, inputImageBuffer)
        val imgTensor = OnnxTensor.createTensor(
            bundle.env,
            inputImageBuffer,
            longArrayOf(1, 3, Sam2TensorContract.IMAGE_SIZE.toLong(), Sam2TensorContract.IMAGE_SIZE.toLong())
        )

        val imgResult = bundle.imageFeaturesSession.run(mapOf("image" to imgTensor))
        val tEnc1 = System.currentTimeMillis()
        val imgEncMs = tEnc1 - tEnc0

        val topFeat = imgResult.get("top_vision_feature").get() as OnnxTensor
        val topPos = imgResult.get("top_vision_pos_enc").get() as OnnxTensor
        val high0 = imgResult.get("high_res_feature_0").get() as OnnxTensor
        val high1 = imgResult.get("high_res_feature_1").get() as OnnxTensor

        var totalStepMs = 0L

        // 2. Track active objects
        for ((objId, state) in activeStates) {
            if (!state.isInitialized) continue

            val tStep0 = System.currentTimeMillis()
            val sel = Sam2OnnxStateSelector.selectForFrame(state, frameIndex)

            val selMemFeatTensor = OnnxTensor.createTensor(
                bundle.env,
                sel.memoryFeaturesBuffer,
                longArrayOf(sel.memoryCount.toLong(), 1, 64, 64, 64)
            )
            val selMemPosTensor = OnnxTensor.createTensor(
                bundle.env,
                sel.memoryPosBuffer,
                longArrayOf(sel.memoryCount.toLong(), 1, 64, 64, 64)
            )
            val tposBuffer = ByteBuffer.allocateDirect(sel.memoryCount * 8).order(ByteOrder.nativeOrder()).asLongBuffer().apply {
                put(sel.memoryTPosIndices)
                flip()
            }
            val tposTensor = OnnxTensor.createTensor(
                bundle.env,
                tposBuffer,
                longArrayOf(sel.memoryCount.toLong())
            )
            val selPtrsTensor = OnnxTensor.createTensor(
                bundle.env,
                sel.objPtrsBuffer,
                longArrayOf(sel.objPtrCount.toLong(), 1, 256)
            )

            val tempInputs = mapOf(
                "current_top_feature" to topFeat,
                "current_top_pos" to topPos,
                "current_high_res_0" to high0,
                "current_high_res_1" to high1,
                "selected_memory_features" to selMemFeatTensor,
                "selected_memory_pos" to selMemPosTensor,
                "memory_tpos_indices" to tposTensor,
                "selected_obj_ptrs" to selPtrsTensor
            )

            val tempResult = bundle.temporalStepSession.run(tempInputs)
            val tStep1 = System.currentTimeMillis()
            val stepMs = tStep1 - tStep0
            totalStepMs += stepMs

            val highResMaskTensor = tempResult.get("high_res_mask").get() as OnnxTensor
            val memFeatTensor = tempResult.get("memory_features").get() as OnnxTensor
            val memPosTensor = tempResult.get("memory_pos_enc").get() as OnnxTensor
            val objPtrTensor = tempResult.get("obj_ptr").get() as OnnxTensor

            val memFeatFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
            memFeatTensor.floatBuffer.get(memFeatFloats)

            val memPosFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
            memPosTensor.floatBuffer.get(memPosFloats)

            val objPtrFloats = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)
            objPtrTensor.floatBuffer.get(objPtrFloats)

            state.addNonConditioningFrame(frameIndex, memFeatFloats, memPosFloats, objPtrFloats)

            val raw1024Mask = FloatArray(Sam2TensorContract.MASK_1024_ELEMS)
            highResMaskTensor.floatBuffer.get(raw1024Mask)

            val softMask = Sam2MaskPostprocessor.resizeMaskBilinear(
                raw1024Mask,
                Sam2TensorContract.IMAGE_SIZE,
                Sam2TensorContract.IMAGE_SIZE,
                sourceWidth,
                sourceHeight
            )
            Sam2MaskPostprocessor.sigmoidInPlace(softMask)


            val derivedBboxStrict = Sam2MaskPostprocessor.computeBboxFromMaskStrict(
                softMask,
                sourceWidth,
                sourceHeight
            )
            val isValid = derivedBboxStrict != null
            val finalBbox = derivedBboxStrict ?: FloatRect(0f, 0f, 0f, 0f)
            val maskArea = Sam2MaskPostprocessor.computeMaskArea(softMask)

            selMemFeatTensor.close()
            selMemPosTensor.close()
            tposTensor.close()
            selPtrsTensor.close()
            tempResult.close()

            results.add(
                Sam2TrackResult(
                    frameIndex = frameIndex,
                    objectId = objId,
                    softMask = softMask,
                    bbox = finalBbox,
                    maskArea = maskArea,
                    inferenceMs = imgEncMs + stepMs,
                    isValid = isValid
                )
            )
        }

        imgTensor.close()
        imgResult.close()

        metrics.recordFrame(imgEncMs, totalStepMs)
        metrics.stateMemoryBytes = activeStates.values.sumOf { it.computeStateMemoryBytes() }

        return results
    }

    override fun reset() {
        activeStates.values.forEach { it.reset() }
        activeStates.clear()
    }

    override fun close() {
        reset()
        bundle.close()
    }
}

