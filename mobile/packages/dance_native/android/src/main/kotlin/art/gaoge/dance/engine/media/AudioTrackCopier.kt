package art.gaoge.dance.engine.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer

internal fun muxableAudioSampleIsSync(sampleFlags: Int): Boolean {
    // This passthrough has no decryptor or partial-access-unit assembler.
    require(sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC.inv() == 0) {
        "Cannot mux encrypted, partial, or unknown audio sample flags"
    }
    return sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
}

class AudioTrackCopier(
    private val context: Context,
    private val sourceUri: String,
    private val startUs: Long = 0L,
    private val endUs: Long? = null
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
                if (startUs > 0L) {
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
                return true
            }
        }
        return false
    }

    fun copyToMuxer(muxer: Mp4Muxer, bufferCapacity: Int = 256 * 1024) {
        if (audioTrackIndexInSource < 0) return

        val buffer = ByteBuffer.allocateDirect(bufferCapacity)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            val sampleTimeUs = extractor.sampleTime
            if (sampleTimeUs < startUs) {
                extractor.advance()
                continue
            }
            if (endUs != null && sampleTimeUs >= endUs) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = (sampleTimeUs - startUs).coerceAtLeast(0L)
            // Extractor and Codec flag values are not interchangeable: encrypted
            // (2) would become codec-config, partial-frame (4) would become EOS.
            bufferInfo.flags = if (muxableAudioSampleIsSync(extractor.sampleFlags)) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }

            muxer.writeAudioSample(buffer, bufferInfo)
            extractor.advance()
        }
    }

    override fun close() {
        try {
            extractor.release()
        } catch (_: Exception) {}
    }
}
