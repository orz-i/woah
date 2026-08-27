package com.danceanon.native.sam2

/**
 * SAM2 Hiera Tiny architectural tensor contracts and constants.
 * Extracted directly from sam2_hiera_t.yaml and model manifest.
 */
object Sam2TensorContract {
    const val IMAGE_SIZE = 1024
    const val NUM_MASKMEM = 7
    const val MEM_DIM = 64
    const val HIDDEN_DIM = 256
    const val MAX_OBJ_PTRS = 16
    const val LOW_RES_MASK_SIZE = 256
    const val MASK_OUTPUT_SIZE = 256
    const val FEAT_SIZE = 64


    const val MASK_THRESHOLD = 0.15f
    const val BBOX_EXPAND_RATIO = 0.05f

    val NORM_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val NORM_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    // LiteRT Model Asset Paths
    const val MODEL_IMAGE_FEATURES = "models/litert/sam2_image_features.tflite"
    const val MODEL_INIT_STEP = "models/litert/sam2_init_step.tflite"
    const val MODEL_TEMPORAL_STEP = "models/litert/sam2_temporal_step.tflite"

    // Buffer sizes in elements
    const val TOP_FEAT_ELEMS = 256 * 64 * 64
    const val HIGH_RES_0_ELEMS = 32 * 256 * 256
    const val HIGH_RES_1_ELEMS = 64 * 128 * 128
    const val MEM_FEAT_ELEMS = 64 * 64 * 64
    const val OBJ_PTR_ELEMS = 256
    const val MASK_1024_ELEMS = 1024 * 1024
    const val TOTAL_MEM_TOKENS = NUM_MASKMEM * 4096 + MAX_OBJ_PTRS * 4 // 28736
}

