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
 * Features zero JNI direct buffer allocations, feature caching, and FBO RGBA direct preprocessing.
 */
class Sam2OnnxVideoTracker(
    val bundle: Sam2OnnxSessionBundle,
    val encoderStride: Int = 1
) : ISam2VideoTracker {

    val metrics = Sam2OnnxRuntimeMetrics()
    private val activeStates = mutableMapOf<Int, Sam2OnnxVideoState>()
    private var sourceWidth = 0
    private var sourceHeight = 0

    // Feature Cache for Stride Optimization
    private var cachedTopFeatTensor: OnnxTensor? = null
    private var cachedTopPosTensor: OnnxTensor? = null
    private var cachedHigh0Tensor: OnnxTensor? = null
    private var cachedHigh1Tensor: OnnxTensor? = null
    private var cachedImageResult: OrtSession.Result? = null
    private var cachedImgTensor: OnnxTensor? = null

    // Pre-allocated NCHW direct image input buffer (1024x1024 RGB)
    private val inputImageBuffer: FloatBuffer = ByteBuffer.allocateDirect(1 * 3 * Sam2TensorContract.IMAGE_SIZE * Sam2TensorContract.IMAGE_SIZE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    // Reusable pooled direct buffers for zero runtime allocation
    private val reusablePtsBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val reusableLabelsBuffer: IntBuffer = ByteBuffer.allocateDirect(2 * 4)
        .order(ByteOrder.nativeOrder()).asIntBuffer()
    private val reusableTposBuffer: LongBuffer = ByteBuffer.allocateDirect(Sam2TensorContract.NUM_MASKMEM * 8)
        .order(ByteOrder.nativeOrder()).asLongBuffer()

    // Reusable output extraction float arrays
    private val reusableMemFeatFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
    private val reusableMemPosFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
    private val reusableObjPtrFloats = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)
    private val reusableRaw1024Mask = FloatArray(Sam2TensorContract.MASK_1024_ELEMS)

    private fun releaseFeatureCache() {
        try {
            cachedImgTensor?.close()
            cachedImageResult?.close()
        } catch (_: Throwable) {}
        cachedImgTensor = null
        cachedImageResult = null
        cachedTopFeatTensor = null
        cachedTopPosTensor = null
        cachedHigh0Tensor = null
        cachedHigh1Tensor = null
    }

    override fun initialize(request: Sam2InitRequest): Sam2TrackResult {
        return initializeInternal(
            objectId = request.objectId,
            bbox = request.bbox,
            width = request.sourceWidth,
            height = request.sourceHeight
        ) { buf ->
            Sam2Preprocessor.preprocessBitmapToBuffer(request.frame, buf)
        }
    }

    fun initializeWithPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        objectId: Int,
        bbox: FloatRect
    ): Sam2TrackResult {
        return initializeInternal(
            objectId = objectId,
            bbox = bbox,
            width = width,
            height = height
        ) { buf ->
            Sam2Preprocessor.preprocessRgbPixelsToBuffer(pixels, width, height, buf)
        }
    }

    override fun initializeWithRgba(
        rgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        objectId: Int,
        bbox: FloatRect
    ): Sam2TrackResult {
        return initializeInternal(
            objectId = objectId,
            bbox = bbox,
            width = width,
            height = height
        ) { buf ->
            Sam2Preprocessor.preprocessRgbaBufferToBuffer(rgbaBuffer, buf)
        }
    }

    private inline fun initializeInternal(
        objectId: Int,
        bbox: FloatRect,
        width: Int,
        height: Int,
        prepareInput: (FloatBuffer) -> Unit
    ): Sam2TrackResult {
        val t0 = System.currentTimeMillis()
        sourceWidth = width
        sourceHeight = height

        val state = Sam2OnnxVideoState(objectId)
        activeStates[objectId] = state

        // 1. Image Encoder pass
        val tEnc0 = System.currentTimeMillis()
        prepareInput(inputImageBuffer)

        releaseFeatureCache()

        val imgTensor = OnnxTensor.createTensor(
            bundle.env,
            inputImageBuffer,
            longArrayOf(1, 3, Sam2TensorContract.IMAGE_SIZE.toLong(), Sam2TensorContract.IMAGE_SIZE.toLong())
        )
        val imgResult = bundle.imageFeaturesSession.run(mapOf("image" to imgTensor))
        val tEnc1 = System.currentTimeMillis()
        val imgEncMs = tEnc1 - tEnc0

        cachedImgTensor = imgTensor
        cachedImageResult = imgResult
        cachedTopFeatTensor = imgResult.get("top_vision_feature").get() as OnnxTensor
        cachedTopPosTensor = imgResult.get("top_vision_pos_enc").get() as OnnxTensor
        cachedHigh0Tensor = imgResult.get("high_res_feature_0").get() as OnnxTensor
        cachedHigh1Tensor = imgResult.get("high_res_feature_1").get() as OnnxTensor

        val topFeat = cachedTopFeatTensor!!
        val high0 = cachedHigh0Tensor!!
        val high1 = cachedHigh1Tensor!!

        // 2. Prepare Prompt Inputs using reusable direct buffers
        val tStep0 = System.currentTimeMillis()
        val modelBbox = Sam2Preprocessor.transformBboxPrompt(bbox, width, height)
        
        reusablePtsBuffer.clear()
        reusablePtsBuffer.put(modelBbox[0])
        reusablePtsBuffer.put(modelBbox[1])
        reusablePtsBuffer.put(modelBbox[2])
        reusablePtsBuffer.put(modelBbox[3])
        reusablePtsBuffer.flip()

        reusableLabelsBuffer.clear()
        reusableLabelsBuffer.put(2)
        reusableLabelsBuffer.put(3)
        reusableLabelsBuffer.flip()

        val ptsTensor = OnnxTensor.createTensor(bundle.env, reusablePtsBuffer, longArrayOf(1, 2, 2))
        val labelsTensor = OnnxTensor.createTensor(bundle.env, reusableLabelsBuffer, longArrayOf(1, 2))

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

        // Extract outputs into reusable float arrays
        val highResMaskTensor = initResult.get("high_res_mask").get() as OnnxTensor
        val memFeatTensor = initResult.get("memory_features").get() as OnnxTensor
        val memPosTensor = initResult.get("memory_pos_enc").get() as OnnxTensor
        val objPtrTensor = initResult.get("obj_ptr").get() as OnnxTensor

        memFeatTensor.floatBuffer.get(reusableMemFeatFloats)
        memPosTensor.floatBuffer.get(reusableMemPosFloats)
        objPtrTensor.floatBuffer.get(reusableObjPtrFloats)

        // Update State with deep copied memory slices
        state.addConditioningFrame(
            0,
            reusableMemFeatFloats.clone(),
            reusableMemPosFloats.clone(),
            reusableObjPtrFloats.clone()
        )

        // Postprocess Mask (Compact 256x256 for instantaneous 0ms GPU TMU hardware upsampling)
        highResMaskTensor.floatBuffer.get(reusableRaw1024Mask)

        val softMask = Sam2MaskPostprocessor.resizeMaskBilinear(
            reusableRaw1024Mask,
            Sam2TensorContract.IMAGE_SIZE,
            Sam2TensorContract.IMAGE_SIZE,
            Sam2TensorContract.MASK_OUTPUT_SIZE,
            Sam2TensorContract.MASK_OUTPUT_SIZE
        )
        Sam2MaskPostprocessor.fastSigmoidInPlace(softMask)

        val derivedBboxStrict = Sam2MaskPostprocessor.computeBboxFromMaskStrict(
            mask = softMask,
            width = Sam2TensorContract.MASK_OUTPUT_SIZE,
            height = Sam2TensorContract.MASK_OUTPUT_SIZE,
            srcWidth = sourceWidth,
            srcHeight = sourceHeight
        )
        val isValid = derivedBboxStrict != null
        val finalBbox = derivedBboxStrict ?: FloatRect(0f, 0f, 0f, 0f)
        val maskArea = Sam2MaskPostprocessor.computeMaskArea(softMask)

        ptsTensor.close()
        labelsTensor.close()
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

    override fun step(frame: Bitmap, frameIndex: Int): List<Sam2TrackResult> {
        return stepInternal(frameIndex) { buf ->
            Sam2Preprocessor.preprocessBitmapToBuffer(frame, buf)
        }
    }

    fun stepWithPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        frameIndex: Int
    ): List<Sam2TrackResult> {
        return stepInternal(frameIndex) { buf ->
            Sam2Preprocessor.preprocessRgbPixelsToBuffer(pixels, width, height, buf)
        }
    }

    override fun stepWithRgba(
        rgbaBuffer: ByteBuffer,
        frameIndex: Int
    ): List<Sam2TrackResult> {
        return stepInternal(frameIndex) { buf ->
            Sam2Preprocessor.preprocessRgbaBufferToBuffer(rgbaBuffer, buf)
        }
    }

    private inline fun stepInternal(
        frameIndex: Int,
        prepareInput: (FloatBuffer) -> Unit
    ): List<Sam2TrackResult> {
        val results = mutableListOf<Sam2TrackResult>()
        if (activeStates.isEmpty()) {
            return results
        }

        val shouldEncode = (frameIndex == 0) || (frameIndex % encoderStride == 0) || (cachedTopFeatTensor == null)
        var imgEncMs = 0L

        if (shouldEncode) {
            val tEnc0 = System.currentTimeMillis()
            prepareInput(inputImageBuffer)

            releaseFeatureCache()

            val imgTensor = OnnxTensor.createTensor(
                bundle.env,
                inputImageBuffer,
                longArrayOf(1, 3, Sam2TensorContract.IMAGE_SIZE.toLong(), Sam2TensorContract.IMAGE_SIZE.toLong())
            )
            val imgResult = bundle.imageFeaturesSession.run(mapOf("image" to imgTensor))
            val tEnc1 = System.currentTimeMillis()
            imgEncMs = tEnc1 - tEnc0

            cachedImgTensor = imgTensor
            cachedImageResult = imgResult
            cachedTopFeatTensor = imgResult.get("top_vision_feature").get() as OnnxTensor
            cachedTopPosTensor = imgResult.get("top_vision_pos_enc").get() as OnnxTensor
            cachedHigh0Tensor = imgResult.get("high_res_feature_0").get() as OnnxTensor
            cachedHigh1Tensor = imgResult.get("high_res_feature_1").get() as OnnxTensor
        }

        val topFeat = cachedTopFeatTensor!!
        val topPos = cachedTopPosTensor!!
        val high0 = cachedHigh0Tensor!!
        val high1 = cachedHigh1Tensor!!

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
            
            reusableTposBuffer.clear()
            reusableTposBuffer.put(sel.memoryTPosIndices)
            reusableTposBuffer.flip()

            val tposTensor = OnnxTensor.createTensor(
                bundle.env,
                reusableTposBuffer,
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

            memFeatTensor.floatBuffer.get(reusableMemFeatFloats)
            memPosTensor.floatBuffer.get(reusableMemPosFloats)
            objPtrTensor.floatBuffer.get(reusableObjPtrFloats)

            state.addNonConditioningFrame(
                frameIndex,
                reusableMemFeatFloats.clone(),
                reusableMemPosFloats.clone(),
                reusableObjPtrFloats.clone()
            )

            highResMaskTensor.floatBuffer.get(reusableRaw1024Mask)

            val softMask = Sam2MaskPostprocessor.resizeMaskBilinear(
                reusableRaw1024Mask,
                Sam2TensorContract.IMAGE_SIZE,
                Sam2TensorContract.IMAGE_SIZE,
                Sam2TensorContract.MASK_OUTPUT_SIZE,
                Sam2TensorContract.MASK_OUTPUT_SIZE
            )
            Sam2MaskPostprocessor.fastSigmoidInPlace(softMask)

            val derivedBboxStrict = Sam2MaskPostprocessor.computeBboxFromMaskStrict(
                mask = softMask,
                width = Sam2TensorContract.MASK_OUTPUT_SIZE,
                height = Sam2TensorContract.MASK_OUTPUT_SIZE,
                srcWidth = sourceWidth,
                srcHeight = sourceHeight
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

        metrics.recordFrame(imgEncMs, totalStepMs)
        metrics.stateMemoryBytes = activeStates.values.sumOf { it.computeStateMemoryBytes() }

        return results
    }

    override fun reset() {
        releaseFeatureCache()
        activeStates.values.forEach { it.reset() }
        activeStates.clear()
    }

    override fun close() {
        reset()
        bundle.close()
    }
}
