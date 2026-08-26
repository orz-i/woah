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
        val SAM2 = ProcessingProfile("sam2", inferenceStride = 1, inputSize = Sam2TensorContract.IMAGE_SIZE, useSam2 = true)

        fun fromName(name: String?): ProcessingProfile {
            return when (name?.lowercase()?.trim()) {
                "sam2" -> SAM2
                "quality" -> QUALITY
                "speed" -> SPEED
                "balanced" -> BALANCED
                else -> QUALITY
            }
        }

    }
}

