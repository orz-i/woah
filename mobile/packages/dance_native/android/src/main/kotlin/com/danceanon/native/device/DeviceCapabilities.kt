package com.danceanon.native.device

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

data class DeviceCapabilities(
    val androidApi: Int = Build.VERSION.SDK_INT,
    val gpuSupported: Boolean = true,
    val h264Encoder: Boolean = true,
    val hevcEncoder: Boolean = true,
    val maxEncodeWidth: Int = 1920,
    val maxEncodeHeight: Int = 1080,
    val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
    val recommendedProfile: String = "balanced",
    val supportedProfiles: List<String> = listOf("balanced"),
    val inferenceBackends: List<String> = listOf("litert_gpu", "litert_cpu")
) {
    companion object {
        fun detect(context: Context): DeviceCapabilities {
            val api = Build.VERSION.SDK_INT
            val cores = Runtime.getRuntime().availableProcessors()

            // 1. Check OpenGL ES support
            var isGpuSupported = true
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val glEsVersion = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
                isGpuSupported = glEsVersion >= 0x20000 // OpenGL ES 2.0+
            } catch (_: Throwable) {
                isGpuSupported = true
            }

            // 2. Query hardware encoder capabilities via MediaCodecList
            var hasH264 = false
            var hasHevc = false
            var maxWidth = 1920
            var maxHeight = 1080

            try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val codecInfos = codecList.codecInfos
                for (info in codecInfos) {
                    if (!info.isEncoder) continue
                    val types = info.supportedTypes
                    for (type in types) {
                        if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
                            hasH264 = true
                            try {
                                val caps = info.getCapabilitiesForType(type).videoCapabilities
                                if (caps != null) {
                                    maxWidth = maxOf(maxWidth, caps.supportedWidths.upper)
                                    maxHeight = maxOf(maxHeight, caps.supportedHeights.upper)
                                }
                            } catch (_: Throwable) {}
                        } else if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                            hasHevc = true
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w("DeviceCapabilities", "Failed to query MediaCodecList: ${e.message}")
                hasH264 = true
                hasHevc = api >= Build.VERSION_CODES.LOLLIPOP
            }

            val recProfile = if (cores >= 8) "balanced" else "speed"

            return DeviceCapabilities(
                androidApi = api,
                gpuSupported = isGpuSupported,
                h264Encoder = hasH264,
                hevcEncoder = hasHevc,
                maxEncodeWidth = maxWidth,
                maxEncodeHeight = maxHeight,
                cpuCores = cores,
                recommendedProfile = recProfile,
                supportedProfiles = listOf("quality", "balanced", "speed"),
                inferenceBackends = listOf("litert_gpu", "litert_cpu")
            )
        }
    }
}
