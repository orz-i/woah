package com.danceanon.native.pipeline

import com.danceanon.native.sam2.Sam2TensorContract

data class ProcessingProfile(
    val name: String,
    val inferenceStride: Int,
    val inputSize: Int = 640,
    val useSam2: Boolean = false
) {
    companion object {
        val QUALITY = ProcessingProfile("quality", inferenceStride = 1, inputSize = 640)
        val BALANCED = ProcessingProfile("balanced", inferenceStride = 2, inputSize = 640)
        val SPEED = ProcessingProfile("speed", inferenceStride = 3, inputSize = 640)

        val SAM2_QUALITY = ProcessingProfile("sam2_quality", inferenceStride = 1, inputSize = Sam2TensorContract.IMAGE_SIZE, useSam2 = true)
        val SAM2_BALANCED = ProcessingProfile("sam2_balanced", inferenceStride = 2, inputSize = Sam2TensorContract.IMAGE_SIZE, useSam2 = true)
        val SAM2_SPEED = ProcessingProfile("sam2_speed", inferenceStride = 3, inputSize = Sam2TensorContract.IMAGE_SIZE, useSam2 = true)
        val SAM2 = SAM2_BALANCED

        fun fromName(name: String?): ProcessingProfile {
            return when (name?.lowercase()?.trim()) {
                "sam2", "sam2_balanced" -> SAM2_BALANCED
                "sam2_quality" -> SAM2_QUALITY
                "sam2_speed" -> SAM2_SPEED
                "quality" -> QUALITY
                "speed" -> SPEED
                "balanced" -> BALANCED
                else -> QUALITY
            }
        }
    }

}

