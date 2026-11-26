package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class ComparisonCutoutTest {
    @Test fun maskHitAndAlphaUseTheSharedRustContract() {
        val t = floatArrayOf(1f,0f,0f,4f,4f,4f,4f)
        val a = ByteArray(16) { if(it%4<2) 255.toByte() else 0 }
        val b = ByteArray(16) { if(it%4>=2) 255.toByte() else 0 }
        assertEquals(0,FrameKernel.cutoutSelectNative(a+b,t,.125f,.125f))
        assertEquals(1,FrameKernel.cutoutSelectNative(a+b,t,.875f,.125f))
        assertEquals(-2,FrameKernel.cutoutSelectNative(a+a,t,.125f,.125f))
        assertEquals(-1,FrameKernel.cutoutSelectNative(a+b,t,1f,.5f))
        val letterboxed=floatArrayOf(1f,0f,1f,4f,2f,4f,4f)
        val mask=byteArrayOf(0,0,0,0,0,128.toByte(),255.toByte(),255.toByte(),
            0,128.toByte(),255.toByte(),255.toByte(),0,0,0,0)
        assertContentEquals(byteArrayOf(0,66,255.toByte(),255.toByte(),0,66,255.toByte(),255.toByte()),
            FrameKernel.cutoutAlphaNative(mask,letterboxed,4,2))
    }
    @Test fun fixedAnchorAndSourcePtsDoNotSilentlyFollowAnotherFrame() {
        val p=ComparisonCutoutPlacement(FloatRect(.1f,.2f,.5f,.8f),.25,.85,.6,true)
        val q=p.quad(1000,500,1.5)
        assertEquals(.75f,q.scaleX,.00001f)
        assertEquals(-.5f,(2*.7f-1)*q.scaleX+q.shiftX,.00001f)
        val input=byteArrayOf(1,2,3,4)
        val layer=ComparisonCutoutLayer(40000,2,2,input,p)
        input[0]=0
        val frame=ComparisonCutoutFrame(layer,null)
        assertEquals(layer,frame.layer(true,40000))
        assertNull(frame.layer(true,40001))
        assertNull(frame.layer(false,40000))
        val out=ByteBuffer.allocateDirect(4)
        layer.uploadTo(out)
        val received=ByteArray(4);out.get(received)
        assertContentEquals(byteArrayOf(1,2,3,4),received)
    }
    @Test fun weakCurrentObservationCanContinueBothDirectionsButNotSeed() {
        val t = floatArrayOf(1f,0f,0f,4f,4f,4f,4f)
        val m = ByteArray(16) { if(it%4<2) 255.toByte() else 0 }
        val appearance=FloatArray(FrameKernel.DANCE_APPEARANCE_BINS).also{it[0]=1f}
        fun row(conf: Float) = floatArrayOf(0f,0f,2f,4f,conf)
        FrameKernel.DanceCutoutTrack().use { track ->
            assertFailsWith<IllegalStateException>{track.step(100_000,0,row(.22f),m,t,appearance)}
            assertEquals(0,track.step(100_000,0,row(.8f),m,t,appearance))
            assertEquals(0,track.step(133_333,-1,row(.22f),m,t,appearance))
            assertEquals(-1,track.step(166_667,-1,floatArrayOf(),byteArrayOf(),t,floatArrayOf()))
            assertEquals(-1,track.step(200_000,-1,row(.22f),m,t,appearance))
        }
        FrameKernel.DanceCutoutTrack().use { track ->
            assertEquals(0,track.step(100_000,0,row(.8f),m,t,appearance))
            assertEquals(0,track.step(66_667,-1,row(.22f),m,t,appearance))
        }
    }
    @Test fun cacheRequiresExactSourcePtsAndWindowIncludesPredecessorAndAnchor() {
        val pts=listOf(0L,33_333L,66_667L,100_000L)
        assertEquals(pts.subList(1,4),ComparisonCutoutAnalysis.window(pts,40_000,100_000,66_667))
        assertFailsWith<IllegalArgumentException> { ComparisonCutoutAnalysis.window(pts,40_000,100_000,66_666) }
        assertFailsWith<IllegalArgumentException> {
            ComparisonCutoutAnalysis.window((0L..3600L).toList(),0,3600,50)
        }
        val p=ComparisonCutoutPlacement(FloatRect(.1f,.2f,.5f,.8f),.25,.85,.6,false)
        val a=ComparisonCutoutLayer(33_333,2,2,byteArrayOf(1,2,3,4),p)
        val cache=ComparisonCutoutTrackCache(mapOf(33_333L to a,66_667L to null))
        assertEquals(2,cache.frameCount);assertEquals(1,cache.visibleCount)
        assertEquals(a,cache.layer(33_333));assertNull(cache.layer(33_334));assertNull(cache.layer(66_667))
    }
    @Test fun thumbnailUsesRealRgbAndCropsTransparentPadding() {
        val t=floatArrayOf(1f,0f,1f,4f,2f,4f,4f)
        val m=byteArrayOf(-1,-1,-1,-1,-1,0,0,0,-1,0,0,0,-1,-1,-1,-1)
        val rgba=ByteArray(64).apply { for(i in 0..15){this[i*4]=i.toByte();this[i*4+3]=-1} }
        assertContentEquals(intArrayOf(1,2,0xff040000.toInt(),0xff080000.toInt()),
            FrameKernel.cutoutThumbnailNative(m,t,rgba,false))
        assertContentEquals(intArrayOf(1,2,0xff080000.toInt(),0xff040000.toInt()),
            FrameKernel.cutoutThumbnailNative(m,t,rgba,true))
        assertNull(FrameKernel.cutoutThumbnailNative(ByteArray(16),t,rgba,false))
    }
    @Test fun longSourceCanAnalyzeABPracticeWindow() {
        val pts=(0L..1800L).map { it*33333 }
        val first=pts[800]+1;val last=pts[980]+1
        val chosen=pts[850]
        val result=ComparisonCutoutAnalysis.window(pts,first,last,chosen)
        assertEquals(181,result.size)
        assertEquals(pts[800],result.first())
        assertEquals(pts[980],result.last())
        assertFailsWith<IllegalArgumentException> { ComparisonCutoutAnalysis.window(pts,first,last,pts[100]) }
    }
    @Test fun canvasEditReusesExactCachedMaskAndFixedAnchor() {
        val base=ComparisonCutoutPlacement(FloatRect(.1f,.2f,.5f,.8f),.25,.85,.6,false)
        val layer=ComparisonCutoutLayer(33_333,2,2,byteArrayOf(1,2,3,4),base)
        val cache=ComparisonCutoutCache(ComparisonCutoutTrackCache(mapOf(33_333L to layer)),ComparisonCutoutTrackCache(emptyMap()))
        val settings=ComparisonCanvasLayer(.5,.9,.8,.5f,true)
        val layout=ComparisonCanvasLayout(settings,settings,false,2)
        cache.setLayout(layout)
        val frame=cache.frame(33_333,33_333)
        assertEquals(layer,frame.layer(true,33_333));assertNull(frame.layer(false,33_333))
        val edited=frame.layout!!.reference.apply(layer.placement)
        assertEquals(base.anchor,edited.anchor);assertEquals(.5,edited.x);assertEquals(.5f,edited.opacity)
        assertEquals(true,edited.mirror);assertEquals(false,frame.layout.targetOnTop)
        assertFailsWith<IllegalArgumentException>{ComparisonCanvasLayer(Double.NaN,.5,.8,1f,false)}
        assertFailsWith<IllegalArgumentException>{ComparisonCanvasLayout(settings,settings,true,3)}
    }
    @Test fun frozenExportLayoutRejectsAbsentMaskAndDoesNotFollowPreviewEdits() {
        val base=ComparisonCutoutPlacement(FloatRect(.1f,.2f,.5f,.8f),.25,.85,.6,false)
        val layer=ComparisonCutoutLayer(33_333,2,2,byteArrayOf(1,2,3,4),base)
        val track=ComparisonCutoutTrackCache(mapOf(33_333L to layer),"fingerprint")
        val preview=ComparisonCutoutCache(track,track)
        val settings=ComparisonCanvasLayer(.5,.9,.8,.5f,true)
        val layout=ComparisonCanvasLayout(settings,settings,false,2)
        preview.setLayout(layout)
        val frozen=preview.frozen();frozen.requireComplete()
        preview.setLayout(layout.copy(background=0))
        assertEquals(2,frozen.strictFrame(33_333,33_333).layout!!.background)
        assertFailsWith<ComparisonCutoutExportUnavailable>{frozen.strictFrame(33_334,33_333)}
        assertFailsWith<ComparisonCutoutExportUnavailable>{
            ComparisonCutoutCache(track,ComparisonCutoutTrackCache(mapOf(33_333L to null),"fingerprint")).requireComplete()
        }
    }
    @Test fun anonymousDiskChunksKeepExactPtsAndFrozenLeases() {
        val dir=java.nio.file.Files.createTempDirectory("cutout-disk-test").toFile()
        try {
            val pts=(0L..399L).map{it*33333}
            assertEquals(400,ComparisonCutoutAnalysis.window(pts,0,pts.last(),pts[200]).size)
            val base=ComparisonCutoutPlacement(FloatRect(.1f,.2f,.5f,.8f),.25,.85,.6,false)
            val disk=ComparisonCutoutDiskTrack(dir,pts)
            for(i in pts.indices.reversed()) disk.add(pts[i],2,2,if(i==5)null else byteArrayOf(i.toByte(),2,3,4),base)
            disk.seal(pts[200])
            val track=ComparisonCutoutTrackCache(disk,"digest")
            val lease=track.retained()
            track.close()
            assertEquals(399,lease.visibleCount)
            for(i in listOf(0,31,32,350,399,15,200)) {
                val data=ByteBuffer.allocate(4)
                lease.layer(pts[i])!!.uploadTo(data)
                assertEquals(i.toByte(),data.get())
            }
            assertNull(lease.layer(pts[5]));assertNull(lease.layer(pts[200]+1))
            assertEquals(0,dir.listFiles()!!.size)
            lease.close()
        } finally {dir.deleteRecursively()}
    }

    @Test fun reverseSpoolRejectsInvalidRecordAndBudget() {
        val dir=java.nio.file.Files.createTempDirectory("cutout-spool-test").toFile()
        try {
            ComparisonCutoutDisk(dir,100).use { disk ->
                val first=disk.append(byteArrayOf(1,2,3))
                val second=disk.append(byteArrayOf(4,5,6))
                assertContentEquals(byteArrayOf(4,5,6),disk.read(second))
                assertContentEquals(byteArrayOf(1,2,3),disk.read(first))
                assertFailsWith<IllegalStateException>{disk.read(first.copy(length=2))}
                assertFailsWith<IllegalArgumentException>{disk.append(ByteArray(90))}
            }
            assertEquals(0,dir.listFiles()!!.size)
        } finally {dir.deleteRecursively()}
    }
    @Test fun spilledRawObservationsPreserveReverseIdentityAcrossChunks() {
        val dir=java.nio.file.Files.createTempDirectory("cutout-observation-test").toFile()
        try {
            val mapper=art.gaoge.dance.engine.geometry.ModelCoordinateMapper(640,640)
            val left=ByteArray(160*160){if(it%160<80)255.toByte() else 0}
            val right=ByteArray(160*160){if(it%160>=80)255.toByte() else 0}
            fun person(mask:ByteArray,box:FloatRect,conf:Float)=art.gaoge.dance.engine.inference.PersonDetection(box,conf,
                art.gaoge.dance.engine.inference.NativeMask(160,160,ByteBuffer.wrap(mask),640,640))
            // Native decoder RGB is bottom-up. Left dark and right white
            // distinguish the selected person without retaining RGB in spool.
            val frame=ByteBuffer.allocateDirect(640*640*4)
            for(y in 0 until 640)for(x in 0 until 640){
                val c=if(x<320)16.toByte() else 240.toByte()
                frame.put(c).put(c).put(c).put(255.toByte())
            }
            frame.rewind()
            fun observation(i:Int):ComparisonCutoutAnalysis.Observation {
                val a=person(left,FloatRect(0f,0f,320f,640f),if(i==31||i==32).22f else .8f)
                val b=person(right,FloatRect(320f,0f,640f,640f),.8f)
                return ComparisonCutoutAnalysis.Observation(i*33333L,
                    if(i%2==0)listOf(a,b) else listOf(b,a),mapper,frame)
            }
            ComparisonCutoutDisk(dir,16L*1024*1024).use { disk ->
                val records=(0..96).map{disk.append(observation(it).encode())}
                FrameKernel.DanceCutoutTrack().use { direct ->
                    FrameKernel.DanceCutoutTrack().use { spilled ->
                        for(i in 96 downTo 0) {
                            val original=observation(i)
                            val decoded=ComparisonCutoutAnalysis.Observation.decode(disk.read(records[i]))
                            assertEquals(original.pts,decoded.pts)
                            assertContentEquals(original.rows,decoded.rows)
                            assertContentEquals(original.masks,decoded.masks)
                            assertContentEquals(original.transform,decoded.transform)
                            assertContentEquals(original.appearance,decoded.appearance)
                            assertEquals(1f,decoded.appearance[i%2*FrameKernel.DANCE_APPEARANCE_BINS])
                            assertEquals(1f,decoded.appearance[(1-i%2)*FrameKernel.DANCE_APPEARANCE_BINS+4])
                            val seed=if(i==96)0 else -1
                            val expected=original.track(direct,seed)
                            val actual=decoded.track(spilled,seed)
                            assertEquals(expected,actual)
                            assertEquals(i%2,actual,"Selected left identity must survive the chunk boundary")
                        }
                    }
                }
            }
        } finally {dir.deleteRecursively()}
    }
    @Test fun archiveRoundTripsSelectedAlphaAndRejectsStaleOrCorruptInput() {
        val dir=java.nio.file.Files.createTempDirectory("cutout-archive-test").toFile()
        val root=java.io.File(dir,"saved")
        val archive=ComparisonCutoutArchive(root,dir)
        val pts=(0L..79L).map{it*33333}
        val seed=ComparisonCutoutSeed(pts[32],.25f,.5f)
        val selection=ComparisonCutoutSelection(seed,seed)
        val key=ComparisonCutoutArchiveKey(0,0,1.0,0,pts.last()+1,false,true,"CPU","ref","target","model")
        fun track(reference:Boolean,digest:String):ComparisonCutoutTrackCache{
            val placement=ComparisonCutoutPlacement(FloatRect(.1f,.2f,.5f,.8f),
                if(reference).27 else .73,.88,.70,!reference)
            return ComparisonCutoutTrackCache(pts.associateWith{p->
                ComparisonCutoutLayer(p,2,2,byteArrayOf((p%255).toByte(),2,3,4),placement)
            },digest)
        }
        try{
            ComparisonCutoutCache(track(true,"ref"),track(false,"target")).use{cache->
                assertEquals(true,archive.save("project",key,selection,cache){true})
                assertFailsWith<kotlinx.coroutines.CancellationException>{
                    archive.restore("project",key,{pts},{pts}){false}
                }
                val restored=archive.restore("project",key,{pts},{pts}){true}!!
                restored.cache.use{saved->
                    assertEquals(selection,restored.selection)
                    saved.requireComplete()
                    for(p in listOf(pts[0],pts[31],pts[32],pts[79])){
                        val a=ByteBuffer.allocate(4);val b=ByteBuffer.allocate(4)
                        cache.reference.layer(p)!!.uploadTo(a)
                        saved.reference.layer(p)!!.uploadTo(b)
                        assertContentEquals(a.array(),b.array())
                    }
                    assertNull(saved.reference.layer(pts[32]+1))
                }
                assertNull(archive.restore("project",key.copy(offset=1),{pts},{pts}){true})
                assertEquals(false,java.io.File(root,"project.cutout").exists())
                archive.save("project",key,selection,cache){true}
                java.io.File(root,"project.cutout").appendBytes(byteArrayOf(1))
                assertNull(archive.restore("project",key,{pts},{pts}){true})
                archive.save("project",key,selection,cache){true}
                assertNull(archive.restore("project",key,{pts.dropLast(1)},{pts}){true})
                archive.save("project",key,selection,cache){true}
                assertFailsWith<IllegalArgumentException>{archive.forget("../outside")}
                archive.forget("project")
                assertNull(archive.restore("project",key,{pts},{pts}){true})
            }
        }finally{dir.deleteRecursively()}
    }
}
