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
    const val LOW_RES_MASK_SIZE = 256
    const val FEAT_SIZE = 64

    const val MASK_THRESHOLD = 0.15f
    const val BBOX_EXPAND_RATIO = 0.05f

    val NORM_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val NORM_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
}
