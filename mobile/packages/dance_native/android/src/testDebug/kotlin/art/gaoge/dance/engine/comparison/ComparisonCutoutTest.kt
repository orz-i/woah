package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
