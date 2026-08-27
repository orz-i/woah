package com.danceanon.native.litert

data class LiteRtRunnerPolicy(
    val requestedAccelerator: LiteRtAccelerator = LiteRtAccelerator.GPU,
    val allowCpuFallback: Boolean = false,
    val requireWarmupSuccess: Boolean = true
) {
    companion object {
        val STRICT_GPU = LiteRtRunnerPolicy(
            requestedAccelerator = LiteRtAccelerator.GPU,
            allowCpuFallback = false,
            requireWarmupSuccess = true
        )
        val GPU_WITH_CPU_FALLBACK = LiteRtRunnerPolicy(
            requestedAccelerator = LiteRtAccelerator.GPU,
            allowCpuFallback = true,
            requireWarmupSuccess = true
        )
        val STRICT_CPU = LiteRtRunnerPolicy(
            requestedAccelerator = LiteRtAccelerator.CPU,
            allowCpuFallback = false,
            requireWarmupSuccess = true
        )
    }
}
