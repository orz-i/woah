package art.gaoge.dance.engine.media

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Experimental inference-only decoder used to remove SurfaceTexture/OES color conversion from
 * the YOLO input path. Rendering keeps using the existing Surface decoder.
 *
 * The decoder exposes YUV_420_888 Images and converts them with CPU integer color math into the
 * same 640x640, bottom-up RGBA contract consumed by segmentGlReadbackRgbaSync(). No decoded frame
 * from this class is used for rendering or identity decisions other than as YOLO pixel input.
 */
class CanonicalYuvInferenceDecoder(
    private val context: Context,
    private val sourceUri: String,
    private val rotationDegrees: Int,
    private val modelInputSize: Int = 640,
    private val startUs: Long = 0L
) : AutoCloseable {

    data class RuntimeInfo(
        val codecName: String,
        val colorStandard: Int?,
        val colorRange: Int?,
        val colorTransfer: Int?
    )

    data class DecodedRgbaFrame(
        val ptsUs: Long,
        val rgbaBuffer: ByteBuffer
    )

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var inputEos = false
    private var outputEos = false
    private var outputFormat: MediaFormat? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private val rgbaBuffer = ByteBuffer.allocateDirect(modelInputSize * modelInputSize * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val conversionWorkspace = CanonicalYuvToRgba.Workspace(modelInputSize)

    var runtimeInfo: RuntimeInfo? = null
        private set

    fun prepare() {
        if (sourceUri.startsWith("content://")) {
            extractor.setDataSource(context, Uri.parse(sourceUri), null)
        } else {
            extractor.setDataSource(sourceUri.removePrefix("file://"))
        }

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) {
                trackIndex = i
                inputFormat = format
                break
            }
        }
        require(trackIndex >= 0 && inputFormat != null) { "No video track for canonical inference decode" }

        extractor.selectTrack(trackIndex)
        if (startUs > 0L) {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: error("Missing video MIME for canonical inference decode")
        inputFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        codec = decoder
        outputFormat = null
        updateRuntimeInfo(decoder.name, inputFormat)
    }

    /**
     * Sequentially decodes until [targetPtsUs] and returns a reused bottom-up RGBA buffer.
     * Older decoded frames are discarded when the production YOLO stride skips them.
     */
    fun decodeRgbaAtPts(
        targetPtsUs: Long,
        mapper: ModelCoordinateMapper
    ): ByteBuffer = decodeRgbaFrame(
        targetPtsUs = targetPtsUs,
        mapper = mapper,
        requireExactPts = true
    ).rgbaBuffer

    /**
     * Decodes the first display frame at or after [targetPtsUs]. This is used by one-shot
     * analysis where a millisecond trim boundary is not guaranteed to be an exact source PTS.
     */
    fun decodeRgbaAtOrAfterPts(
        targetPtsUs: Long,
        mapper: ModelCoordinateMapper
    ): DecodedRgbaFrame = decodeRgbaFrame(
        targetPtsUs = targetPtsUs,
        mapper = mapper,
        requireExactPts = false
    )

    private fun decodeRgbaFrame(
        targetPtsUs: Long,
        mapper: ModelCoordinateMapper,
        requireExactPts: Boolean
    ): DecodedRgbaFrame {
        val decoder = codec ?: error("Canonical inference decoder not prepared")
        var emptyOutputCount = 0

        while (!outputEos && emptyOutputCount < 300) {
            feedAvailableInput(decoder)

            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    emptyOutputCount++
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = decoder.outputFormat
                    updateRuntimeInfo(decoder.name, outputFormat)
                    emptyOutputCount = 0
                }
                outputIndex >= 0 -> {
                    emptyOutputCount = 0
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val ptsUs = bufferInfo.presentationTimeUs
                    if (isEos) outputEos = true

                    var image: Image? = null
                    try {
                        if (!isEos && ptsUs >= targetPtsUs) {
                            if (requireExactPts && ptsUs != targetPtsUs) {
                                error("Canonical decoder PTS mismatch: requested=$targetPtsUs decoded=$ptsUs")
                            }
                            image = decoder.getOutputImage(outputIndex)
                                ?: error("Canonical decoder returned null YUV image at pts=$ptsUs")
                            CanonicalYuvToRgba.convert(
                                image = image,
                                output = rgbaBuffer,
                                mapper = mapper,
                                rotationDegrees = rotationDegrees,
                                colorStandard = colorInt(outputFormat, MediaFormat.KEY_COLOR_STANDARD),
                                colorRange = colorInt(outputFormat, MediaFormat.KEY_COLOR_RANGE),
                                workspace = conversionWorkspace
                            )
                            return DecodedRgbaFrame(ptsUs = ptsUs, rgbaBuffer = rgbaBuffer)
                        }
                    } finally {
                        try { image?.close() } catch (_: Throwable) {}
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        error("Canonical decoder timed out before pts=$targetPtsUs")
    }

    private fun feedAvailableInput(decoder: MediaCodec) {
        if (inputEos) return
        while (true) {
            val inputIndex = decoder.dequeueInputBuffer(0L)
            if (inputIndex < 0) return
            val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize < 0) {
                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                inputEos = true
                return
            }
            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun updateRuntimeInfo(codecName: String, format: MediaFormat?) {
        runtimeInfo = RuntimeInfo(
            codecName = codecName,
            colorStandard = colorInt(format, MediaFormat.KEY_COLOR_STANDARD),
            colorRange = colorInt(format, MediaFormat.KEY_COLOR_RANGE),
            colorTransfer = colorInt(format, MediaFormat.KEY_COLOR_TRANSFER)
        )
    }

    private fun colorInt(format: MediaFormat?, key: String): Int? =
        if (format != null && format.containsKey(key)) format.getInteger(key) else null

    override fun close() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { extractor.release() } catch (_: Throwable) {}
        codec = null
    }
}

/** Image ownership/metadata transport only; sampling and color math live in Rust. */
internal object CanonicalYuvToRgba {
    internal class Workspace(val modelInputSize: Int)
    fun convert(image: Image, output: ByteBuffer, mapper: ModelCoordinateMapper,
        rotationDegrees: Int, colorStandard: Int?, colorRange: Int?,
        workspace: Workspace = Workspace(mapper.modelInputSize)) {
        require(image.planes.size >= 3)
        require(workspace.modelInputSize == mapper.modelInputSize)
        val crop = image.cropRect
        val matrix = when (colorStandard ?: MediaFormat.COLOR_STANDARD_BT709) {
            MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> 0
            MediaFormat.COLOR_STANDARD_BT2020 -> 2
            else -> 1
        }
        val metadata = intArrayOf(image.width, image.height, crop.left, crop.top, crop.right, crop.bottom,
            mapper.srcWidth, mapper.srcHeight, ((rotationDegrees % 360) + 360) % 360,
            mapper.modelInputSize, mapper.modelInputSize, 1, matrix,
            if (colorRange == MediaFormat.COLOR_RANGE_FULL) 1 else 0, 114,
            image.planes[0].rowStride, image.planes[0].pixelStride,
            image.planes[1].rowStride, image.planes[1].pixelStride,
            image.planes[2].rowStride, image.planes[2].pixelStride)
        val planes = image.planes.take(3).map { it.buffer.duplicate().slice() }
        art.gaoge.dance.engine.core.FrameKernel.yuv420(planes[0], planes[1], planes[2], metadata, output)
        output.rewind()
    }
}
