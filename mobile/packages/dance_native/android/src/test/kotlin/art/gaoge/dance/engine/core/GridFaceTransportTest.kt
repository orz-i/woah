package art.gaoge.dance.engine.core

import kotlin.test.Test
import kotlin.test.*
import java.nio.ByteBuffer

class GridFaceTransportTest {
    @Test fun rgbaTensorAndLandmarksUseTheSameSourceTransform() {
        val rgba=ByteBuffer.allocateDirect(2*4*4)
        repeat(8){rgba.put(255.toByte()).put(0).put(128.toByte()).put(255.toByte())};rgba.rewind()
        val input=FrameKernel.gridFaceInputNative(rgba,2,4,8)
        assertEquals(192*192*3,input.size);assertEquals(-1f,input[0])
        val center=(96*192+96)*3
        assertEquals(1f,input[center]);assertEquals(-1f,input[center+1])
        val boxes=FloatArray(2304*16);val scores=FloatArray(2304){-100f};val n=25*48+23
        scores[n]=0f
        floatArrayOf(2f,4f,20f,30f,1f,2f,3f,4f,5f,6f,7f,8f,9f,10f,11f,12f).copyInto(boxes,n*16)
        val output=FrameKernel.gridFaceDecodeNative(boxes,scores,96,192,.35f)
        assertEquals(17,output.size)
        assertContentEquals(floatArrayOf(38f,91f,58f,121f,.5f,47f,104f),output.copyOfRange(0,7))
    }
    @Test fun corruptTensorRejectsInsteadOfReturningAnEmptyFaceList() {
        val boxes=FloatArray(2304*16);boxes[2]=Float.NaN
        try {FrameKernel.gridFaceDecodeNative(boxes,FloatArray(2304){-100f},192,192,.35f);fail("bad tensor accepted")}
        catch(expected:IllegalArgumentException){}
    }
}
