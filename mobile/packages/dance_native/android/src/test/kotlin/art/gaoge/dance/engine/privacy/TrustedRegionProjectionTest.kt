package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect
import kotlin.math.sqrt
import kotlin.test.*

/** Test-only original native arithmetic, compared before switching consumers. */
class TrustedRegionProjectionTest {
    private fun FloatRect.values()=floatArrayOf(left,top,right,bottom)
    @Test fun sharedProjectionMatchesOriginalNativeArithmeticAcrossScaleAndLeaseBoundaries() {
        val ages=longArrayOf(-1,0,1,75_000,149_999,150_000,800_000,Long.MAX_VALUE)
        repeat(512){i->
            val previous=FloatRect((i%17)*31.25f,(i%13)*12.5f,(i%17)*31.25f+80+i%109,(i%13)*12.5f+240+i%337)
            val scale=floatArrayOf(.12f,.50f,.88f,1f,1.12f,1.5f,3f,10f)[i%8]
            val current=FloatRect(previous.left+(i%9-4)*4.25f,previous.top+(i%7-3)*5.25f,
                previous.right+(i%9-4)*4.25f+previous.width*(scale-1f),previous.bottom+(i%7-3)*5.25f+previous.height*(scale-1f))
            val seed=floatArrayOf(previous.centerX,previous.top+previous.height*.14f,8f+i%33,12f+i%55)
            val age=ages[i%ages.size]
            val widthRatio=(current.width/previous.width.coerceAtLeast(1f)).coerceAtLeast(.1f)
            val heightRatio=(current.height/previous.height.coerceAtLeast(1f)).coerceAtLeast(.1f)
            val bounded=sqrt(widthRatio*heightRatio).coerceIn(.88f,1.12f)
            val expansion=1f+age.coerceIn(0,150_000).toFloat()/150_000f*.10f
            val delta=PersonBboxMotionEstimator.estimate(previous,current)
            val expected=floatArrayOf(seed[0]+delta.dx,seed[1]+delta.dy,(seed[2]*bounded*expansion).coerceAtLeast(1f),(seed[3]*bounded*expansion).coerceAtLeast(1f))
            val actual=FrameKernel.projectFaceRegionNative(current.values(),previous.values(),seed,age)
            assertNotNull(actual,"case$i")
            assertEquals(expected.map{it.toRawBits()},actual.map{it.toRawBits()},"case$i")
        }
        assertNull(FrameKernel.projectFaceRegionNative(floatArrayOf(0f,0f,0f,0f),floatArrayOf(0f,0f,10f,20f),floatArrayOf(5f,5f,2f,2f),0))
    }
}
