package art.gaoge.dance.engine.comparison

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ComparisonPracticeTest {
    private val overlap=ComparisonOverlap(0,200_000,3_000_000)
    @Test fun forwardFollowsVfrPresentationBoundariesRatherThanAverageFps() {
        for ((start,end) in listOf(0L to 18_000L,18_000L to 79_000L,79_000L to 112_500L)) {
            assertEquals(end,ComparisonFrameNavigation.target(ComparisonFrameInterval(start,end),true,overlap) {
                error("Forward stepping must not search backward")
            })
        }
    }
    @Test fun previousDecodesImmediatelyBeforeTheDisplayedFrameNotBeforeTheClock() {
        var probe=-1L
        val target=ComparisonFrameNavigation.target(ComparisonFrameInterval(79_000,112_500),false,overlap) {
            probe=it;ComparisonFrameInterval(18_000,79_000)
        }
        assertEquals(78_999L,probe)
        assertEquals(18_000L,target)
        assertEquals(3_018_000L,target+overlap.offsetUs)
    }
    @Test fun firstAndLastFramesStayInsideTheSharedOverlap() {
        val clipped=ComparisonOverlap(40_000,100_000,-40_000)
        assertEquals(40_000L,ComparisonFrameNavigation.target(ComparisonFrameInterval(18_000,79_000),false,clipped) {
            error("The first visible frame has no previous frame")
        })
        assertEquals(79_000L,ComparisonFrameNavigation.target(ComparisonFrameInterval(79_000,112_500),true,clipped) {
            error("The final frame has no next frame")
        })
    }
    @Test fun previousFrameMayBeginBeforeTheCommonMusicRange() {
        val clipped=ComparisonOverlap(40_000,200_000,-40_000)
        assertEquals(40_000L,ComparisonFrameNavigation.target(ComparisonFrameInterval(79_000,112_500),false,clipped) {
            ComparisonFrameInterval(18_000,79_000)
        })
    }
    @Test fun loopUsesExclusiveEndAndPreservesNegativeMusicMappingOnRestart() {
        val loop=ComparisonLoop(250_000,950_000)
        assertTrue(loop.contains(250_000));assertTrue(loop.contains(949_999))
        assertFalse(loop.contains(950_000));assertTrue(loop.reached(950_000))
        val music=ComparisonOverlap(1_000_000,5_000_000,-1_000_000)
        assertEquals(1_250_000L,music.teacherTimeUs(loop.startUs))
        assertEquals(250_000L,music.learnerTimeUs(loop.startUs))
        assertFailsWith<IllegalArgumentException> { ComparisonLoop(100,100) }
    }
}
