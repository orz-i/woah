package com.danceanon.native.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

class Mp4Muxer(
    outputPath: String,
    private val expectedTracks: Int = 2
) : AutoCloseable {

    private val tempFile = File(outputPath)
    private val muxer: MediaMuxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    var videoTrackIndex: Int = -1
        private set
    var audioTrackIndex: Int = -1
        private set
    private var isStarted = false
    private var addedTracks = 0
    private val lock = Any()

    fun addVideoTrack(format: MediaFormat): Int = synchronized(lock) {
        if (isStarted) throw IllegalStateException("Muxer already started")
        videoTrackIndex = muxer.addTrack(format)
        addedTracks++
        checkAndStartLocked()
        return videoTrackIndex
    }

    fun addAudioTrack(format: MediaFormat): Int = synchronized(lock) {
        if (isStarted) throw IllegalStateException("Muxer already started")
        audioTrackIndex = muxer.addTrack(format)
        addedTracks++
        checkAndStartLocked()
        return audioTrackIndex
    }

    private fun checkAndStartLocked() {
        if (!isStarted && addedTracks >= expectedTracks) {
            muxer.start()
            isStarted = true
        }
    }

    fun forceStartIfSingleTrack() = synchronized(lock) {
        if (!isStarted && addedTracks > 0) {
            muxer.start()
            isStarted = true
        }
    }

    fun writeVideoSample(byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            check(isStarted) { "Cannot write video sample: Muxer has not started" }
            check(videoTrackIndex >= 0) { "Video track has not been added to muxer" }
            if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= 0) {
                muxer.writeSampleData(videoTrackIndex, byteBuf, bufferInfo)
            }
        }
    }

    fun writeAudioSample(byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            check(isStarted) { "Cannot write audio sample: Muxer has not started" }
            check(audioTrackIndex >= 0) { "Audio track has not been added to muxer" }
            if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= 0) {
                muxer.writeSampleData(audioTrackIndex, byteBuf, bufferInfo)
            }
        }
    }

    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            check(isStarted) { "Cannot write sample data: Muxer has not started" }
            check(trackIndex >= 0) { "Invalid track index: $trackIndex" }
            if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= 0) {
                muxer.writeSampleData(trackIndex, byteBuf, bufferInfo)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            try {
                if (isStarted) {
                    muxer.stop()
                }
            } catch (_: Exception) {
            } finally {
                try {
                    muxer.release()
                } catch (_: Exception) {}
                isStarted = false
            }
        }
    }
}
