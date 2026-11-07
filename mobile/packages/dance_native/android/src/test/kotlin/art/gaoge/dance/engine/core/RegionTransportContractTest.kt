package art.gaoge.dance.engine.core

import kotlin.test.*
class RegionTransportContractTest {
    @Test fun validRegionsAreReturnedAndUnsupportedRegionsAreAbsent(){
        val body=floatArrayOf(200f,0f,440f,600f)
        val fallback=assertNotNull(FrameKernel.headFallbackNative(body))
        assertEquals(4,fallback.size);assertTrue(fallback[2]>0f&&fallback[3]>0f)
        assertNull(FrameKernel.headFallbackNative(floatArrayOf(0f,0f,0f,0f)))
        val row=FloatArray(18);row[0]=10f;row[1]=20f;row[2]=50f;row[3]=80f;row[4]=.9f
        val face=assertNotNull(FrameKernel.faceRegionNative(row,null,1f,0f))
        assertEquals(30f,face[0]);assertEquals(40f*.66f,face[2]);assertEquals(60f*.74f,face[3])
        val pixels=ByteArray(25600);val transform=floatArrayOf(1f,0f,0f,640f,640f,640f,160f);val seed=floatArrayOf(320f,100f,30f,40f)
        assertNull(FrameKernel.maskHeadNative(pixels,160,160,transform,body,seed,null,1f))
        for(y in 20 until 32)for(x in 76 until 84)pixels[y*160+x]=255.toByte()
        val center=assertNotNull(FrameKernel.maskHeadNative(pixels,160,160,transform,body,seed,null,1f))
        assertEquals(2,center.size);assertTrue(center.all{it.isFinite()&&it>0f})
        val trusted=assertNotNull(FrameKernel.maskHeadNative(pixels,160,160,transform,body,seed,body,1.1f))
        assertEquals(4,trusted.size);assertTrue(trusted[2]>seed[2]&&trusted[3]>seed[3])
        assertNull(FrameKernel.maskHeadNative(ByteArray(1),160,160,transform,body,seed,null,1f))
    }
}
