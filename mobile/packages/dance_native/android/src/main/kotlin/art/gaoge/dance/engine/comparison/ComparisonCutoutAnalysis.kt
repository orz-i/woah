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
internal class ComparisonCutoutTrackCache private constructor(
    private val entries: Map<Long, ComparisonCutoutLayer?>?,
    private val disk: ComparisonCutoutDiskTrack?, val fingerprint:String?
) : AutoCloseable {
    constructor(layers:Map<Long,ComparisonCutoutLayer?>,fingerprint:String?=null):this(layers.toMap(),null,fingerprint)
    constructor(disk:ComparisonCutoutDiskTrack,fingerprint:String):this(null,disk,fingerprint)
    val times:List<Long> get()=disk?.times ?: entries!!.keys.sorted()
    val frameCount:Int get()=disk?.frameCount ?: entries!!.size
    val visibleCount:Int get()=disk?.visibleCount ?: entries!!.values.count{it!=null}
    fun layer(ptsUs:Long):ComparisonCutoutLayer?=disk?.layer(ptsUs) ?: entries?.get(ptsUs)
    fun retained()=ComparisonCutoutTrackCache(entries,disk?.retain(),fingerprint)
    private val released=java.util.concurrent.atomic.AtomicBoolean(false)
    override fun close(){if(released.compareAndSet(false,true))disk?.close()}
}

internal class ComparisonCutoutCache(val reference: ComparisonCutoutTrackCache,
    val target: ComparisonCutoutTrackCache):AutoCloseable {
    private val layout=java.util.concurrent.atomic.AtomicReference<ComparisonCanvasLayout?>(null)
    private val released=java.util.concurrent.atomic.AtomicBoolean(false)
    fun setLayout(value:ComparisonCanvasLayout?) = layout.getAndSet(value)
    fun frame(referencePts: Long, targetPts: Long) =
        ComparisonCutoutFrame(reference.layer(referencePts),target.layer(targetPts),layout.get())
    fun frozen(): ComparisonCutoutCache = ComparisonCutoutCache(reference.retained(),target.retained()).also { it.setLayout(layout.get()) }
    override fun close(){if(released.compareAndSet(false,true)){reference.close();target.close()}}
    fun strictFrame(referencePts:Long,targetPts:Long):ComparisonCutoutFrame = frame(referencePts,targetPts).also {
        if(it.layer(true,referencePts)==null || it.layer(false,targetPts)==null)throw ComparisonCutoutExportUnavailable()
    }
    fun requireComplete() {
        if(reference.frameCount==0 || target.frameCount==0 ||
            reference.visibleCount!=reference.frameCount || target.visibleCount!=target.frameCount ||
            reference.fingerprint==null || target.fingerprint==null)throw ComparisonCutoutExportUnavailable()
    }
    fun verifySources(context:Context,spec:ComparisonExportSpec,current:()->Boolean) {
        if(ComparisonCutoutFingerprint.read(context,spec.teacherUri,current)!=reference.fingerprint ||
            ComparisonCutoutFingerprint.read(context,spec.learnerUri,current)!=target.fingerprint)
            throw ComparisonCutoutExportUnavailable()
    }
}

internal class ComparisonCutoutExportUnavailable:IllegalStateException("抠像画布分析不完整或源视频已变化，请重新选人")
/** Content URIs can change under the same path. Hash the original bytes around
 * analysis and again around encoding; never persist the source digest or masks. */
internal object ComparisonCutoutFingerprint {
    fun read(context:Context,uri:String,current:()->Boolean):String {
        val source=Uri.parse(uri)
        val md=MessageDigest.getInstance("SHA-256")
        val stream=if(source.scheme=="content")requireNotNull(context.contentResolver.openInputStream(source))
            else java.io.File(source.path ?: uri).inputStream()
        stream.use {
            val bytes=ByteArray(256*1024)
            while(true) {
                if(!current())throw CancellationException("Cutout fingerprint cancelled")
                val count=it.read(bytes)
                if(count<0)break
                if(count>0)md.update(bytes,0,count)
            }
        }
        return android.util.Base64.encodeToString(md.digest(),android.util.Base64.NO_WRAP)
    }
}
internal data class ComparisonCutoutExportSnapshot(val cache:ComparisonCutoutCache,val originUs:Long) {
    fun retained()=ComparisonCutoutExportSnapshot(cache.frozen(),originUs)
    fun close()=cache.close()
    fun validate(spec:ComparisonExportSpec,range:SourceInterval) {
        if(!spec.cutout || originUs+spec.startUs!=range.startUs || originUs+spec.endUs!=range.endUs)
            throw ComparisonCutoutExportUnavailable()
        cache.requireComplete()
    }
}

/** Bounded M0 analysis for one common interval. No decoding/inference on the GL
 * thread. Both directions are anchored at the same real selected source sample. */
internal class ComparisonCutoutAnalysis(private val context: Context) {
    companion object {
        const val MAX_FRAMES = 3600
        private const val MAX_PREFIX_BYTES = 512 * 1024 * 1024L
        private const val ALPHA_EDGE = 256

        fun window(sorted: List<Long>, start: Long, endInclusive: Long, anchor: Long): List<Long> {
            require(start >= 0 && endInclusive >= start && anchor >= 0)
            val first = sorted.binarySearch(start).let { if(it >= 0) it else -it-2 }
            require(first >= 0) { "选区首帧不可读取" }
            val last = sorted.binarySearch(endInclusive).let { if(it >= 0) it else -it-2 }
            require(last >= first && last-first+1 <= MAX_FRAMES) { "抠像画布当前每路最多支持 3600 帧" }
            val result = sorted.subList(first,last+1)
            require(result.binarySearch(anchor) >= 0) { "选人画面已变化，请暂停后重试" }
            return result.toList()
        }
    }

    private fun checkCurrent(current: () -> Boolean) {
        if(!current()) throw CancellationException("Cutout analysis cancelled")
    }

    internal fun timestamps(uri: String, current: () -> Boolean): List<Long> {
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

    internal class Observation private constructor(val pts:Long,val transform:FloatArray,
        val rows:FloatArray,val masks:ByteArray,val appearance:FloatArray) {
        val area=transform[6].toInt()*transform[6].toInt()
        val bounds=rows.asList().chunked(5).map { FloatRect(it[0],it[1],it[2],it[3]) }
        private constructor(pts:Long,transform:FloatArray,rows:FloatArray,masks:ByteArray,frame:ByteBuffer?):this(
            pts,transform,rows,masks,frame?.let{FrameKernel.danceAppearanceNative(it,masks,transform)}
                ?: FloatArray(rows.size/5*FrameKernel.DANCE_APPEARANCE_BINS))
        constructor(pts:Long,persons:List<PersonDetection>,mapper:ModelCoordinateMapper,frame:ByteBuffer?=null):this(pts,
            floatArrayOf(mapper.scale,mapper.padLeft,mapper.padTop,mapper.srcWidth.toFloat(),
                mapper.srcHeight.toFloat(),mapper.modelInputSize.toFloat(),mapper.protoSize.toFloat()),
            FloatArray(persons.size*5).apply { persons.forEachIndexed { i,d ->
                val b=d.bbox;floatArrayOf(b.left,b.top,b.right,b.bottom,d.confidence).copyInto(this,i*5)
            } },
            ByteArray(persons.size*mapper.protoSize*mapper.protoSize).apply bytes@{
                require(persons.size<=256)
                val area=mapper.protoSize*mapper.protoSize
                persons.forEachIndexed { i,d ->
                    val m=requireNotNull(d.mask)
                    require(m.width==mapper.protoSize && m.height==mapper.protoSize)
                    m.buffer.duplicate().apply{rewind();get(this@bytes,i*area,area)}
                }
            },frame)
        fun encode():ByteArray {
            val data=ByteBuffer.allocate(8+7*4+4+rows.size*4+masks.size+appearance.size*4)
            data.putLong(pts);transform.forEach{data.putFloat(it)}
            data.putInt(rows.size);rows.forEach{data.putFloat(it)};data.put(masks)
            appearance.forEach{data.putFloat(it)}
            return data.array()
        }
        companion object {
            fun decode(bytes:ByteArray):Observation {
                val data=ByteBuffer.wrap(bytes)
                val pts=data.long
                val t=FloatArray(7){data.float}
                val n=data.int
                require(pts>=0 && t.all{it.isFinite()} && t[6]==160f && n in 0..1280 && n%5==0 &&
                    data.remaining()==n*4+n/5*(160*160+FrameKernel.DANCE_APPEARANCE_BINS*4))
                val rows=FloatArray(n){data.float}
                val masks=ByteArray(n/5*160*160);data.get(masks)
                val appearance=FloatArray(n/5*FrameKernel.DANCE_APPEARANCE_BINS){data.float}
                return Observation(pts,t,rows,masks,appearance)
            }
        }
        fun select(seed: ComparisonCutoutSeed) =
            FrameKernel.cutoutSelectNative(masks,transform,seed.x,seed.y)
        fun track(session:FrameKernel.DanceCutoutTrack,seed:Int=-1)=session.step(pts,seed,rows,masks,transform,appearance)
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
            val observation=Observation(seed.ptsUs,detected.persons,mapper,rgba)
            val index=observation.select(seed)
            require(index>=0) { "未能明确选中人物，请长按人物身体中央重试" }
            val raw=ByteArray(mapper.modelInputSize*mapper.modelInputSize*4)
            rgba.duplicate().apply{rewind();get(raw)}
            val preview=observation.thumbnail(index,raw)
            checkCurrent(current)
            Inspection(seed,observation,index,preview,digest(rgba))
        }
    }

    /** A lane owns one seed and exactly one tracker per direction. The
     * neighborhood is analyzed first, then the untouched suffix and prefix.
     * Each decode span is ascending; reverse tracking consumes its spool
     * in descending PTS order. No model sample is inferred twice. */
    internal class Work(val preview:ComparisonCutoutTrackCache,val total:Int,
        private val disk:ComparisonCutoutDiskTrack,private val finishWork:()->ComparisonCutoutTrackCache,
        private val cleanup:()->Unit):AutoCloseable {
        val analyzed get()=disk.frameCount
        val visible get()=disk.visibleCount
        private var finished=false
        private var closed=false
        fun finish():ComparisonCutoutTrackCache {
            check(!finished&&!closed)
            return finishWork().also{finished=true}
        }
        override fun close(){if(!closed){closed=true;try{cleanup()}finally{if(!finished)disk.close()}}}
    }

    fun begin(uri: String, description: ComparisonVideoDescription, start: Long,endInclusive: Long,
        firstPreview:Long,lastPreview:Long,seed: ComparisonCutoutSeed, reference: Boolean, mirror: Boolean,
        segmenter: YoloLiteRtSegmenter,current: () -> Boolean, inspection: Inspection? = null): Work {
        val measure=art.gaoge.dance.engine.BuildConfig.DEBUG
        val wallStart=System.nanoTime()
        val timing=linkedMapOf<String,Long>()
        fun addTime(name:String,nanos:Long) {
            if(measure)timing[name]=(timing[name] ?: 0L)+nanos
        }
        fun <T> timed(name:String,body:()->T):T {
            if(!measure)return body()
            val before=System.nanoTime()
            return try { body() } finally { addTime(name,System.nanoTime()-before) }
        }
        val fingerprint=timed("fingerprint"){ComparisonCutoutFingerprint.read(context,uri,current)}
        val times=timed("frameIndex"){window(timestamps(uri,current),start,endInclusive,seed.ptsUs)}
        val nearby=window(times,firstPreview,lastPreview,seed.ptsUs)
        require(nearby.size<=256) { "初始画布帧率超出范围" }
        val first=times.binarySearch(nearby.first())
        val last=times.binarySearch(nearby.last())
        val anchor=times.binarySearch(seed.ptsUs)
        val swapped=description.rotation%180!=0
        val sw=if(swapped)description.height else description.width
        val sh=if(swapped)description.width else description.height
        val mapper=ModelCoordinateMapper(sw,sh)
        val scale=ALPHA_EDGE.toDouble()/max(sw,sh)
        val aw=(sw*scale).roundToInt().coerceAtLeast(1)
        val ah=(sh*scale).roundToInt().coerceAtLeast(1)
        val layers=ComparisonCutoutDiskTrack(context.cacheDir,times)
        val forward=try{FrameKernel.DanceCutoutTrack()}catch(e:Throwable){layers.close();throw e}
        val reverse=try{FrameKernel.DanceCutoutTrack()}catch(e:Throwable){forward.close();layers.close();throw e}
        val small=HashMap<Long,ComparisonCutoutLayer?>()
        var placement:ComparisonCutoutPlacement?=null
        var prefixBytes=0L
        fun add(o:Observation,index:Int,initial:Boolean) {
            checkCurrent(current)
            val alpha=if(index<0)null else timed("alpha"){o.alpha(index,aw,ah)}
                ?.takeIf{bytes->bytes.any{it.toInt()!=0}}
            val fixed=requireNotNull(placement)
            timed("alphaStore"){layers.add(o.pts,aw,ah,alpha,fixed)}
            if(initial)small[o.pts]=alpha?.let{ComparisonCutoutLayer(o.pts,aw,ah,it,fixed)}
        }
        fun decode(from:Int,to:Int,consume:(Observation)->Unit) {
            if(from>=to)return
            CanonicalYuvInferenceDecoder(context,uri,description.rotation,startUs=times[from]).use { decoder ->
                decoder.prepare()
                for(i in from until to){
                    checkCurrent(current)
                    val pts=times[i]
                    val frame=timed("decodeAndConvert"){decoder.decodeRgbaAtPts(pts,mapper)}
                    val inspected=inspection?.takeIf { it.seed==seed && pts==seed.ptsUs }
                    require(inspected==null || inspected.sourceDigest.contentEquals(digest(frame))) { "选人画面已变化，请重新选择" }
                    val observation=inspected?.observation ?: run {
                        val detected=timed("segmentTotal"){segmenter.segmentGlReadbackRgbaSync(frame,mapper,pts,
                            confidenceThreshold=if(pts==seed.ptsUs) .25f else .20f)}
                        if(measure)detected.stageTimingsMs.forEach{(name,ms)->addTime(name,ms*1_000_000)}
                        timed("observation"){Observation(pts,detected.persons,mapper,frame)}
                    }
                    consume(observation)
                }
            }
        }
        fun backwards(from:Int,to:Int,initial:Boolean) {
            if(from>=to)return
            ComparisonCutoutDisk(context.cacheDir,MAX_PREFIX_BYTES).use { spool ->
                val records=ArrayList<ComparisonCutoutDisk.Record>()
                decode(from,to){records.add(timed("prefixWrite"){spool.append(it.encode())})}
                prefixBytes+=spool.bytes
                for(record in records.asReversed()){
                    checkCurrent(current)
                    val prior=timed("prefixRead"){Observation.decode(spool.read(record))}
                    add(prior,timed("track"){prior.track(reverse)},initial)
                }
            }
        }
        try {
            // Seed first, then grow both directions around the playhead.
            decode(anchor,anchor+1){observation->
                val chosen=observation.select(seed)
                require(chosen>=0 && (inspection?.takeIf{it.seed==seed}?.index ?: chosen)==chosen) { "未能明确选中人物" }
                val b=observation.bounds[chosen]
                placement=ComparisonCutoutPlacement(FloatRect(b.left/sw,b.top/sh,b.right/sw,b.bottom/sh),
                    if(reference).27 else .73,.88,.70,mirror)
                placement!!.quad(960,540,sw.toDouble()/sh)
                require(timed("track"){observation.track(forward,chosen)==chosen && observation.track(reverse,chosen)==chosen})
                add(observation,chosen,true)
            }
            backwards(first,anchor,true)
            decode(anchor+1,last+1){add(it,timed("track"){it.track(forward)},true)}
            require(small.size==nearby.size && small[seed.ptsUs]!=null)
            val preview=ComparisonCutoutTrackCache(small) // no fingerprint: cannot export a partial
            small.clear()
            return Work(preview,times.size,layers,finishWork={
                decode(last+1,times.size){add(it,timed("track"){it.track(forward)},false)}
                backwards(0,first,false)
                layers.seal(seed.ptsUs)
                require(fingerprint==timed("fingerprint"){ComparisonCutoutFingerprint.read(context,uri,current)}) { "源视频已变化" }
                if(measure){
                    val stages=org.json.JSONObject()
                    timing.forEach{(name,nanos)->stages.put(name,nanos/1_000_000)}
                    android.util.Log.i("WoahCutoutPerf",org.json.JSONObject()
                        .put("role",if(reference)"reference" else "target").put("frames",times.size)
                        .put("visible",layers.visibleCount).put("alphaDiskBytes",layers.bytes)
                        .put("accelerator",segmenter.effectiveAccelerator.toString())
                        .put("wallMs",(System.nanoTime()-wallStart)/1_000_000)
                        .put("prefixBytes",prefixBytes).put("alphaBytesPerFrame",aw*ah)
                        .put("stageMs",stages).toString())
                }
                ComparisonCutoutTrackCache(layers,fingerprint)
            },cleanup={try{forward.close()}finally{reverse.close()}})
        } catch(e:Throwable){try{forward.close()}finally{try{reverse.close()}finally{layers.close()}};throw e}
    }
}
