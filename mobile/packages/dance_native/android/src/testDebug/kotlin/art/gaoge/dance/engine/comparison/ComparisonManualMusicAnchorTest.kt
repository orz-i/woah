package art.gaoge.dance.engine.comparison

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComparisonManualMusicAnchorTest {
    @Test fun parsesExactMicrosecondsAndMinuteNotation() {
        assertEquals(0L, ComparisonManualMusicAnchor.parseTime("0"))
        assertEquals(5_123456L, ComparisonManualMusicAnchor.parseTime(" 5.123456 "))
        assertEquals(123_040000L, ComparisonManualMusicAnchor.parseTime("2:03.04"))
        assertEquals("123.040000", ComparisonManualMusicAnchor.formatTime(123_040000))
    }
    @Test fun rejectsAmbiguousPrecisionOverflowAndInvalidMinuteSeconds() {
        for (value in listOf("", "-1", "+1", "NaN", "1e3", "1.0000001", "2:60", "1:2:3",
            "86400.000001", "1441:00", "999999999999999999999999", "0. 2", ".5")) {
            assertFailsWith<IllegalArgumentException>(value) { ComparisonManualMusicAnchor.parseTime(value) }
        }
    }
    @Test fun positiveOffsetMapsTheExactMusicEventWithoutChangingMotion() {
        val anchor = ComparisonManualMusicAnchor(5_000_000, 7_125_000)
        val range = anchor.overlap(18_000_000, 20_000_000)
        assertEquals(2_125_000L, anchor.offsetUs)
        assertEquals(anchor.learnerUs, range.learnerTimeUs(anchor.teacherUs - range.teacherStartUs))
        assertEquals(2_125_000L, range.learnerTimeUs(9_000_000) - range.teacherTimeUs(9_000_000))
    }
    @Test fun negativeOffsetRetainsTheCorrectNonzeroOverlapStart() {
        val anchor = ComparisonManualMusicAnchor(2_000_000, 1_000_000)
        val range = anchor.overlap(12_000_000, 11_000_000)
        assertEquals(-1_000_000L, anchor.offsetUs)
        assertEquals(1_000_000L, range.teacherStartUs)
        assertEquals(0L, range.learnerTimeUs(0))
        assertEquals(anchor.learnerUs, range.learnerTimeUs(1_000_000))
    }
    @Test fun rejectsEndpointsAndUnplayableAnchorsBeforeChangingThePlayer() {
        assertFailsWith<IllegalArgumentException> { ComparisonManualMusicAnchor(18_000_000, 19_000_000).overlap(18_000_000, 20_000_000) }
        assertFailsWith<IllegalArgumentException> { ComparisonManualMusicAnchor(17_000_000, 20_000_000).overlap(18_000_000, 20_000_000) }
        assertFailsWith<IllegalArgumentException> { ComparisonManualMusicAnchor(-1, 0) }
        assertFailsWith<IllegalArgumentException> { ComparisonManualMusicAnchor(0, 300_000_001) }
    }
}
