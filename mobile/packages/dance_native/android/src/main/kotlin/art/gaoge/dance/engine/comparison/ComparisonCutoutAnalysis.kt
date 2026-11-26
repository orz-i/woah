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
    /** Replace exactly the decoder-indexed samples in the confirmed patch.
     * Missing patch observations explicitly replace old observations too.
     * Keep the original placement anchor to avoid geometry jumps at seams. */
    fun replacing(directory:java.io.File,patch:ComparisonCutoutTrackCache,expected:List<Long>,
                  originalAnchor:Long,current:()->Boolean):ComparisonCutoutTrackCache {
        val digest=requireNotNull(fingerprint)
        require(patch.fingerprint==digest && patch.times==expected && expected.isNotEmpty())
        val all=times
        val start=all.binarySearch(expected.first())
        require(start>=0 && start+expected.size<=all.size && all.subList(start,start+expected.size)==expected)
        val anchor=requireNotNull(layer(originalAnchor))
        val fixed=anchor.placement
        val out=ComparisonCutoutDiskTrack(directory,all)
        try {
            var oldVisible=0;var patchVisible=0
            for((i,pts) in all.withIndex()){
                if(!current())throw CancellationException("Cutout repair cancelled")
                val old=layer(pts)
                if(old!=null)oldVisible++
                val patched=i in start until start+expected.size
                val selected=if(patched)patch.layer(pts) else old
                if(patched && selected!=null)patchVisible++
                val alpha=selected?.let {
                    require(it.sourcePtsUs==pts && it.width==anchor.width && it.height==anchor.height)
                    ByteBuffer.allocate(it.width*it.height).also(it::uploadTo).array()
                }
                out.add(pts,anchor.width,anchor.height,alpha,fixed)
            }
            require(oldVisible==visibleCount && patchVisible==patch.visibleCount)
            out.seal(originalAnchor)
            return ComparisonCutoutTrackCache(out,digest)
        }catch(e:Throwable){out.close();throw e}
    }
    fun retained()=ComparisonCutoutTrackCache(entries,disk?.retain(),fingerprint)
    private val released=java.util.concurrent.atomic.AtomicBoolean(false)
    override fun close(){if(released.compareAndSet(false,true))disk?.close()}
}

internal class ComparisonCutoutCache(val reference: ComparisonCutoutTrackCache,
    val target: ComparisonCutoutTrackCache):AutoCloseable {
    private val layout=java.util.concurrent.atomic.AtomicReference<ComparisonCanvasLayout?>(null)
    private val released=java.util.concurrent.atomic.AtomicBoolean(false)
    fun setLayout(value:ComparisonCanvasLayout?) = layout.getAndSet(value)
    fun replacing(referenceRole:Boolean,directory:java.io.File,patch:ComparisonCutoutTrackCache,
                  expected:List<Long>,anchor:Long,current:()->Boolean):ComparisonCutoutCache {
        val replaced=(if(referenceRole)reference else target).replacing(directory,patch,expected,anchor,current)
        try {
            return (if(referenceRole)ComparisonCutoutCache(replaced,target.retained())
                else ComparisonCutoutCache(reference.retained(),replaced)).also{it.setLayout(layout.get())}
        }catch(e:Throwable){replaced.close();throw e}
    }
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
        fun stepOrDetail(session:FrameKernel.DanceCutoutTrack,roi:IntArray)=session.stepOrDetail(pts,rows,masks,transform,appearance,roi)
        fun finishDetail(session:FrameKernel.DanceCutoutTrack,fullCount:Int)=session.finishDetail(pts,fullCount,rows,masks,transform,appearance)
        fun withDetail(local:Observation,roi:IntArray):Observation {
            require(local.pts==pts)
            var allRows=rows;var allMasks=masks;var allAppearance=appearance
            for(i in local.bounds.indices){
                if(allRows.size/5>=256)break
                val mask=ByteArray(area)
                val row=FrameKernel.danceDetailRemapNative(local.rows.copyOfRange(i*5,(i+1)*5),
                    local.mask(i),local.transform,transform,roi,allRows,allMasks,mask) ?: continue
                allRows+=row;allMasks+=mask
                val n=FrameKernel.DANCE_APPEARANCE_BINS
                allAppearance+=local.appearance.copyOfRange(i*n,(i+1)*n)
            }
            return if(allRows===rows)this else Observation(pts,transform,allRows,allMasks,allAppearance)
        }
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
        segmenter: YoloLiteRtSegmenter,current: () -> Boolean, inspection: Inspection? = null,
        fixedGrid:Pair<Int,Int>?=null,detailEnabled:Boolean=true,detailReuse:Boolean=true): Work {
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
        val dimensions=fixedGrid ?: FrameKernel.cutoutGridNative(sw,sh,times.size,nearby.size).let{it[0] to it[1]}
        val (aw,ah)=dimensions
        require(aw in 1..384 && ah in 1..384 &&
            (aw.toLong()*ah+3200)*times.size<=256L*1024*1024 &&
            (aw.toLong()*ah+3200)*nearby.size<=18L*1024*1024)
        val layers=ComparisonCutoutDiskTrack(context.cacheDir,times)
        val forward=try{FrameKernel.DanceCutoutTrack()}catch(e:Throwable){layers.close();throw e}
        val reverse=try{FrameKernel.DanceCutoutTrack()}catch(e:Throwable){forward.close();layers.close();throw e}
        val small=HashMap<Long,ComparisonCutoutLayer?>()
        var placement:ComparisonCutoutPlacement?=null
        var prefixBytes=0L
        // Device A/B showed CPU contention with its four inference threads.
        // Prefetch only for effective GPU, keeping CPU on its serial path.
        val prefetch=segmenter.effectiveAccelerator==art.gaoge.dance.engine.litert.LiteRtAccelerator.GPU
        val decodeBuffers=if(prefetch) Array(2){ByteBuffer.allocateDirect(mapper.modelInputSize*mapper.modelInputSize*4)} else null
        fun observe(pts:Long,frame:ByteBuffer,crop:((ModelCoordinateMapper,IntArray)->ByteBuffer)?,
                    consume:(Observation,((ModelCoordinateMapper,IntArray)->ByteBuffer)?)->Unit){
            checkCurrent(current)
            val inspected=inspection?.takeIf { it.seed==seed && pts==seed.ptsUs }
            require(inspected==null || inspected.sourceDigest.contentEquals(digest(frame))) { "选人画面已变化，请重新选择" }
            val observation=inspected?.observation ?: run {
                val detected=timed("segmentTotal"){segmenter.segmentGlReadbackRgbaSync(frame,mapper,pts,
                    confidenceThreshold=if(pts==seed.ptsUs) .25f else .20f)}
                if(measure)detected.stageTimingsMs.forEach{(name,ms)->addTime(name,ms*1_000_000)}
                timed("observation"){Observation(pts,detected.persons,mapper,frame)}
            }
            consume(observation,crop)
        }
        fun add(o:Observation,index:Int,initial:Boolean) {
            checkCurrent(current)
            val alpha=if(index<0)null else timed("alpha"){o.alpha(index,aw,ah)}
                ?.takeIf{bytes->bytes.any{it.toInt()!=0}}
            val fixed=requireNotNull(placement)
            timed("alphaStore"){layers.add(o.pts,aw,ah,alpha,fixed)}
            if(initial)small[o.pts]=alpha?.let{ComparisonCutoutLayer(o.pts,aw,ah,it,fixed)}
        }
        // Exact source reuse is scoped to a decode span, never to a durable
        // project. CPU can sample the currently borrowed Image; GPU keeps its
        // proven RGBA prefetch and lazily opens a second sequential reader.
        val detailBudget=minOf(160,maxOf(1,(times.size+3)/4))
        var detailAttempts=0;var detailAccepted=0;var detailFailures=0
        var detailInline=0;var detailOpens=0;var detailSeeks=0;var detailReuseHits=0
        // A single auxiliary codec per span. Descending PTS requires flush
        // and seek; a failed reuse is retried once with a fresh codec.
        val reader=object:AutoCloseable{
            var decoder:CanonicalYuvInferenceDecoder?=null
            var previous:Long?=null
            fun closeCurrent(){
                decoder?.close();decoder=null;previous=null
            }
            fun read(pts:Long,local:ModelCoordinateMapper,roi:IntArray):ByteBuffer{
                if(!detailReuse){
                    detailOpens++
                    return CanonicalYuvInferenceDecoder(context,uri,description.rotation,startUs=pts).use{
                        timed("detailOpen"){it.prepare()}
                        // Own the result after decoder closes (same direct buffer
                        // as previous C6 path; no borrowed Image survives).
                        timed("detailDecode"){it.decodeDetailAtPts(pts,local,roi,sw,sh)}
                    }
                }
                for(attempt in 0..1){
                    try{
                        checkCurrent(current)
                        val d=decoder ?: CanonicalYuvInferenceDecoder(context,uri,description.rotation,startUs=pts).also{
                            decoder=it;detailOpens++;timed("detailOpen"){it.prepare()}
                        }
                        if(previous!=null){
                            if(pts<=previous!! || pts-previous!!>1_200_000L){
                                timed("detailSeek"){d.seekDetailTo(pts)};detailSeeks++
                            }
                            detailReuseHits++
                        }
                        val frame=timed("detailDecode"){d.decodeDetailAtPts(pts,local,roi,sw,sh)}
                        previous=pts
                        return frame
                    }catch(e:CancellationException){throw e}
                    catch(e:Exception){
                        closeCurrent()
                        if(attempt==1)throw e
                    }
                }
                error("Detail decode exhausted")
            }
            override fun close(){closeCurrent()}
        }
        fun addTracked(o:Observation,session:FrameKernel.DanceCutoutTrack,initial:Boolean,
                       crop:((ModelCoordinateMapper,IntArray)->ByteBuffer)?=null){
            checkCurrent(current)
            if(!detailEnabled || detailAttempts>=detailBudget){
                add(o,timed("track"){o.track(session)},initial);return
            }
            val roi=IntArray(4)
            val selected=timed("track"){o.stepOrDetail(session,roi)}
            if(selected!=-3){add(o,selected,initial);return}
            detailAttempts++
            val merged=try{
                val cropMapper=ModelCoordinateMapper(roi[2],roi[3])
                val frame=if(detailReuse && crop!=null){
                    detailInline++;timed("detailDecode"){crop(cropMapper,roi)}
                }else reader.read(o.pts,cropMapper,roi)
                checkCurrent(current)
                val local=timed("detailInference"){segmenter.segmentGlReadbackRgbaSync(frame,cropMapper,o.pts,
                    confidenceThreshold=.25f)}
                o.withDetail(Observation(o.pts,local.persons,cropMapper,frame),roi)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){detailFailures++;o}
            checkCurrent(current)
            val index=timed("track"){merged.finishDetail(session,o.bounds.size)}
            if(index>=o.bounds.size)detailAccepted++
            add(merged,index,initial)
        }
        fun decode(from:Int,to:Int,inlineDetail:Boolean=false,consume:(Observation,((ModelCoordinateMapper,IntArray)->ByteBuffer)?)->Unit) {
            if(from>=to)return
            val buffers=decodeBuffers
            if(buffers==null || to-from==1){
                CanonicalYuvInferenceDecoder(context,uri,description.rotation,startUs=times[from]).use{decoder->
                    decoder.prepare()
                    for(i in from until to){
                        checkCurrent(current)
                        val pts=times[i]
                        if(detailReuse && detailEnabled && inlineDetail){
                            val start=System.nanoTime()
                            decoder.decodeRgbaWithDetailAtPts(pts,mapper){frame,crop->
                                addTime("decodeAndConvert",System.nanoTime()-start)
                                observe(pts,frame,crop,consume)
                            }
                        }else observe(pts,timed("decodeAndConvert"){decoder.decodeRgbaAtPts(pts,mapper)},null,consume)
                    }
                }
                return
            }
            val producer=java.util.concurrent.Executors.newSingleThreadExecutor{job->
                Thread(job,"WoahCutoutDecode").apply{isDaemon=true}
            }
            var decoder:CanonicalYuvInferenceDecoder?=null // producer only
            data class Decoded(val pts:Long,val buffer:ByteBuffer,val elapsed:Long)
            fun submit(i:Int)=producer.submit(java.util.concurrent.Callable{
                checkCurrent(current)
                val value=decoder ?: CanonicalYuvInferenceDecoder(context,uri,description.rotation,startUs=times[from]).also{
                    decoder=it;it.prepare()
                }
                val start=System.nanoTime()
                val pts=times[i]
                val frame=value.decodeRgbaAtPtsInto(pts,mapper,buffers[(i-from)%2])
                Decoded(pts,frame,System.nanoTime()-start)
            })
            try {
                var next=submit(from)
                for(i in from until to){
                    checkCurrent(current)
                    val decoded=timed("decodeWait"){next.get()}
                    require(decoded.pts==times[i])
                    addTime("decodeAndConvert",decoded.elapsed)
                    // The next task owns the other buffer. We consume this
                    // frame completely before its slot can be reused.
                    if(i+1<to)next=submit(i+1)
                    observe(decoded.pts,decoded.buffer,null,consume)
                }
            }finally{
                // Wait for an in-flight decode, then close on its own thread.
                // Do not release/reuse the buffers while the producer owns them.
                val cleanup=producer.submit{decoder?.close();decoder=null}
                producer.shutdown()
                var interrupted=false
                try{
                    while(true){
                        try{cleanup.get();break}
                        catch(_:InterruptedException){interrupted=true}
                    }
                }finally{if(interrupted)Thread.currentThread().interrupt()}
            }
        }
        fun backwards(from:Int,to:Int,initial:Boolean) {
            if(from>=to)return
            ComparisonCutoutDisk(context.cacheDir,MAX_PREFIX_BYTES).use { spool ->
                val records=ArrayList<ComparisonCutoutDisk.Record>()
                decode(from,to){observation,_->records.add(timed("prefixWrite"){spool.append(observation.encode())})}
                prefixBytes+=spool.bytes
                try{
                    for(record in records.asReversed()){
                        checkCurrent(current)
                        val prior=timed("prefixRead"){Observation.decode(spool.read(record))}
                        addTracked(prior,reverse,initial)
                    }
                }finally{reader.close()}
            }
        }
        try {
            // Seed first, then grow both directions around the playhead.
            decode(anchor,anchor+1){observation,_->
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
            try{decode(anchor+1,last+1,true){o,crop->addTracked(o,forward,true,crop)}}finally{reader.close()}
            require(small.size==nearby.size && small[seed.ptsUs]!=null)
            val preview=ComparisonCutoutTrackCache(small) // no fingerprint: cannot export a partial
            small.clear()
            return Work(preview,times.size,layers,finishWork={
                try{decode(last+1,times.size,true){o,crop->addTracked(o,forward,false,crop)}}finally{reader.close()}
                backwards(0,first,false)
                layers.seal(seed.ptsUs)
                require(fingerprint==timed("fingerprint"){ComparisonCutoutFingerprint.read(context,uri,current)}) { "源视频已变化" }
                if(measure){
                    val stages=org.json.JSONObject()
                    timing.forEach{(name,nanos)->stages.put(name,nanos/1_000_000)}
                    android.util.Log.i("WoahCutoutPerf",org.json.JSONObject()
                        .put("role",if(reference)"reference" else "target").put("frames",times.size)
                        .put("visible",layers.visibleCount).put("alphaDiskBytes",layers.bytes)
                        .put("accelerator",segmenter.effectiveAccelerator.toString()).put("decodePrefetch",prefetch)
                        .put("wallMs",(System.nanoTime()-wallStart)/1_000_000)
                        .put("prefixBytes",prefixBytes).put("alphaBytesPerFrame",aw*ah)
                        .put("detailAttempts",detailAttempts).put("detailAccepted",detailAccepted).put("detailFailures",detailFailures)
                        .put("detailEnabled",detailEnabled).put("detailReuse",detailReuse)
                        .put("detailInline",detailInline).put("detailDecoderOpens",detailOpens)
                        .put("detailSeeks",detailSeeks).put("detailReuseHits",detailReuseHits).put("stageMs",stages).toString())
                }
                ComparisonCutoutTrackCache(layers,fingerprint)
            },cleanup={try{forward.close()}finally{reverse.close()}})
        } catch(e:Throwable){try{forward.close()}finally{try{reverse.close()}finally{layers.close()}};throw e}
    }
}
