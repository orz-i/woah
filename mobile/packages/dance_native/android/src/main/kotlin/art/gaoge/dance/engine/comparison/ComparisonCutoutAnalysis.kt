package art.gaoge.dance.engine.comparison

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import art.gaoge.dance.engine.bridge.comparison.ComparisonCutoutPreviewDto
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.PersonDetection
import art.gaoge.dance.engine.inference.YoloLiteRtSegmenter
import art.gaoge.dance.engine.media.CanonicalYuvInferenceDecoder
import kotlinx.coroutines.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

internal data class ComparisonCutoutSeed(val ptsUs: Long, val x: Float, val y: Float) {
    init { require(ptsUs >= 0 && x.isFinite() && y.isFinite() && x >= 0 && x < 1 && y >= 0 && y < 1) }
}

/** Immutable, source-scoped preview cache. A known missing observation has no layer. */
internal class ComparisonCutoutTrackCache(layers: Map<Long, ComparisonCutoutLayer?>) {
    private val entries = layers.toMap()
    val frameCount: Int get() = entries.size
    val visibleCount: Int get() = entries.values.count { it != null }
    fun layer(ptsUs: Long): ComparisonCutoutLayer? = entries[ptsUs]
}

internal data class ComparisonCutoutCache(val reference: ComparisonCutoutTrackCache,
    val target: ComparisonCutoutTrackCache) {
    private val layout=java.util.concurrent.atomic.AtomicReference<ComparisonCanvasLayout?>(null)
    fun setLayout(value:ComparisonCanvasLayout?): ComparisonCanvasLayout? = layout.getAndSet(value)
    fun frame(referencePts: Long, targetPts: Long) =
        ComparisonCutoutFrame(reference.layer(referencePts),target.layer(targetPts),layout.get())
}

/** Bounded M0 analysis for one common interval. No decoding/inference on the GL
 * thread. Both directions are anchored at the same real selected source sample. */
internal class ComparisonCutoutAnalysis(private val context: Context) {
    companion object {
        const val MAX_FRAMES = 300
        private const val MAX_PREFIX_BYTES = 32 * 1024 * 1024L
        private const val ALPHA_EDGE = 256

        fun window(sorted: List<Long>, start: Long, endInclusive: Long, anchor: Long): List<Long> {
            require(start >= 0 && endInclusive >= start && anchor >= 0)
            val first = sorted.binarySearch(start).let { if(it >= 0) it else -it-2 }
            require(first >= 0) { "选区首帧不可读取" }
            val last = sorted.binarySearch(endInclusive).let { if(it >= 0) it else -it-2 }
            require(last >= first && last-first+1 <= MAX_FRAMES) { "抠像画布预览目前每段最多支持 300 帧" }
            val result = sorted.subList(first,last+1)
            require(result.binarySearch(anchor) >= 0) { "选人画面已变化，请暂停后重试" }
            return result.toList()
        }
    }

    private fun checkCurrent(current: () -> Boolean) {
        if(!current()) throw CancellationException("Cutout analysis cancelled")
    }

    private fun timestamps(uri: String, current: () -> Boolean): List<Long> {
        val extractor = MediaExtractor()
        try {
            val source = Uri.parse(uri)
            if(source.scheme == "content") extractor.setDataSource(context,source,null)
            else extractor.setDataSource(source.path ?: uri)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("视频没有画面轨道")
            extractor.selectTrack(index)
            val pts = java.util.TreeSet<Long>()
            var scanned = 0
            while(extractor.sampleTime >= 0) {
                checkCurrent(current)
                require(++scanned <= 1_000_000) { "视频帧数超出预览分析范围" }
                pts.add(extractor.sampleTime)
                if(!extractor.advance()) break
            }
            return pts.toList()
        } finally { extractor.release() }
    }

    internal class Observation(val pts: Long, persons: List<PersonDetection>, mapper: ModelCoordinateMapper) {
        val transform = floatArrayOf(mapper.scale,mapper.padLeft,mapper.padTop,mapper.srcWidth.toFloat(),
            mapper.srcHeight.toFloat(),mapper.modelInputSize.toFloat(),mapper.protoSize.toFloat())
        val area = mapper.protoSize * mapper.protoSize
        val bounds = persons.map { it.bbox }
        val rows = FloatArray(persons.size*5)
        val masks: ByteArray
        init {
            require(persons.size<=256)
            masks = ByteArray(persons.size*area)
            persons.forEachIndexed { i,d ->
                val b=d.bbox
                floatArrayOf(b.left,b.top,b.right,b.bottom,d.confidence).copyInto(rows,i*5)
                val m=requireNotNull(d.mask)
                require(m.width==mapper.protoSize && m.height==mapper.protoSize)
                m.buffer.duplicate().apply { rewind();get(masks,i*area,area) }
            }
        }
        fun select(seed: ComparisonCutoutSeed) =
            FrameKernel.cutoutSelectNative(masks,transform,seed.x,seed.y)
        fun track(session: FrameKernel.CutoutTrack, seed: Int = -1) =
            session.step(pts,seed,rows,masks,transform)
        private fun mask(index: Int) = masks.copyOfRange(index*area,(index+1)*area)
        fun alpha(index: Int, width: Int,height: Int) =
            FrameKernel.cutoutAlphaNative(mask(index),transform,width,height)
        fun thumbnail(index: Int, rgba: ByteArray): ComparisonCutoutPreviewDto {
            val packed=requireNotNull(FrameKernel.cutoutThumbnailNative(mask(index),transform,rgba,true))
            val w=packed[0];val h=packed[1]
            require(w>0 && h>0 && packed.size==w*h+2)
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            try {
                bitmap.setPixels(packed,2,w,0,0,w,h)
                val bytes=ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))
                return ComparisonCutoutPreviewDto(pts,bytes.toByteArray(),w.toLong(),h.toLong())
            } finally { bitmap.recycle() }
        }
    }

    internal data class Inspection(val seed: ComparisonCutoutSeed, val observation: Observation,
        val index: Int, val preview: ComparisonCutoutPreviewDto, val sourceDigest: ByteArray)

    private fun digest(frame: ByteBuffer): ByteArray = MessageDigest.getInstance("SHA-256").let {
        it.update(frame.duplicate().apply{rewind()});it.digest()
    }

    fun inspect(uri: String,description: ComparisonVideoDescription,seed: ComparisonCutoutSeed,
        segmenter: YoloLiteRtSegmenter,current: () -> Boolean): Inspection {
        checkCurrent(current)
        val rotated=description.rotation%180!=0
        val mapper=ModelCoordinateMapper(if(rotated)description.height else description.width,
            if(rotated)description.width else description.height)
        return CanonicalYuvInferenceDecoder(context,uri,description.rotation,startUs=seed.ptsUs).use { decoder ->
            decoder.prepare()
            val rgba=decoder.decodeRgbaAtPts(seed.ptsUs,mapper)
            checkCurrent(current)
            val detected=segmenter.segmentGlReadbackRgbaSync(rgba,mapper,seed.ptsUs)
            val observation=Observation(seed.ptsUs,detected.persons,mapper)
            val index=observation.select(seed)
            require(index>=0) { "未能明确选中人物，请长按人物身体中央重试" }
            val raw=ByteArray(mapper.modelInputSize*mapper.modelInputSize*4)
            rgba.duplicate().apply{rewind();get(raw)}
            val preview=observation.thumbnail(index,raw)
            checkCurrent(current)
            Inspection(seed,observation,index,preview,digest(rgba))
        }
    }

    fun analyze(uri: String, description: ComparisonVideoDescription, start: Long,endInclusive: Long,
        seed: ComparisonCutoutSeed, reference: Boolean, mirror: Boolean, segmenter: YoloLiteRtSegmenter,
        current: () -> Boolean, inspection: Inspection? = null): ComparisonCutoutTrackCache {
        val times=window(timestamps(uri,current),start,endInclusive,seed.ptsUs)
        val swapped=description.rotation%180!=0
        val sw=if(swapped)description.height else description.width
        val sh=if(swapped)description.width else description.height
        val mapper=ModelCoordinateMapper(sw,sh)
        val scale=ALPHA_EDGE.toDouble()/max(sw,sh)
        val aw=(sw*scale).roundToInt().coerceAtLeast(1)
        val ah=(sh*scale).roundToInt().coerceAtLeast(1)
        val prefix=ArrayList<Observation>()
        var prefixBytes=0L
        val layers=HashMap<Long,ComparisonCutoutLayer?>()
        var placement: ComparisonCutoutPlacement?=null
        fun add(o: Observation, index: Int) {
            checkCurrent(current)
            val alpha=if(index<0)null else o.alpha(index,aw,ah)
            layers[o.pts]=alpha?.takeIf { bytes -> bytes.any { it.toInt()!=0 } }?.let {
                ComparisonCutoutLayer(o.pts,aw,ah,it,requireNotNull(placement))
            }
        }
        FrameKernel.CutoutTrack().use { forward ->
            FrameKernel.CutoutTrack().use { reverse ->
                CanonicalYuvInferenceDecoder(context,uri,description.rotation,startUs=times.first()).use { decoder ->
                    decoder.prepare()
                    for (pts in times) {
                        checkCurrent(current)
                        val frame=decoder.decodeRgbaAtPts(pts,mapper)
                        val inspected=inspection?.takeIf { it.seed==seed && pts==seed.ptsUs }
                        require(inspected==null || inspected.sourceDigest.contentEquals(digest(frame))) {
                            "选人画面已变化，请重新选择"
                        }
                        val observation=inspected?.observation ?: run {
                            val detected=segmenter.segmentGlReadbackRgbaSync(frame,mapper,pts,
                                confidenceThreshold=if(pts==seed.ptsUs) .25f else .20f)
                            Observation(pts,detected.persons,mapper)
                        }
                        if(pts<seed.ptsUs) {
                            prefixBytes+=observation.masks.size
                            require(prefixBytes<=MAX_PREFIX_BYTES) { "多人画面超出本次预览分析内存范围" }
                            prefix.add(observation)
                            continue
                        }
                        if(pts==seed.ptsUs) {
                            val index=observation.select(seed)
                            require(inspected==null || inspected.index==index)
                            require(index>=0) { "未能明确选中人物，请长按人物身体中央重试" }
                            val b=observation.bounds[index]
                            placement=ComparisonCutoutPlacement(FloatRect(b.left/sw,b.top/sh,b.right/sw,b.bottom/sh),
                                if(reference) .27 else .73,.88,.70,mirror)
                            // Validate placement before any preview can publish.
                            placement!!.quad(960,540,sw.toDouble()/sh)
                            require(observation.track(forward,index)==index && observation.track(reverse,index)==index)
                            add(observation,index)
                            for (prior in prefix.asReversed()) add(prior,prior.track(reverse))
                            prefix.clear()
                        } else add(observation,observation.track(forward))
                    }
                }
            }
        }
        require(placement!=null && layers.size==times.size && layers[seed.ptsUs]!=null) { "人物分析未完成" }
        return ComparisonCutoutTrackCache(layers)
    }

}
