package com.danceanon.native.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.danceanon.native.bridge.VideoInfoDto
import java.io.File

object VideoProbe {

    fun probe(context: Context, uriString: String): VideoInfoDto {
        if (uriString.isBlank()) {
            throw com.danceanon.native.bridge.DanceNativeException(
                com.danceanon.native.bridge.DanceNativeException.INVALID_ARGUMENT,
                "Video URI cannot be empty"
            )
        }

        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()

        try {
            // Configure DataSource for file path or content URI
            try {
                if (uriString.startsWith("content://")) {
                    val uri = Uri.parse(uriString)
                    retriever.setDataSource(context, uri)
                    extractor.setDataSource(context, uri, null)
                } else {
                    val cleanPath = uriString.removePrefix("file://")
                    val file = File(cleanPath)
                    if (!file.exists() || !file.canRead()) {
                        throw com.danceanon.native.bridge.DanceNativeException(
                            com.danceanon.native.bridge.DanceNativeException.VIDEO_OPEN_FAILED,
                            "Video file does not exist or is not readable: $cleanPath"
                        )
                    }
                    retriever.setDataSource(cleanPath)
                    extractor.setDataSource(cleanPath)
                }
            } catch (e: com.danceanon.native.bridge.DanceNativeException) {
                throw e
            } catch (e: Exception) {
                throw com.danceanon.native.bridge.DanceNativeException(
                    com.danceanon.native.bridge.DanceNativeException.VIDEO_OPEN_FAILED,
                    "Failed to open video source: $uriString (${e.message})",
                    e
                )
            }

            // 1. Extract metadata via MediaMetadataRetriever
            val rawWidthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val rawHeightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val hasAudioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)

            var codedWidth = rawWidthStr?.toIntOrNull() ?: 0
            var codedHeight = rawHeightStr?.toIntOrNull() ?: 0
            val rotation = (rotationStr?.toIntOrNull() ?: 0) % 360
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            var hasAudio = hasAudioStr?.equals("yes", ignoreCase = true) ?: false

            var videoMime: String? = null
            var audioMime: String? = null
            var fps = 0.0
            var foundVideoTrack = false

            // 2. Deep inspection via MediaExtractor tracks
            val trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("video/")) {
                    foundVideoTrack = true
                    videoMime = mime
                    if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                        codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                    } else if (format.containsKey("frame-rate")) {
                        fps = format.getFloat("frame-rate").toDouble()
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    audioMime = mime
                }
            }

            if (!foundVideoTrack || videoMime == null || codedWidth <= 0 || codedHeight <= 0) {
                throw com.danceanon.native.bridge.DanceNativeException(
                    com.danceanon.native.bridge.DanceNativeException.VIDEO_TRACK_NOT_FOUND,
                    "No valid video track found in media file: $uriString"
                )
            }

            // 3. Fallback FPS estimation via PTS difference if needed
            if (fps <= 0.0 || fps > 240.0) {
                fps = estimateFpsFromExtractor(extractor)
            }
            if (fps <= 0.0) {
                fps = 30.0
            }

            // 4. Calculate visual display dimensions with rotation applied
            val displayWidth = if (rotation == 90 || rotation == 270) codedHeight else codedWidth
            val displayHeight = if (rotation == 90 || rotation == 270) codedWidth else codedHeight

            return VideoInfoDto(
                codedWidth = codedWidth.toLong(),
                codedHeight = codedHeight.toLong(),
                displayWidth = displayWidth.toLong(),
                displayHeight = displayHeight.toLong(),
                fps = fps,
                durationMs = durationMs,
                rotation = rotation.toLong(),
                videoCodec = videoMime,
                audioCodec = audioMime,
                hasAudio = hasAudio
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {}
            try {
                extractor.release()
            } catch (_: Throwable) {}
        }
    }

    private fun estimateFpsFromExtractor(extractor: MediaExtractor): Double {
        try {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i)
                    val ptsList = mutableListOf<Long>()
                    while (ptsList.size < 10 && extractor.sampleTime >= 0) {
                        ptsList.add(extractor.sampleTime)
                        extractor.advance()
                    }
                    if (ptsList.size >= 2) {
                        val deltas = mutableListOf<Long>()
                        for (k in 0 until ptsList.size - 1) {
                            val diff = ptsList[k + 1] - ptsList[k]
                            if (diff > 0) deltas.add(diff)
                        }
                        if (deltas.isNotEmpty()) {
                            val avgDeltaUs = deltas.average()
                            if (avgDeltaUs > 0) {
                                return 1_000_000.0 / avgDeltaUs
                            }
                        }
                    }
                    break
                }
            }
        } catch (_: Throwable) {}
        return 30.0
    }
}
