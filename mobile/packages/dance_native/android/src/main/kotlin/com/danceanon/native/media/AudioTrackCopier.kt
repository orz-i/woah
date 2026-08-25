package com.danceanon.native.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer

class AudioTrackCopier(
    private val context: Context,
    private val sourceUri: String
) : AutoCloseable {

    private val extractor = MediaExtractor()
    var audioTrackIndexInSource = -1
        private set
    var audioFormat: MediaFormat? = null
        private set

    fun prepare(): Boolean {
        if (sourceUri.startsWith("content://")) {
            extractor.setDataSource(context, Uri.parse(sourceUri), null)
        } else {
            extractor.setDataSource(sourceUri.removePrefix("file://"))
        }

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndexInSource = i
                audioFormat = format
                extractor.selectTrack(i)
                return true
            }
        }
        return false
    }

    fun copyToMuxer(muxer: Mp4Muxer, muxerAudioTrackIndex: Int, bufferCapacity: Int = 256 * 1024) {
        if (audioTrackIndexInSource < 0) return

        val buffer = ByteBuffer.allocateDirect(bufferCapacity)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(muxerAudioTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    override fun close() {
        try {
            extractor.release()
        } catch (_: Exception) {}
    }
}
