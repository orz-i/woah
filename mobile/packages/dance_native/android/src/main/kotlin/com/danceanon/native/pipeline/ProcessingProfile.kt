package com.danceanon.native.pipeline

data class ProcessingProfile(
    val name: String,
    val inferenceStride: Int,
    val inputSize: Int = 640
) {
    companion object {
        val QUALITY = ProcessingProfile("quality", inferenceStride = 1, inputSize = 640)
        val BALANCED = ProcessingProfile("balanced", inferenceStride = 2, inputSize = 640)
        val SPEED = ProcessingProfile("speed", inferenceStride = 3, inputSize = 640)

        fun fromName(name: String?): ProcessingProfile {
            return when (name?.lowercase()?.trim()) {
                "quality" -> QUALITY
                "speed" -> SPEED
                "balanced" -> BALANCED
                else -> BALANCED
            }
        }
    }
}
