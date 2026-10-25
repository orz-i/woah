package art.gaoge.dance.engine.comparison

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.view.Surface
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal class ComparisonGlCancelled : RuntimeException()

internal data class ComparisonVideoDescription(
    val width: Int, val height: Int, val rotation: Int,
    val durationUs: Long, val fps: Float, val mime: String,
    val decoder: String, val hasAudio: Boolean
)

/** Source PTS interval, not a made-up CFR index. End is exclusive. */
internal data class ComparisonFrameInterval(val startUs: Long, val endUs: Long) {
    fun contains(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs
}

/** A bounded synchronous decoder. Only the GL thread touches codec/texture state. */
internal class ComparisonCodecVideo(
    context: Context, uri: Uri, val texture: SurfaceTexture,
    callbackHandler: Handler, private val current: () -> Boolean
) : AutoCloseable {
    private data class Output(val index: Int, val ptsUs: Long, val eos: Boolean)
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val surface = Surface(texture)
    private val signal = Semaphore(0)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var pending: Output? = null
    private var inputEos = false
    private var outputEos = false
    private var displayedPtsUs = -1L
    private var displayedEndUs = -1L
    val matrix = FloatArray(16)
    val description: ComparisonVideoDescription
    var decodedFrames = 0L
        private set
    var skippedFrames = 0L
        private set
    var textureWaits = 0L
        private set

    init {
        try {
            if (uri.scheme == "content") extractor.setDataSource(context, uri, null)
            else extractor.setDataSource(uri.path ?: error("Missing local source path"))
            var videoIndex = -1
            var format: MediaFormat? = null
            var hasAudio = false
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) hasAudio = true
                if (mime.startsWith("video/") && videoIndex < 0) {
                    videoIndex = i
                    format = f
                }
            }
            check(videoIndex >= 0 && format != null) { "No video track" }
            val f = format
            val mime = f.getString(MediaFormat.KEY_MIME)!!
            val rotation = if (f.containsKey(MediaFormat.KEY_ROTATION))
                f.getInteger(MediaFormat.KEY_ROTATION) else 0
            val duration = f.getLong(MediaFormat.KEY_DURATION)
            check(duration > 0) { "Video has no finite duration" }
            val width = f.getInteger(MediaFormat.KEY_WIDTH)
            val height = f.getInteger(MediaFormat.KEY_HEIGHT)
            val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE))
                runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                    .getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) } else -1f
            // Apply rotation in the compositor, not implicitly in the producer.
            f.setInteger(MediaFormat.KEY_ROTATION, 0)
            extractor.selectTrack(videoIndex)
            texture.setDefaultBufferSize(width, height)
            texture.setOnFrameAvailableListener({ signal.release() }, callbackHandler)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            description = ComparisonVideoDescription(width, height, rotation,
                duration, fps, mime, decoder.name, hasAudio)
            decoder.configure(f, surface, null, 0)
            decoder.start()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun checkCurrent() { if (!current()) throw ComparisonGlCancelled() }

    fun seek(sourceUs: Long) {
        checkCurrent()
        val decoder = codec ?: error("Decoder closed")
        pending?.let { decoder.releaseOutputBuffer(it.index, false) }
        pending = null
        decoder.flush()
        extractor.seekTo(sourceUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputEos = false
        outputEos = false
        displayedPtsUs = -1L
        displayedEndUs = -1L
        signal.drainPermits()
    }

    private fun feed() {
        if (inputEos) return
        val decoder = codec ?: error("Decoder closed")
        val index = decoder.dequeueInputBuffer(0)
        if (index < 0) return
        val buffer = decoder.getInputBuffer(index) ?: error("No codec input buffer")
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEos = true
        } else {
            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun nextOutput(): Output? {
        if (outputEos) return null
        val decoder = codec ?: error("Decoder closed")
        val deadline = System.nanoTime() + 2_000_000_000L
        while (true) {
            checkCurrent()
            feed()
            val index = decoder.dequeueOutputBuffer(bufferInfo, 1_000L)
            if (index >= 0) {
                val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (eos) outputEos = true
                if (bufferInfo.size == 0 && eos) {
                    decoder.releaseOutputBuffer(index, false)
                    return null
                }
                decodedFrames++
                return Output(index, bufferInfo.presentationTimeUs, eos)
            }
            check(System.nanoTime() < deadline) { "Video decoder output timeout" }
        }
    }

    /** Keep the newest output <= target, and hold exactly one future output. */
    fun selectAt(sourceUs: Long): ComparisonFrameInterval {
        checkCurrent()
        val target = sourceUs.coerceIn(0, description.durationUs - 1)
        if (displayedPtsUs >= 0 && target in displayedPtsUs until displayedEndUs)
            return ComparisonFrameInterval(displayedPtsUs, displayedEndUs)
        check(displayedPtsUs < 0 || target >= displayedPtsUs) { "Backward tick requires seek" }
        val decoder = codec ?: error("Decoder closed")
        var candidate: Output? = null
        try {
            if (pending == null && !outputEos) pending = nextOutput()
            while (pending != null && pending!!.ptsUs <= target) {
                candidate?.let {
                    decoder.releaseOutputBuffer(it.index, false)
                    skippedFrames++
                }
                candidate = pending
                pending = null
                if (!candidate!!.eos) pending = nextOutput()
            }
            val selected = candidate
            if (selected != null) {
                signal.drainPermits()
                decoder.releaseOutputBuffer(selected.index, true)
                candidate = null
                val deadline = System.nanoTime() + 1_000_000_000L
                while (true) {
                    checkCurrent()
                    if (signal.tryAcquire(5, TimeUnit.MILLISECONDS)) {
                        texture.updateTexImage()
                        // Late callbacks from a flushed seek cannot relabel old pixels.
                        if (kotlin.math.abs(texture.timestamp / 1000L - selected.ptsUs) <= 1L) break
                    } else textureWaits++
                    check(System.nanoTime() < deadline) { "SurfaceTexture PTS latch timeout" }
                }
                texture.getTransformMatrix(matrix)
                displayedPtsUs = selected.ptsUs
                displayedEndUs = pending?.ptsUs ?: description.durationUs
            }
            val interval = ComparisonFrameInterval(displayedPtsUs, displayedEndUs)
            check(interval.contains(target)) { "Decoded frame does not cover requested source PTS" }
            return interval
        } finally {
            candidate?.let { decoder.releaseOutputBuffer(it.index, false) }
        }
    }

    override fun close() {
        runCatching { texture.setOnFrameAvailableListener(null) }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { extractor.release() }
        runCatching { surface.release() }
    }
}
