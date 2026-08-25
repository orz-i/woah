package com.danceanon.native.device

import android.os.Build

data class DeviceCapabilities(
    val androidApi: Int = Build.VERSION.SDK_INT,
    val gpuSupported: Boolean = true,
    val h264Encoder: Boolean = true,
    val hevcEncoder: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP,
    val maxEncodeWidth: Int = 3840,
    val maxEncodeHeight: Int = 2160,
    val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
    val recommendedProfile: String = if (Runtime.getRuntime().availableProcessors() >= 8) "balanced" else "speed"
)
