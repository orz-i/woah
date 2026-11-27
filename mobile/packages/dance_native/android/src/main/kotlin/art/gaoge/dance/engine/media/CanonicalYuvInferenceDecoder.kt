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
    private val rgbaBuffer by lazy {
        ByteBuffer.allocateDirect(modelInputSize * modelInputSize * 4).apply {
            order(ByteOrder.nativeOrder())
        }
    }
    private val conversionWorkspace = CanonicalYuvToRgba.Workspace(modelInputSize)
    private val detailBuffer by lazy { ByteBuffer.allocateDirect(modelInputSize*modelInputSize*4) }

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
        try {
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            codec = decoder
        }catch(error:Throwable){
            try{decoder.release()}catch(_:Throwable){}
            throw error
        }
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

    /** Exact-PTS decode into an exclusively owned caller buffer. The
     * comparison producer alternates two buffers; other decoder callers
     * continue to use the original single reusable buffer contract. */
    internal fun decodeRgbaAtPtsInto(
        targetPtsUs:Long, mapper:ModelCoordinateMapper, destination:ByteBuffer
    ):ByteBuffer {
        require(destination.isDirect && destination.capacity()==modelInputSize*modelInputSize*4)
        return decodeRgbaFrame(targetPtsUs,mapper,true,destination).rgbaBuffer
    }

    /** Capture only the original planes for a bounded reverse ROI block.
     * No full-frame resize or model inference on this auxiliary path. */
    internal fun captureSourceAtPts(pts:Long,mapper:ModelCoordinateMapper,
        source:CanonicalYuvToRgba.OwnedSource) {
        source.clear()
        decodeRgbaFrame(pts,mapper,true,convertFullFrame=false,onSource={image,_->
            source.capture(image,rotationDegrees,
                colorInt(outputFormat,MediaFormat.KEY_COLOR_STANDARD),
                colorInt(outputFormat,MediaFormat.KEY_COLOR_RANGE))
        })
    }

    internal fun decodeDetailAtPts(targetPtsUs:Long,mapper:ModelCoordinateMapper,roi:IntArray,sceneWidth:Int,sceneHeight:Int):ByteBuffer {
        require(roi.size==4 && mapper.srcWidth==roi[2] && mapper.srcHeight==roi[3])
        return decodeRgbaFrame(targetPtsUs,mapper,true,rgbaBuffer,roi+intArrayOf(sceneWidth,sceneHeight)).rgbaBuffer
    }

    /** Borrow the exact source image only during this synchronous callback.
     * No Image or crop function may be used after the codec buffer is released. */
    internal fun decodeRgbaWithDetailAtPts(
        pts:Long,mapper:ModelCoordinateMapper,
        consume:(ByteBuffer,(ModelCoordinateMapper,IntArray)->ByteBuffer)->Unit
    ) {
        decodeRgbaFrame(pts,mapper,true,onSource={image,rgba->
            var active=true
            try {
                consume(rgba){local,roi->
                    check(active)
                    require(roi.size==4 && local.srcWidth==roi[2] && local.srcHeight==roi[3])
                    CanonicalYuvToRgba.convert(image,detailBuffer,local,rotationDegrees,
                        colorInt(outputFormat,MediaFormat.KEY_COLOR_STANDARD),
                        colorInt(outputFormat,MediaFormat.KEY_COLOR_RANGE),conversionWorkspace,
                        roi+intArrayOf(mapper.srcWidth,mapper.srcHeight))
                    detailBuffer
                }
            }finally{active=false}
        })
    }

    /** Reposition an already-running synchronous codec after all borrowed
     * images have been released. The next decode still requires exact PTS. */
    internal fun seekDetailTo(pts:Long){
        require(pts>=0)
        val decoder=checkNotNull(codec)
        extractor.seekTo(pts,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()
        inputEos=false;outputEos=false
    }

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
        requireExactPts: Boolean,
        destination:ByteBuffer=rgbaBuffer,
        detail:IntArray?=null,
        onSource:((Image,ByteBuffer)->Unit)?=null,
        convertFullFrame:Boolean=true
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
                            if(convertFullFrame) CanonicalYuvToRgba.convert(
                                image = image,
                                output = destination,
                                mapper = mapper,
                                rotationDegrees = rotationDegrees,
                                colorStandard = colorInt(outputFormat, MediaFormat.KEY_COLOR_STANDARD),
                                colorRange = colorInt(outputFormat, MediaFormat.KEY_COLOR_RANGE),
                                workspace = conversionWorkspace,detail=detail
                            )
                            onSource?.invoke(image,destination)
                            return DecodedRgbaFrame(ptsUs = ptsUs, rgbaBuffer = destination)
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
        workspace: Workspace = Workspace(mapper.modelInputSize),detail:IntArray?=null) {
        require(image.planes.size >= 3)
        val planes = image.planes.take(3)
        convertPlanes(image.width,image.height,image.cropRect,
            planes.map{it.buffer.duplicate().slice()},
            planes.flatMap{listOf(it.rowStride,it.pixelStride)}.toIntArray(),
            output,mapper,rotationDegrees,colorStandard,colorRange,workspace,detail)
    }

    /** One exclusively owned reverse-block source slot. Copy the exact decoder plane
     * bytes and strides, never the model-sized RGBA. No codec Image survives
     * the decode callback, and no raw source bytes are written to disk.
     * Oversized frames fall back to the original auxiliary decoder. */
    internal class OwnedSource(private val maxBytes:Int=8*1024*1024) {
        init { require(maxBytes in 1..8*1024*1024) }
        private var storage:ByteBuffer?=null
        private var planes:List<ByteBuffer> = emptyList()
        private var strides=IntArray(0)
        private var width=0
        private var height=0
        private var crop=android.graphics.Rect()
        private var rotation=0
        private var standard:Int?=null
        private var range:Int?=null
        internal val valid get()=planes.size==3
        internal val capacity get()=storage?.capacity() ?: 0
        fun clear(){planes=emptyList()}
        fun release(){clear();storage=null}
        fun capture(image:Image,rotationDegrees:Int,colorStandard:Int?,colorRange:Int?) {
            clear()
            require(image.planes.size>=3)
            val src=image.planes.take(3)
            val slices=src.map{it.buffer.duplicate().slice()}
            val size=slices.sumOf{it.remaining().toLong()}
            if(size<=0 || size>maxBytes)return
            // One allocation per slot, reused between blocks. If decoder
            // plane sizes grow, fall back rather than transiently exceeding
            // the block's direct-buffer allocation budget.
            // No crop views retain a previous frame.
            val existing=storage
            if(existing!=null && existing.capacity()<size)return
            val buffer=existing ?: ByteBuffer.allocateDirect(size.toInt()).also{storage=it}
            buffer.clear()
            val captured=ArrayList<ByteBuffer>(3)
            for(plane in slices){
                val start=buffer.position()
                buffer.put(plane)
                val end=buffer.position()
                captured.add(buffer.duplicate().apply{position(start);limit(end)}.slice())
            }
            width=image.width;height=image.height;crop=android.graphics.Rect(image.cropRect)
            strides=src.flatMap{listOf(it.rowStride,it.pixelStride)}.toIntArray()
            rotation=rotationDegrees;standard=colorStandard;range=colorRange
            planes=captured
        }

        // Crop is synchronous on the consumer, before the slot is reused.
        // Even an escaped function cannot read the next source frame.
        fun withCrop(scene:ModelCoordinateMapper,output:ByteBuffer,
            consume:(((ModelCoordinateMapper,IntArray)->ByteBuffer)?)->Unit){
            if(!valid){consume(null);return}
            var active=true
            try {
                consume { local,roi ->
                    check(active && valid)
                    require(roi.size==4 && local.srcWidth==roi[2] && local.srcHeight==roi[3])
                    convertPlanes(width,height,crop,planes,strides,output,local,rotation,standard,range,
                        Workspace(local.modelInputSize),roi+intArrayOf(scene.srcWidth,scene.srcHeight))
                    output
                }
            }finally{active=false;clear()}
        }
    }

    private fun convertPlanes(width:Int,height:Int,sourceCrop:android.graphics.Rect,
        planes:List<ByteBuffer>,strides:IntArray,output:ByteBuffer,mapper:ModelCoordinateMapper,
        rotationDegrees:Int,colorStandard:Int?,colorRange:Int?,workspace:Workspace,detail:IntArray?){
        require(planes.size==3 && strides.size==6)
        require(workspace.modelInputSize == mapper.modelInputSize)
        val crop = android.graphics.Rect(sourceCrop)
        if(detail!=null){
            val (x,y,w,h)=detail
            val cw=crop.width();val ch=crop.height()
            val rotation=((rotationDegrees%360)+360)%360
            val dw=if(rotation%180==0)cw else ch
            val dh=if(rotation%180==0)ch else cw
            require(detail.size==6 && detail[4]==dw && detail[5]==dh &&
                x>=0 && y>=0 && w>0 && h>0 && x+w<=dw && y+h<=dh)
            val local=when(rotation){
                0->android.graphics.Rect(x,y,x+w,y+h)
                90->android.graphics.Rect(y,ch-x-w,y+h,ch-x)
                180->android.graphics.Rect(cw-x-w,ch-y-h,cw-x,ch-y)
                270->android.graphics.Rect(cw-y-h,x,cw-y,x+w)
                else->error("Invalid detail rotation")
            }
            local.offset(crop.left,crop.top)
            crop.set(local)
        }
        val matrix = when (colorStandard ?: MediaFormat.COLOR_STANDARD_BT709) {
            MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> 0
            MediaFormat.COLOR_STANDARD_BT2020 -> 2
            else -> 1
        }
        val metadata = intArrayOf(width, height, crop.left, crop.top, crop.right, crop.bottom,
            mapper.srcWidth, mapper.srcHeight, ((rotationDegrees % 360) + 360) % 360,
            mapper.modelInputSize, mapper.modelInputSize, 1, matrix,
            if (colorRange == MediaFormat.COLOR_RANGE_FULL) 1 else 0, 114,
            strides[0], strides[1], strides[2], strides[3], strides[4], strides[5])
        art.gaoge.dance.engine.core.FrameKernel.yuv420(planes[0], planes[1], planes[2], metadata, output)
        output.rewind()
    }
}
