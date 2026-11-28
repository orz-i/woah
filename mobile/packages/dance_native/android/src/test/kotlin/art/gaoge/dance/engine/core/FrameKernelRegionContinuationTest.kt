package art.gaoge.dance.engine.core

import art.gaoge.dance.engine.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class FrameKernelRegionContinuationTest {
    @Test fun stickerSizeTransportRetainsCurrentCenterAndCoverage() {
        val state = FrameKernel.FaceRenderSizeState()
        val large = floatArrayOf(100f, 80f, 40f, 50f)
        val small = floatArrayOf(123f, 90f, 20f, 25f)
        assertContentEquals(large, state.step(large, 0, 0))
        val weak = state.step(small, 33_333, 1)
        assertContentEquals(floatArrayOf(123f, 90f, 40f, 50f), weak)
        val observed = state.step(small, 66_666, 0)
        assertTrue(observed[3] in 25f..50f)
        assertContentEquals(small, state.step(small, 250_001, 2))
        assertContentEquals(large, state.step(large, 283_334, 0))
        assertContentEquals(small, state.step(small, 10_000, 1))
        assertContentEquals(small, FrameKernel.FaceRenderSizeState().step(small, 33_333, 1))
    }

    @Test fun ellipseAxesTransportPreservesBothAxesAndResetsOnModeChanges() {
        val state = FrameKernel.FaceRenderSizeState()
        val circle = floatArrayOf(100f, 80f, 50f, 50f)
        val narrow = floatArrayOf(123f, 90f, 35f, 50f)
        assertContentEquals(circle, state.step(circle, 0, 0, preserveAxes = true))
        assertContentEquals(floatArrayOf(123f, 90f, 50f, 50f),
            state.step(narrow, 33_333, 1, preserveAxes = true))
        val observed = state.step(narrow, 66_666, 0, preserveAxes = true)
        assertTrue(observed[2] > narrow[2] && observed[2] < circle[2])
        assertTrue(observed[3] >= narrow[3])
        assertContentEquals(narrow, state.step(narrow, 250_001, 2, preserveAxes = true))
        assertContentEquals(circle, state.step(circle, 283_334, 0, preserveAxes = true))
        assertContentEquals(narrow, state.step(narrow, 10_000, 1, preserveAxes = true))
        assertContentEquals(circle, state.step(circle, 43_333, 0, preserveAxes = true))
        // Switching scope must not carry either mode's previous history.
        assertContentEquals(narrow, state.step(narrow, 76_666, 1))
        assertContentEquals(narrow, state.step(narrow, 110_000, 1, preserveAxes = true))
    }

    @Test fun invalidEllipseAxesInputDoesNotCorruptHistory() {
        val state = FrameKernel.FaceRenderSizeState()
        val circle = floatArrayOf(100f, 80f, 50f, 50f)
        state.step(circle, 0, 0, preserveAxes = true)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            state.step(floatArrayOf(100f, 80f, Float.NaN, 50f), 33_333, 0, preserveAxes = true)
        }
        assertContentEquals(floatArrayOf(123f, 90f, 50f, 50f),
            state.step(floatArrayOf(123f, 90f, 35f, 50f), 66_666, 1, preserveAxes = true))
    }

    @Test fun sourceContextUsesTheSameBoundedTimestampPlan() {
        kotlin.test.assertNull(FrameKernel.regionContextProbeUs(0))
        kotlin.test.assertNull(FrameKernel.regionContextProbeUs(-1))
        kotlin.test.assertEquals(21_960_000L,FrameKernel.regionContextProbeUs(22_360_000))
        kotlin.test.assertEquals(0L,FrameKernel.regionContextProbeUs(1))
    }
    @Test fun freshAnonymousIdsDoNotEraseRecentObservedFaceCoverage() {
        val core=FrameKernel.RegionContinuation()
        val observed=floatArrayOf(1298.2534f,1206.1788f,116.943756f,131.11876f)
        val old=FloatRect(246.7338f,1044.3633f,2142.255f,3449.7703f)
        assertContentEquals(observed,core.step(observed,old,22_040_000,0))
        val fallback=floatArrayOf(1070.4006f,829.6046f,306.32767f,362.0236f)
        val current=FloatRect(129.13925f,373.52557f,1521.5377f,3438.6226f)
        val actual=core.step(fallback,current,22_360_000,2)
        val dx=(1272f-actual[0])/actual[2];val dy=(1176f-actual[1])/actual[3]
        assertTrue(dx*dx+dy*dy<=1f)
        core.reset()
        assertContentEquals(fallback,core.step(fallback,current,22_400_000,2))
    }
    @Test fun staleOrFutureMeasurementsCannotExtendCoverageAfterSeek() {
        val core=FrameKernel.RegionContinuation();val raw=floatArrayOf(100f,80f,20f,25f)
        val box=FloatRect(0f,0f,200f,400f)
        core.step(raw,box,1_000_000,0)
        val fallback=floatArrayOf(40f,30f,15f,15f)
        assertContentEquals(fallback,core.step(fallback,box,999_999,2))
        core.step(raw,box,1_000_000,0)
        assertContentEquals(fallback,core.step(fallback,box,1_500_001,2))
    }
}
