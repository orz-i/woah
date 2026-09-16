package com.danceanon.native.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 8_000_000,
    private val fps: Float = 30.0f,
    private val iFrameInterval: Int = 1
) : AutoCloseable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var inputSurface: Surface? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var isStarted = false

    fun prepare(): Surface {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            // MediaCodec exposes an integer nominal frame-rate hint. Round
            // 29.97/59.94 to 30/60 instead of truncating to 29/59; actual frame
            // timing remains driven by source presentation timestamps.
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        isStarted = true

        val codecName = try { codec.name } catch (_: Throwable) { "Unknown" }
        val isHw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try { codec.codecInfo.isHardwareAccelerated } catch (_: Throwable) { "Unknown" }
        } else {
            "N/A"
        }
        android.util.Log.i(
            "VideoEncoder",
            "[Telemetry] Hardware VideoEncoder started: name=$codecName, isHw=$isHw, canvas=${width}x${height}@${fps}fps, bitrate=$bitrate"
        )

        return inputSurface!!

    }

    fun drainEncoder(muxer: Mp4Muxer, endOfStream: Boolean) {
        if (!isStarted) return

        if (endOfStream) {
            codec.signalEndOfInputStream()
        }

        val timeoutUs = 10_000L
        while (true) {
            val status = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                muxer.addVideoTrack(newFormat)
            } else if (status >= 0) {
                val outputBuffer = codec.getOutputBuffer(status) ?: continue
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeVideoSample(outputBuffer, bufferInfo)
                }

                codec.releaseOutputBuffer(status, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    override fun close() {
        try {
            if (isStarted) {
                codec.stop()
            }
        } catch (_: Exception) {}
        try {
            codec.release()
        } catch (_: Exception) {}
        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        isStarted = false
    }
}
