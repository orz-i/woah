package art.gaoge.dance.engine.comparison

import android.content.Context
import art.gaoge.dance.engine.core.FrameKernel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import art.gaoge.dance.engine.inference.YoloLiteRtSegmenter
import art.gaoge.dance.engine.litert.LiteRtAccelerator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * Device-local comparison A/B. Same model, decoded source PTS, thresholds,
 * anchor, forward/reverse identity and final disk cache as the canvas.
 * No model tensors, source paths, images or masks are written to the report.
 * Explicit opt-in; never changes the application/production runtime policy.
 */
@RunWith(AndroidJUnit4::class)
class ComparisonCutoutBackendInstrumentedTest {
    private data class Source(val file: File, val description: ComparisonVideoDescription,
        val times: List<Long>, val seed: ComparisonCutoutSeed, val reference: Boolean)
    private data class Frame(val pts: Long, val width: Int, val height: Int, val alpha: ByteArray)
    private data class Lane(val frames: List<Frame?>, val evidence: JSONObject)
    private data class Run(val lanes: List<Lane>, val evidence: JSONObject)

    private fun source(file: File, reference: Boolean, anchorUs: Long, limitUs: Long,
        x:Float=if(reference).355f else .324f,y:Float=if(reference).855f else .86f,
        maxFrames:Int=360): Source {
        require(file.isFile)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val f=extractor.getTrackFormat(track)
            val duration=f.getLong(MediaFormat.KEY_DURATION)
            val rotation=if(f.containsKey(MediaFormat.KEY_ROTATION))f.getInteger(MediaFormat.KEY_ROTATION) else 0
            val fps=if(f.containsKey(MediaFormat.KEY_FRAME_RATE))f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() else -1f
            extractor.selectTrack(track)
            val pts=sortedSetOf<Long>()
            // MediaExtractor may expose decode-order B-frame timestamps.
            // Filter presentation timestamps rather than stop at first >= limit.
            var scanned=0
            while(extractor.sampleTime>=0){
                require(++scanned<=100_000)
                if(extractor.sampleTime<limitUs)pts.add(extractor.sampleTime)
                if(!extractor.advance())break
            }
            require(pts.size>=30 && pts.size<=maxFrames && pts.first()==0L)
            val anchor=pts.minBy { abs(it-anchorUs) }
            return Source(file,ComparisonVideoDescription(f.getInteger(MediaFormat.KEY_WIDTH),f.getInteger(MediaFormat.KEY_HEIGHT),
                rotation,duration,fps,requireNotNull(f.getString(MediaFormat.KEY_MIME)),"device",false),
                pts.toList(),ComparisonCutoutSeed(anchor,x,y),reference)
        } finally {extractor.release()}
    }

    private fun ms(start:Long)= (System.nanoTime()-start)/1_000_000.0

    private suspend fun runBackend(context:Context,sources:List<Source>,backend:LiteRtAccelerator,threads:Int,
        evidenceRoot:File?=null,detailEnabled:Boolean=true,detailReuse:Boolean=true,detailScheduled:Boolean=true,captureObservations:Boolean=false,gpuReverseBlock:Boolean=false,reverseSlotBudget:Int=6*1024*1024):Run {
        val start=System.nanoTime()
        YoloLiteRtSegmenter(context,requestedAccelerator=backend,cpuNumThreads=threads).use { segmenter ->
            segmenter.initialize()
            val info=requireNotNull(segmenter.runtimeInfo)
            val initialized=ms(start)
            // Don't label silent fallback measurements as GPU.
            if(segmenter.effectiveAccelerator!=backend)
                return Run(emptyList(),JSONObject().put("requested",backend.name)
                    .put("effective",segmenter.effectiveAccelerator.name).put("status","fallback")
                    .put("initializationMs",initialized).put("compileMs",info.compileMs)
                    .put("fallback",info.fallbackReason ?: "unknown"))
            val analysis=ComparisonCutoutAnalysis(context)
            val inspected=sources.map { src ->
                analysis.inspect(src.file.absolutePath,src.description,src.seed,segmenter,{true})
            }
            val inspectionMs=ms(start)-initialized
            val first=System.nanoTime()
            val works=ArrayList<ComparisonCutoutAnalysis.Work>()
            val observationWriters=ArrayList<DataOutputStream>()
            val observationFiles=ArrayList<Pair<File,File>>()
            val observationCounts=ArrayList<Set<Long>>()
            try {
                sources.forEachIndexed { i,src ->
                    val sink:((ComparisonCutoutAnalysis.Observation)->Unit)?=if(captureObservations){
                        check(context.packageName=="art.gaoge.dance.engine.cutoutqa.test")
                        val root=requireNotNull(evidenceRoot)
                        val role=if(src.reference)"reference" else "target"
                        val dest=File(root,"${backend.name.lowercase()}-$role.observations.gz")
                        val tmp=File(root,"${dest.name}.tmp")
                        val writer=DataOutputStream(java.util.zip.GZIPOutputStream(BufferedOutputStream(tmp.outputStream())))
                        observationWriters.add(writer);observationFiles.add(tmp to dest)
                        writer.writeInt(0x574F4431);writer.writeInt(src.times.size);writer.writeInt(FrameKernel.DANCE_APPEARANCE_BINS)
                        val times=HashSet<Long>();observationCounts.add(times)
                        var total=0L
                        val callback:(ComparisonCutoutAnalysis.Observation)->Unit={o->
                            val bytes=o.encode()
                            require(times.add(o.pts) && times.size<=src.times.size && bytes.size<=8*1024*1024)
                            total+=bytes.size
                            require(total<=512L*1024*1024)
                            writer.writeInt(bytes.size);writer.write(bytes)
                        }
                        callback
                    }else null
                    works.add(analysis.begin(src.file.absolutePath,src.description,0,src.times.last(),
                        max(0,src.seed.ptsUs-500_000),min(src.times.last(),src.seed.ptsUs+499_999),
                        src.seed,src.reference,!src.reference,segmenter,{true},inspected[i],detailEnabled=detailEnabled,detailReuse=detailReuse,detailScheduled=detailScheduled,observationEvidence=sink,gpuReverseBlock=gpuReverseBlock,reverseSlotBudget=reverseSlotBudget))
                }
                val firstMs=ms(first)
                val lanes=ArrayList<Lane>()
                for ((i,work) in works.withIndex()) {
                    val src=sources[i]
                    val laneStart=System.nanoTime()
                    work.finish().use { cache ->
                        val finishMs=ms(laneStart)
                        // Long-scene evidence is streamed to an explicitly opted-in
                        // private instrumentation directory; never hold all alpha
                        // frames in RAM or write to a product project.
                        val frames=if(evidenceRoot!=null){
                            val role=if(src.reference)"reference" else "target"
                            val destination=File(evidenceRoot,"${backend.name.lowercase()}-$role.alpha")
                            val temporary=File(evidenceRoot,"${destination.name}.tmp")
                            try{
                                DataOutputStream(BufferedOutputStream(temporary.outputStream())).use{out->
                                    out.writeInt(0x574F4131);out.writeInt(src.times.size)
                                    for(at in src.times){
                                        val layer=cache.layer(at)
                                        out.writeLong(at);out.writeInt(layer?.width ?: 0);out.writeInt(layer?.height ?: 0)
                                        if(layer!=null){
                                            val bytes=ByteBuffer.allocate(layer.width*layer.height)
                                            layer.uploadTo(bytes);out.write(bytes.array())
                                        }
                                    }
                                }
                                check(temporary.renameTo(destination))
                            }finally{temporary.delete()}
                            emptyList()
                        }else src.times.map { at ->
                            cache.layer(at)?.let { layer ->
                                val bytes=ByteBuffer.allocate(layer.width*layer.height)
                                layer.uploadTo(bytes)
                                Frame(at,layer.width,layer.height,bytes.array())
                            }
                        }
                        val anchor=requireNotNull(cache.layer(src.seed.ptsUs)).placement.anchor
                        val stats=JSONObject().put("reference",src.reference).put("frames",cache.frameCount)
                            .put("visible",cache.visibleCount).put("finishMs",finishMs)
                            .put("anchor",JSONArray(listOf(anchor.left,anchor.top,anchor.right,anchor.bottom)))
                            .put("firstAnalyzed",work.preview.frameCount)
                        lanes.add(Lane(frames,stats))
                        if(evidenceRoot!=null)Log.i("WoahCutoutBackendQa","lane=" + stats.toString())
                    }
                }
                check(segmenter.effectiveAccelerator==backend)
                if(captureObservations)require(observationCounts.zip(sources).all{(times,src)->times==src.times.toSet()})
                observationWriters.forEach{it.close()}
                observationWriters.clear()
                observationFiles.forEach{(tmp,dest)->check(tmp.renameTo(dest))}
                return Run(lanes,JSONObject().put("requested",backend.name).put("effective",backend.name)
                    .put("status","complete").put("cpuThreads",threads).put("initializationMs",initialized).put("gpuReverseBlock",gpuReverseBlock).put("reverseSlotBudget",reverseSlotBudget)
                    .put("compileMs",info.compileMs).put("warmupMs",info.warmupMs)
                    .put("inspectionMs",inspectionMs).put("firstPreviewMs",firstMs)
                    .put("analysisMs",firstMs+lanes.sumOf{it.evidence.getDouble("finishMs")})
                    .put("analysisAndReadbackMs",ms(first)).put("wallMs",ms(start))
                    .put("lanes",JSONArray(lanes.map{it.evidence})))
            } finally {
                works.asReversed().forEach{it.close()}
                observationWriters.forEach{runCatching{it.close()}}
                observationFiles.forEach{(tmp,_)->tmp.delete()}
            }
        }
    }

    private fun compare(cpu:Run,other:Run):JSONObject {
        var cpuVisible=0;var otherVisible=0;var missing=0;var unexpected=0
        val ious=ArrayList<Double>();val maes=ArrayList<Double>();val areaRatios=ArrayList<Double>()
        var exact=0
        val lanes=JSONArray()
        require(cpu.lanes.size==other.lanes.size)
        for((c,o) in cpu.lanes.zip(other.lanes)){
            require(c.frames.size==o.frames.size)
            val laneIous=ArrayList<Double>()
            var laneMissing=0
            for((a,b) in c.frames.zip(o.frames)){
                if(a!=null)cpuVisible++
                if(b!=null)otherVisible++
                if(a!=null && b==null){missing++;laneMissing++;continue}
                if(a==null && b!=null){unexpected++;continue}
                if(a==null || b==null)continue
                require(a.pts==b.pts && a.width==b.width && a.height==b.height)
                var intersection=0;var union=0;var aa=0;var ba=0;var error=0L
                for(i in a.alpha.indices){
                    val av=a.alpha[i].toInt() and 255;val bv=b.alpha[i].toInt() and 255
                    val x=av>=128;val y=bv>=128
                    if(x)aa++
                    if(y)ba++
                    if(x&&y)intersection++
                    if(x||y){union++;error+=abs(av-bv)}
                }
                val iou=if(union==0)1.0 else intersection.toDouble()/union
                ious.add(iou);laneIous.add(iou)
                maes.add(if(union==0)0.0 else error.toDouble()/union)
                areaRatios.add(if(aa==0)1.0 else ba.toDouble()/aa)
                if(a.alpha.contentEquals(b.alpha))exact++
            }
            lanes.put(JSONObject().put("reference",c.evidence.getBoolean("reference"))
                .put("missing",laneMissing).put("minIou",laneIous.minOrNull() ?: JSONObject.NULL)
                .put("meanIou",if(laneIous.isEmpty())JSONObject.NULL else laneIous.average()))
        }
        return JSONObject().put("cpuVisible",cpuVisible).put("candidateVisible",otherVisible)
            .put("missingVsCpu",missing).put("extraVsCpu",unexpected).put("paired",ious.size)
            .put("exactAlphaFrames",exact).put("minIou",ious.minOrNull() ?: JSONObject.NULL)
            .put("meanIou",if(ious.isEmpty())JSONObject.NULL else ious.average())
            .put("foregroundAlphaMae",if(maes.isEmpty())JSONObject.NULL else maes.average())
            .put("minAreaRatio",areaRatios.minOrNull() ?: JSONObject.NULL)
            .put("maxAreaRatio",areaRatios.maxOrNull() ?: JSONObject.NULL).put("lanes",lanes)
    }

    @Test fun sameVideoCpuGpuSelectedAlpha():Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cutoutBackendQa")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.filesDir,"comparison-cutout-backend-qa")
        val report=JSONObject().put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT)
        val candidates=JSONArray()
        try {
            val limitUs=(args.getString("limitUs")?.toLong() ?: 3_000_000).coerceIn(1_000_000,12_000_000)
            val anchorUs=(args.getString("anchorUs")?.toLong() ?: 1_000_000).coerceIn(0,limitUs-1)
            val sources=listOf(source(File(root,"reference.mp4"),true,anchorUs,limitUs),
                source(File(root,"target.mp4"),false,anchorUs,limitUs))
            report.put("limitUs",limitUs).put("anchorUs",sources[0].seed.ptsUs)
            val cpu=runBackend(context,sources,LiteRtAccelerator.CPU,4)
            check(cpu.lanes.isNotEmpty())
            report.put("cpu",cpu.evidence)
            val candidateSpecs=listOf(LiteRtAccelerator.GPU to 4,LiteRtAccelerator.CPU to 6)
            for((backend,threads) in candidateSpecs) {
                val candidate=try {
                    val run=runBackend(context,sources,backend,threads)
                    if(run.lanes.isNotEmpty())run.evidence.put("vsCpu4",compare(cpu,run))
                    run.evidence
                } catch(e:Exception){
                    JSONObject().put("requested",backend.name).put("cpuThreads",threads)
                        .put("status","failed").put("errorClass",e.javaClass.simpleName)
                }
                candidates.put(candidate)
                Log.i("WoahCutoutBackendQa",candidate.toString())
            }
            report.put("completed",true)
        } finally {
            report.put("candidates",candidates)
            root.mkdirs()
            File(root,"report.json").writeText(report.toString(2))
            Log.i("WoahCutoutBackendQa",report.toString())
        }
    }
    /** Real decoder pixels, not a mock coordinate test. Compare all four
     * display orientations with fresh C6 readers, including reverse seeks
     * and scoped cancellation. Only opted-in QA fixture data is used. */
    @Test fun detailSourceReuseParity(){
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("detailSourceQa")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=File(context.filesDir,"comparison-real-dance-qa/reference.mp4")
        val src=source(file,true,3_000_000,12_000_000,.565f,.47f,1800)
        check(src.times.size>=150)
        val pts=listOf(src.times[20],src.times[80],src.times[120],src.times[50])
        val rotationResults=JSONArray()
        fun bytes(buffer:ByteBuffer)=ByteArray(buffer.capacity()).also{buffer.duplicate().apply{rewind();get(it)}}
        for(rotation in listOf(0,90,180,270)){
            val swap=rotation%180!=0
            val sw=if(swap)src.description.height else src.description.width
            val sh=if(swap)src.description.width else src.description.height
            val scene=art.gaoge.dance.engine.geometry.ModelCoordinateMapper(sw,sh)
            val roi=intArrayOf(sw/5,sh/6,sw/2,sh/2)
            val local=art.gaoge.dance.engine.geometry.ModelCoordinateMapper(roi[2],roi[3])
            fun decoder(start:Long)=art.gaoge.dance.engine.media.CanonicalYuvInferenceDecoder(context,file.absolutePath,rotation,startUs=start)
            val fresh=pts.associateWith{at->decoder(at).use{d->
                d.prepare();bytes(d.decodeDetailAtPts(at,local,roi,sw,sh))
            }}
            decoder(pts.first()).use{d->
                d.prepare()
                var escaped:((art.gaoge.dance.engine.geometry.ModelCoordinateMapper,IntArray)->ByteBuffer)?=null
                for(at in pts.take(3)){
                    d.decodeRgbaWithDetailAtPts(at,scene){_,crop->
                        check(bytes(crop(local,roi)).contentEquals(fresh.getValue(at))){"Inline crop changed at rotation $rotation"}
                        escaped=crop
                    }
                    check(runCatching{escaped!!(local,roi)}.isFailure)
                }
                d.seekDetailTo(pts.last())
                check(bytes(d.decodeDetailAtPts(pts.last(),local,roi,sw,sh)).contentEquals(fresh.getValue(pts.last()))){"Seek crop changed"}
                // A cancellation while borrowing the image must release the
                // codec output before the caller continues/tears down.
                val cancelled=runCatching{
                    d.decodeRgbaWithDetailAtPts(src.times[51],scene){_,_->
                        throw kotlinx.coroutines.CancellationException("QA")
                    }
                }
                check(cancelled.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                check(d.decodeRgbaAtPts(src.times[52],scene).capacity()==640*640*4)
            }
            decoder(pts.first()).use{d->
                d.prepare()
                for((i,at) in pts.withIndex()){
                    if(i==3)d.seekDetailTo(at)
                    check(bytes(d.decodeDetailAtPts(at,local,roi,sw,sh)).contentEquals(fresh.getValue(at))){"Reused detail crop changed"}
                }
            }
            // Copy two separate exact source planes, close the codec, then
            // crop in reverse order from the owned slots. A small budget must
            // fail closed and an escaped callback must not read reused bytes.
            val slots=Array(2){art.gaoge.dance.engine.media.CanonicalYuvToRgba.OwnedSource()}
            val out=ByteBuffer.allocateDirect(640*640*4)
            decoder(pts.first()).use{d->
                d.prepare()
                for(i in 0..1)d.captureSourceAtPts(pts[i],scene,slots[i])
                val limited=art.gaoge.dance.engine.media.CanonicalYuvToRgba.OwnedSource(1)
                d.captureSourceAtPts(pts[2],scene,limited)
                check(!limited.valid && limited.capacity==0)
                limited.withCrop(scene,out){check(it==null)}
            }
            for(i in 1 downTo 0){
                var escaped:((art.gaoge.dance.engine.geometry.ModelCoordinateMapper,IntArray)->ByteBuffer)?=null
                slots[i].withCrop(scene,out){crop->
                    check(crop!=null)
                    check(bytes(crop(local,roi)).contentEquals(fresh.getValue(pts[i]))){"Owned source crop changed"}
                    escaped=crop
                }
                check(!slots[i].valid && runCatching{escaped!!(local,roi)}.isFailure)
                slots[i].release();check(slots[i].capacity==0)
            }
            rotationResults.put(JSONObject().put("rotation",rotation).put("snapshotExact",2).put("snapshotBudgetFallback",true).put("inlineExact",3).put("readerExact",4)
                .put("reverseSeekExact",true).put("cancelBorrowReleased",true))
        }
        File(context.filesDir,"comparison-real-dance-qa/detail-source-report.json")
            .writeText(JSONObject().put("passed",true).put("rotations",rotationResults).toString(2))
    }

    /** Cancel after a real reverse source capture, then retry the same
     * lane with an exhausted snapshot budget. No timings asserted here. */
    @Test fun reverseBlockCancelAndFallback():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("reverseLifecycleQa")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName=="art.gaoge.dance.engine.cutoutqa.test")
        val root=File(context.filesDir,"comparison-real-dance-qa")
        val src=source(File(root,"reference.mp4"),true,11_000_000,12_000_000,.55f,.60f,1800)
        fun scratchFds():Set<String> = File("/proc/self/fd").listFiles().orEmpty().mapNotNull {
            runCatching{android.system.Os.readlink(it.absolutePath)}.getOrNull()
        }.filter{it.contains("woah-cutout-") && it.contains(".scratch")}.toSet()
        val before=scratchFds()
        var captures=0
        var current=true
        val analysis=ComparisonCutoutAnalysis(context)
        YoloLiteRtSegmenter(context,requestedAccelerator=LiteRtAccelerator.GPU,cpuNumThreads=4).use{segmenter->
            segmenter.initialize()
            check(segmenter.effectiveAccelerator==LiteRtAccelerator.GPU)
            val inspected=analysis.inspect(src.file.absolutePath,src.description,src.seed,segmenter,{true})
            val cancelled=runCatching{
                analysis.begin(src.file.absolutePath,src.description,0,src.times.last(),
                    src.seed.ptsUs-500_000,src.seed.ptsUs+499_999,src.seed,true,false,segmenter,{current},inspected,
                    gpuReverseBlock=true,onReverseCapture={captures++;current=false}).use{it.finish().close()}
            }
            check(cancelled.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            check(captures==1)
            check(scratchFds()==before){"Reverse cancel leaked scratch descriptors"}
        }
        // A new runner is the real product retry contract (cancel closes the old runner).
        var fallbackVisible=0
        YoloLiteRtSegmenter(context,requestedAccelerator=LiteRtAccelerator.GPU,cpuNumThreads=4).use{segmenter->
            segmenter.initialize()
            check(segmenter.effectiveAccelerator==LiteRtAccelerator.GPU)
            val inspected=analysis.inspect(src.file.absolutePath,src.description,src.seed,segmenter,{true})
            analysis.begin(src.file.absolutePath,src.description,0,src.times.last(),
                src.seed.ptsUs-500_000,src.seed.ptsUs+499_999,src.seed,true,false,segmenter,{true},inspected,
                gpuReverseBlock=true,reverseSlotBudget=1).use{work->
                work.finish().use{cache->
                    fallbackVisible=cache.visibleCount
                    check(cache.frameCount==src.times.size)
                    DataOutputStream(BufferedOutputStream(File(root,"reverse-fallback.alpha").outputStream())).use{out->
                        out.writeInt(0x574F4131);out.writeInt(src.times.size)
                        for(at in src.times){
                            val layer=cache.layer(at)
                            out.writeLong(at);out.writeInt(layer?.width ?: 0);out.writeInt(layer?.height ?: 0)
                            if(layer!=null){
                                val bytes=ByteBuffer.allocate(layer.width*layer.height)
                                layer.uploadTo(bytes);out.write(bytes.array())
                            }
                        }
                    }
                }
            }
        }
        check(scratchFds()==before){"Retry leaked scratch descriptors"}
        File(root,"reverse-lifecycle.json").writeText(JSONObject().put("passed",true)
            .put("cancelAfterCaptures",captures).put("scratchFdsBefore",before.size)
            .put("scratchFdsAfter",scratchFds().size).put("fallbackVisible",fallbackVisible).toString(2))
    }

    @Test fun reverseSourceHighResolution(){
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("reverseHighResolutionQa")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.filesDir,"comparison-real-dance-qa")
        val src=source(File(root,"high-resolution.mp4"),true,600_000,1_200_000,.46f,.60f,1800)
        val sw=src.description.width;val sh=src.description.height
        check(sw==3840 && sh==2160 && src.description.rotation==0)
        val scene=art.gaoge.dance.engine.geometry.ModelCoordinateMapper(sw,sh)
        val roi=intArrayOf(sw/4,sh/4,sw/2,sh/2)
        val local=art.gaoge.dance.engine.geometry.ModelCoordinateMapper(roi[2],roi[3])
        fun decoder()=art.gaoge.dance.engine.media.CanonicalYuvInferenceDecoder(context,src.file.absolutePath,0,startUs=src.seed.ptsUs)
        val slot=art.gaoge.dance.engine.media.CanonicalYuvToRgba.OwnedSource(6*1024*1024)
        val output=ByteBuffer.allocateDirect(640*640*4)
        fun bytes(b:ByteBuffer)=ByteArray(b.capacity()).also{b.duplicate().apply{rewind();get(it)}}
        val expected=decoder().use{d->d.prepare();bytes(d.decodeDetailAtPts(src.seed.ptsUs,local,roi,sw,sh))}
        val actual=decoder().use{d->
            d.prepare()
            d.captureSourceAtPts(src.seed.ptsUs,scene,slot)
            check(!slot.valid && slot.capacity==0){"4K should exceed the six MiB per-slot budget"}
            slot.withCrop(scene,output){check(it==null)}
            d.seekDetailTo(src.seed.ptsUs)
            bytes(d.decodeDetailAtPts(src.seed.ptsUs,local,roi,sw,sh))
        }
        check(expected.contentEquals(actual))
        slot.release()
        File(root,"reverse-high-resolution.json").writeText(JSONObject().put("passed",true)
            .put("width",sw).put("height",sh).put("budgetBytes",6*1024*1024)
            .put("allocatedBytes",slot.capacity).put("fallbackCropExact",true).toString(2))
    }

    /** Real independent clips, fixed PTS/seed, no automatic reseeding. The
     * masks are test-only local evidence for review, not identity ground truth. */
    @Test fun realDanceQuality():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("realDanceQa")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName=="art.gaoge.dance.engine.cutoutqa.test")
        val root=File(context.filesDir,"comparison-real-dance-qa").apply{mkdirs()}
        val seconds=(args.getString("limitUs")?.toLong() ?: 30_000_000L).coerceIn(1_000_000,60_000_000)
        val anchor=(args.getString("anchorUs")?.toLong() ?: 3_000_000L).coerceIn(0,seconds-1)
        val sources=listOf(source(File(root,"reference.mp4"),true,anchor,seconds,
            args.getString("referenceX")!!.toFloat(),args.getString("referenceY")!!.toFloat(),1800),
            source(File(root,"target.mp4"),false,anchor,seconds,
            args.getString("targetX")!!.toFloat(),args.getString("targetY")!!.toFloat(),1800))
        val report=JSONObject().put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT)
            .put("limitUs",seconds).put("anchorUs",anchor)
        val results=JSONArray()
        val evidence=File(root,"report.json")
        fun publish(complete:Boolean){
            report.put("completed",complete).put("runs",results)
            val tmp=File(root,"report.tmp")
            tmp.writeText(report.toString(2));check(tmp.renameTo(evidence))
        }
        publish(false)
        val backends=when(args.getString("backend")){
            null,"both"->listOf(LiteRtAccelerator.GPU,LiteRtAccelerator.CPU)
            "cpu"->listOf(LiteRtAccelerator.CPU)
            "gpu"->listOf(LiteRtAccelerator.GPU)
            else->error("Invalid QA backend")
        }
        for(backend in backends){
            val row=try {
                runBackend(context,sources,backend,4,root,args.getString("detailEnabled")!="false",args.getString("detailReuse")!="false",args.getString("detailScheduled")!="false",args.getString("captureObservations")=="true",args.getString("gpuReverseBlock")=="true",args.getString("reverseSlotBudget")?.toInt() ?: 6*1024*1024).evidence
            }catch(e:Exception){
                JSONObject().put("requested",backend.name).put("status","failed").put("errorClass",e.javaClass.simpleName)
            }
            results.put(row);publish(false)
            Log.i("WoahCutoutBackendQa",row.toString())
        }
        publish(true)
    }

}
