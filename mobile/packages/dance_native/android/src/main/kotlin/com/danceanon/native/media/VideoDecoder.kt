package com.danceanon.native.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import java.nio.ByteBuffer

class VideoDecoder(
    private val context: Context,
    private val sourceUri: String,
    private val outputSurface: Surface? = null
) : AutoCloseable {

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    var videoTrackIndex = -1
        private set
    var videoFormat: MediaFormat? = null
        private set
    var durationUs: Long = 0L
        private set

    private var isEOSInput = false
    private val bufferInfo = MediaCodec.BufferInfo()

    fun prepare() {
        if (sourceUri.startsWith("content://")) {
            extractor.setDataSource(context, Uri.parse(sourceUri), null)
        } else {
            extractor.setDataSource(sourceUri.removePrefix("file://"))
        }

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = format
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = format.getLong(MediaFormat.KEY_DURATION)
                }
                extractor.selectTrack(i)
                break
            }
        }

        if (videoTrackIndex < 0 || videoFormat == null) {
            throw IllegalStateException("No video track found in $sourceUri")
        }

        val mime = videoFormat!!.getString(MediaFormat.KEY_MIME) ?: "video/avc"
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(videoFormat, outputSurface, null, 0)
        decoder.start()
        codec = decoder
    }

    fun feedInputBuffer(timeoutUs: Long = 10_000L): Boolean {
        if (isEOSInput || codec == null) return false

        val inputIndex = codec!!.dequeueInputBuffer(timeoutUs)
        if (inputIndex >= 0) {
            val inputBuffer = codec!!.getInputBuffer(inputIndex) ?: return false
            inputBuffer.clear()
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize < 0) {
                codec!!.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                isEOSInput = true
                return false
            } else {
                val pts = extractor.sampleTime
                codec!!.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                extractor.advance()
                return true
            }
        }
        return false
    }

    fun drainOutputBuffer(timeoutUs: Long = 10_000L, onFrameReady: (ptsUs: Long, isEOS: Boolean) -> Unit) {
        val decoder = codec ?: return

        while (true) {
            val status = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (status >= 0) {
                val isEOS = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val pts = bufferInfo.presentationTimeUs

                // Release to render on surface if outputSurface was provided
                decoder.releaseOutputBuffer(status, outputSurface != null)

                onFrameReady(pts, isEOS)

                if (isEOS) break
            }
        }
    }

    override fun close() {
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        try {
            extractor.release()
        } catch (_: Exception) {}
        codec = null
    }
}
