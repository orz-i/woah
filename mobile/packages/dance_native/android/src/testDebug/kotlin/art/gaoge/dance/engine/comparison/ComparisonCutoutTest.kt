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
        fun row(conf: Float) = floatArrayOf(0f,0f,2f,4f,conf)
        FrameKernel.CutoutTrack().use { track ->
            assertEquals(0,track.step(100_000,0,row(.8f),m,t))
            assertEquals(0,track.step(133_333,-1,row(.22f),m,t))
            assertEquals(-1,track.step(166_667,-1,floatArrayOf(),byteArrayOf(),t))
            assertEquals(-1,track.step(200_000,-1,row(.22f),m,t))
        }
        FrameKernel.CutoutTrack().use { track ->
            assertEquals(0,track.step(100_000,0,row(.8f),m,t))
            assertEquals(0,track.step(66_667,-1,row(.22f),m,t))
        }
    }
    @Test fun cacheRequiresExactSourcePtsAndWindowIncludesPredecessorAndAnchor() {
        val pts=listOf(0L,33_333L,66_667L,100_000L)
        assertEquals(pts.subList(1,4),ComparisonCutoutAnalysis.window(pts,40_000,100_000,66_667))
        assertFailsWith<IllegalArgumentException> { ComparisonCutoutAnalysis.window(pts,40_000,100_000,66_666) }
        assertFailsWith<IllegalArgumentException> {
            ComparisonCutoutAnalysis.window((0L..300L).toList(),0,300,50)
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
}
