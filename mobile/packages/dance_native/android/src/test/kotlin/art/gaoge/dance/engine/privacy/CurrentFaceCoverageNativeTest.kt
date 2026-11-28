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
    @Test fun samplingMarginCoversTheChinWhenTheCurrentMaskHasNoHeadRun() {
        val mask=NativeMask(160,160,ByteBuffer.allocateDirect(160*160),640,640,
            mapper=ModelCoordinateMapper(640,640))
        val seed=FacePrivacyEllipse(280f,200f,60f,70f,FacePrivacyRegionSource.DETECTED_FACE)
        val result=FrameKernelFaceGeometry.coverCurrent(mask,FloatRect(80f,60f,560f,620f),seed)
        assertEquals(64f,result.radiusX)
        assertEquals(74f,result.radiusY)
        assertTrue(result.centerY+result.radiusY>=273f)
        assertEquals(seed.source,result.source)
    }
    @Test fun connectedHeadAdapterCoversTheHeadCellsAndActualPrivacyRasterWithoutShoulderInflation() {
        val pixels = ByteBuffer.allocateDirect(160*160)
        // Tapered crown followed by a wider connected shoulder/arm branch.
        val headRows = listOf(6 to (14 until 18), 7 to (13 until 19),
            8 to (12 until 20), 9 to (12 until 20), 10 to (12 until 20), 11 to (13 until 19))
        for ((row, columns) in headRows) for (x in columns) pixels.put(row*160+x, 255.toByte())
        for (y in 12 until 29) for (x in 5 until 27) pixels.put(y*160+x, 255.toByte())
        val mapper = ModelCoordinateMapper(640,640)
        val mask = NativeMask(160,160,pixels,640,640,mapper=mapper)
        val person = FloatRect(12f,16f,116f,124f)
        val seed = FacePrivacyEllipse(64f,38f,12f,16f,FacePrivacyRegionSource.DETECTED_FACE)
        val output = assertNotNull(FrameKernelFaceGeometry.headEllipse(mask,person,seed,seed))
        val raster = assertNotNull(FacePrivacyMaskBuilder.build(listOf(output),mapper))
        fun covered(x:Float,y:Float):Boolean {
            val dx=(x-output.centerX)/output.radiusX
            val dy=(y-output.centerY)/output.radiusY
            return dx*dx+dy*dy<=1.00001f
        }
        for ((y, columns) in headRows) for (x in columns) {
            for (dx in listOf(0f,4f)) for (dy in listOf(0f,4f))
                assertTrue(covered(x*4f+dx,y*4f+dy))
            assertEquals(255.toByte(),raster.buffer.get(y*160+x))
        }
        for (i in 0 until 360) {
            val radians = i*Math.PI/180
            assertTrue(covered(seed.centerX+seed.radiusX*kotlin.math.cos(radians).toFloat(),
                seed.centerY+seed.radiusY*kotlin.math.sin(radians).toFloat()))
        }
        assertEquals(0.toByte(),raster.buffer.get(13*160+5))
        assertEquals(0.toByte(),raster.buffer.get(13*160+26))
        assertEquals(seed.source,output.source)
        assertNull(FrameKernelFaceGeometry.headEllipse(null,person,seed,seed))
        assertNull(FrameKernelFaceGeometry.headEllipse(mask,person,seed,
            seed.copy(source=FacePrivacyRegionSource.PREDICTED_FACE)))
    }
    @Test fun invalidConnectedHeadMaskCannotWriteAnEllipse() {
        assertFailsWith<IllegalArgumentException> {
            FrameKernel.headEllipseNative(byteArrayOf(1,2,3),160,160,
                floatArrayOf(1f,0f,0f,640f,640f,640f,160f),floatArrayOf(80f,60f,560f,620f),
                floatArrayOf(280f,200f,60f,70f),floatArrayOf(280f,200f,60f,70f),0)
        }
    }
    @Test fun malformedCurrentMaskCannotProduceARenderRegion() {
        assertFailsWith<IllegalArgumentException> {
            FrameKernel.currentFaceRegionNative(byteArrayOf(1,2,3),160,160,
                floatArrayOf(1f,0f,0f,640f,640f,640f,160f),floatArrayOf(80f,60f,560f,620f),
                floatArrayOf(280f,200f,60f,70f))
        }
    }
}
