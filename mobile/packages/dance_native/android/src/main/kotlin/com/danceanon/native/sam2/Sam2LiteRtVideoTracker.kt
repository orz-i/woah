package com.danceanon.native.sam2

import android.graphics.Bitmap
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Production-ready LiteRT video tracker for SAM2 Hiera Tiny.
 * Complete hard migration from ONNX Runtime to App-bundled LiteRT 2.1.5.
 * Features zero runtime allocations, pre-allocated tensor buffers, feature caching, and static temporal memory attention.
 */
class Sam2LiteRtVideoTracker(
    val bundle: Sam2LiteRtModelBundle,
    val encoderStride: Int = 1
) : ISam2VideoTracker {

    private val activeStates = mutableMapOf<Int, Sam2VideoState>()
    private var sourceWidth = 0
    private var sourceHeight = 0

    // Pre-allocated NCHW direct image input buffer (1024x1024 RGB)
    private val inputImageBuffer: FloatBuffer = ByteBuffer.allocateDirect(1 * 3 * Sam2TensorContract.IMAGE_SIZE * Sam2TensorContract.IMAGE_SIZE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val inputImageFloats = FloatArray(1 * 3 * Sam2TensorContract.IMAGE_SIZE * Sam2TensorContract.IMAGE_SIZE)

    // Pre-allocated Feature Cache for stride optimization
    private val cachedTopFeat = FloatArray(Sam2TensorContract.TOP_FEAT_ELEMS)
    private val cachedTopPos = FloatArray(Sam2TensorContract.TOP_FEAT_ELEMS)
    private val cachedHigh0 = FloatArray(Sam2TensorContract.HIGH_RES_0_ELEMS)
    private val cachedHigh1 = FloatArray(Sam2TensorContract.HIGH_RES_1_ELEMS)
    private var hasCachedFeatures = false

    // Pre-allocated prompt buffers
    private val ptsFloats = FloatArray(4)
    private val labelsInts = intArrayOf(2, 3)

    // Pre-allocated output extraction arrays
    private val reusableMemFeatFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
    private val reusableMemPosFloats = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
    private val reusableObjPtrFloats = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)
    private val reusableRaw1024Mask = FloatArray(Sam2TensorContract.MASK_1024_ELEMS)
    private val reusableCompact256Mask = FloatArray(Sam2TensorContract.MASK_OUTPUT_SIZE * Sam2TensorContract.MASK_OUTPUT_SIZE)

    // Pre-allocated static selector bundle
    private val staticStateBundle = Sam2LiteRtStaticStateBundle()

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

        val state = Sam2VideoState(objectId)
        activeStates[objectId] = state

        // 1. Image Encoder pass
        prepareInput(inputImageBuffer)
        inputImageBuffer.position(0)
        inputImageBuffer.get(inputImageFloats)

        val imgInBufs = bundle.imageFeaturesRunner.getInputBuffers()
        val imgOutBufs = bundle.imageFeaturesRunner.getOutputBuffers()
        imgInBufs[0].writeFloat(inputImageFloats)
        bundle.imageFeaturesRunner.runInference()

        val outTopFeat = imgOutBufs[0].readFloat()
        val outTopPos = imgOutBufs[1].readFloat()
        val outHigh0 = imgOutBufs[2].readFloat()
        val outHigh1 = imgOutBufs[3].readFloat()

        System.arraycopy(outTopFeat, 0, cachedTopFeat, 0, cachedTopFeat.size)
        System.arraycopy(outTopPos, 0, cachedTopPos, 0, cachedTopPos.size)
        System.arraycopy(outHigh0, 0, cachedHigh0, 0, cachedHigh0.size)
        System.arraycopy(outHigh1, 0, cachedHigh1, 0, cachedHigh1.size)
        hasCachedFeatures = true

        // 2. Prepare Prompt Inputs
        val modelBbox = Sam2Preprocessor.transformBboxPrompt(bbox, width, height)
        ptsFloats[0] = modelBbox[0]
        ptsFloats[1] = modelBbox[1]
        ptsFloats[2] = modelBbox[2]
        ptsFloats[3] = modelBbox[3]

        val initInBufs = bundle.initStepRunner.getInputBuffers()
        val initOutBufs = bundle.initStepRunner.getOutputBuffers()

        initInBufs[0].writeFloat(cachedTopFeat)
        initInBufs[1].writeFloat(cachedHigh0)
        initInBufs[2].writeFloat(cachedHigh1)
        initInBufs[3].writeFloat(ptsFloats)
        initInBufs[4].writeInt(labelsInts)

        bundle.initStepRunner.runInference()

        val rawHighMask = initOutBufs[1].readFloat()
        val outObjPtr = initOutBufs[3].readFloat()
        val outMemFeat = initOutBufs[4].readFloat()
        val outMemPos = initOutBufs[5].readFloat()

        System.arraycopy(outMemFeat, 0, reusableMemFeatFloats, 0, reusableMemFeatFloats.size)
        System.arraycopy(outMemPos, 0, reusableMemPosFloats, 0, reusableMemPosFloats.size)
        System.arraycopy(outObjPtr, 0, reusableObjPtrFloats, 0, reusableObjPtrFloats.size)
        System.arraycopy(rawHighMask, 0, reusableRaw1024Mask, 0, reusableRaw1024Mask.size)

        state.addConditioningFrame(
            0,
            reusableMemFeatFloats,
            reusableMemPosFloats,
            reusableObjPtrFloats
        )

        val softMask = Sam2MaskPostprocessor.resizeMaskBilinear(
            reusableRaw1024Mask,
            Sam2TensorContract.IMAGE_SIZE,
            Sam2TensorContract.IMAGE_SIZE,
            Sam2TensorContract.MASK_OUTPUT_SIZE,
            Sam2TensorContract.MASK_OUTPUT_SIZE
        )
        Sam2MaskPostprocessor.fastSigmoidInPlace(softMask)
        System.arraycopy(softMask, 0, reusableCompact256Mask, 0, softMask.size)

        val derivedBbox = Sam2MaskPostprocessor.computeBboxFromMaskStrict(
            mask = reusableCompact256Mask,
            width = Sam2TensorContract.MASK_OUTPUT_SIZE,
            height = Sam2TensorContract.MASK_OUTPUT_SIZE,
            srcWidth = sourceWidth,
            srcHeight = sourceHeight
        )
        val isValid = derivedBbox != null
        val finalBbox = derivedBbox ?: FloatRect(0f, 0f, 0f, 0f)
        val maskArea = Sam2MaskPostprocessor.computeMaskArea(reusableCompact256Mask)

        val totalMs = System.currentTimeMillis() - t0
        return Sam2TrackResult(
            frameIndex = 0,
            objectId = objectId,
            softMask = reusableCompact256Mask.clone(),
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
        if (activeStates.isEmpty()) return results

        val shouldEncode = (frameIndex == 0) || (frameIndex % encoderStride == 0) || !hasCachedFeatures
        if (shouldEncode) {
            prepareInput(inputImageBuffer)
            inputImageBuffer.position(0)
            inputImageBuffer.get(inputImageFloats)

            val imgInBufs = bundle.imageFeaturesRunner.getInputBuffers()
            val imgOutBufs = bundle.imageFeaturesRunner.getOutputBuffers()
            imgInBufs[0].writeFloat(inputImageFloats)
            bundle.imageFeaturesRunner.runInference()

            val outTopFeat = imgOutBufs[0].readFloat()
            val outTopPos = imgOutBufs[1].readFloat()
            val outHigh0 = imgOutBufs[2].readFloat()
            val outHigh1 = imgOutBufs[3].readFloat()

            System.arraycopy(outTopFeat, 0, cachedTopFeat, 0, cachedTopFeat.size)
            System.arraycopy(outTopPos, 0, cachedTopPos, 0, cachedTopPos.size)
            System.arraycopy(outHigh0, 0, cachedHigh0, 0, cachedHigh0.size)
            System.arraycopy(outHigh1, 0, cachedHigh1, 0, cachedHigh1.size)
            hasCachedFeatures = true
        }

        // Track active objects
        for ((objId, state) in activeStates) {
            if (!state.isInitialized) continue

            val t0 = System.currentTimeMillis()
            Sam2StateSelector.selectForFrame(state, frameIndex, 40, staticStateBundle)

            val tempInBufs = bundle.temporalStepRunner.getInputBuffers()
            val tempOutBufs = bundle.temporalStepRunner.getOutputBuffers()

            tempInBufs[0].writeFloat(cachedTopFeat)
            tempInBufs[1].writeFloat(cachedTopPos)
            tempInBufs[2].writeFloat(cachedHigh0)
            tempInBufs[3].writeFloat(cachedHigh1)
            tempInBufs[4].writeFloat(staticStateBundle.paddedMemFeats)
            tempInBufs[5].writeFloat(staticStateBundle.paddedMemPos)
            tempInBufs[6].writeLong(staticStateBundle.paddedTPosIndices)
            tempInBufs[7].writeFloat(staticStateBundle.paddedObjPtrs)
            tempInBufs[8].writeFloat(staticStateBundle.attnMask)

            bundle.temporalStepRunner.runInference()

            val rawHighMask = tempOutBufs[1].readFloat()
            val outObjPtr = tempOutBufs[3].readFloat()
            val outMemFeat = tempOutBufs[4].readFloat()
            val outMemPos = tempOutBufs[5].readFloat()

            System.arraycopy(outMemFeat, 0, reusableMemFeatFloats, 0, reusableMemFeatFloats.size)
            System.arraycopy(outMemPos, 0, reusableMemPosFloats, 0, reusableMemPosFloats.size)
            System.arraycopy(outObjPtr, 0, reusableObjPtrFloats, 0, reusableObjPtrFloats.size)
            System.arraycopy(rawHighMask, 0, reusableRaw1024Mask, 0, reusableRaw1024Mask.size)

            state.addNonConditioningFrame(
                frameIndex,
                reusableMemFeatFloats,
                reusableMemPosFloats,
                reusableObjPtrFloats
            )

            val softMask = Sam2MaskPostprocessor.resizeMaskBilinear(
                reusableRaw1024Mask,
                Sam2TensorContract.IMAGE_SIZE,
                Sam2TensorContract.IMAGE_SIZE,
                Sam2TensorContract.MASK_OUTPUT_SIZE,
                Sam2TensorContract.MASK_OUTPUT_SIZE
            )
            Sam2MaskPostprocessor.fastSigmoidInPlace(softMask)
            System.arraycopy(softMask, 0, reusableCompact256Mask, 0, softMask.size)

            val derivedBbox = Sam2MaskPostprocessor.computeBboxFromMaskStrict(
                mask = reusableCompact256Mask,
                width = Sam2TensorContract.MASK_OUTPUT_SIZE,
                height = Sam2TensorContract.MASK_OUTPUT_SIZE,
                srcWidth = sourceWidth,
                srcHeight = sourceHeight
            )
            val isValid = derivedBbox != null
            val finalBbox = derivedBbox ?: FloatRect(0f, 0f, 0f, 0f)
            val maskArea = Sam2MaskPostprocessor.computeMaskArea(reusableCompact256Mask)

            val totalMs = System.currentTimeMillis() - t0
            results.add(
                Sam2TrackResult(
                    frameIndex = frameIndex,
                    objectId = objId,
                    softMask = reusableCompact256Mask.clone(),
                    bbox = finalBbox,
                    maskArea = maskArea,
                    inferenceMs = totalMs,
                    isValid = isValid
                )
            )
        }

        return results
    }

    override fun reset() {
        for (state in activeStates.values) {
            state.reset()
        }
        activeStates.clear()
        hasCachedFeatures = false
        staticStateBundle.reset()
    }

    override fun close() {
        reset()
        bundle.close()
    }
}
