package com.danceanon.native.litert

data class LiteRtRuntimeInfo(
    val modelName: String,
    val requestedAccelerator: LiteRtAccelerator,
    val effectiveAccelerator: LiteRtAccelerator,
    val compileMs: Long,
    val warmupMs: Long,
    val fallbackReason: String? = null,
    val inputShapes: List<List<Int>> = emptyList(),
    val outputShapes: List<List<Int>> = emptyList()
)
