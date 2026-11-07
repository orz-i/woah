package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import java.nio.ByteBuffer
import kotlin.test.*

/** Synthetic head rectangles only; original diagnostic observations stay local. */
class CurrentFaceCoverageNativeTest {
    @Test fun productionAdapterPreservesTheCurrentProfileAndOriginalEllipse() {
        val pixels=ByteBuffer.allocateDirect(160*160)
        for(y in 35 until 65)for(x in 50 until 100)pixels.put(y*160+x,255.toByte())
        val mask=NativeMask(160,160,pixels,640,640,mapper=ModelCoordinateMapper(640,640))
        val seed=FacePrivacyEllipse(280f,200f,60f,70f,FacePrivacyRegionSource.DETECTED_FACE)
        val result=FrameKernelFaceGeometry.coverCurrent(mask,FloatRect(80f,60f,560f,620f),seed)
        fun covered(x:Float,y:Float):Boolean {
            val dx=(x-result.centerX)/result.radiusX;val dy=(y-result.centerY)/result.radiusY
            return dx*dx+dy*dy<=1.00001f
        }
        assertTrue(covered(400f,200f))
        assertTrue(covered(seed.centerX-seed.radiusX,seed.centerY))
        assertTrue(covered(seed.centerX,seed.centerY+seed.radiusY))
        assertEquals(seed.source,result.source)
        assertEquals(seed,FrameKernelFaceGeometry.coverCurrent(null,FloatRect(80f,60f,560f,620f),seed))
    }
    @Test fun malformedCurrentMaskCannotProduceARenderRegion() {
        assertFailsWith<IllegalArgumentException> {
            FrameKernel.currentFaceRegionNative(byteArrayOf(1,2,3),160,160,
                floatArrayOf(1f,0f,0f,640f,640f,640f,160f),floatArrayOf(80f,60f,560f,620f),
                floatArrayOf(280f,200f,60f,70f))
        }
    }
}
