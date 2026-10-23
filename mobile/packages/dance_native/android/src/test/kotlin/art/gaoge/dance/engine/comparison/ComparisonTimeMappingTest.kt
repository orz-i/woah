package art.gaoge.dance.engine.comparison

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ComparisonTimeMappingTest {
    private fun interval(start: Long, end: Long) = SourceInterval(start, end)

    @Test fun learnerMusicStartsThreeSecondsLater() {
        val mapping = ComparisonTimeMapping.overlap(
            interval(0L, 12_000_000L), interval(0L, 15_000_000L), 3_000_000L
        )!!
        assertEquals(0L, mapping.teacherStartUs)
        assertEquals(12_000_000L, mapping.durationUs)
        assertEquals(10_000_000L, mapping.teacherTimeUs(10_000_000L))
        assertEquals(13_000_000L, mapping.learnerTimeUs(10_000_000L))
    }

    @Test fun learnerMusicStartsEarlier() {
        val mapping = ComparisonTimeMapping.overlap(
            interval(0L, 15_000_000L), interval(0L, 12_000_000L), -3_000_000L
        )!!
        assertEquals(3_000_000L, mapping.teacherStartUs)
        assertEquals(12_000_000L, mapping.durationUs)
        assertEquals(3_000_000L, mapping.teacherTimeUs(0L))
        assertEquals(0L, mapping.learnerTimeUs(0L))
    }

    @Test fun distinctNonZeroSourceValidRanges() {
        val mapping = ComparisonTimeMapping.overlap(
            interval(2_000_000L, 14_000_000L),
            interval(5_000_000L, 20_000_000L), 3_000_000L
        )!!
        assertEquals(2_000_000L, mapping.teacherStartUs)
        assertEquals(14_000_000L, mapping.teacherEndUs)
        assertEquals(5_000_000L, mapping.learnerTimeUs(0L))
    }

    @Test fun noOverlapIsExplicit() {
        assertNull(ComparisonTimeMapping.overlap(
            interval(0L, 3_000_000L), interval(0L, 3_000_000L), 5_000_000L
        ))
        assertNull(ComparisonTimeMapping.overlap(
            interval(0L, 0L), interval(0L, 0L), 0L
        ))
    }

    @Test fun invalidInputsNeverWrap() {
        assertFailsWith<IllegalArgumentException> { SourceInterval(-1L, 3L) }
        assertFailsWith<IllegalArgumentException> {
            ComparisonTimeMapping.overlap(
                interval(0L, 10L), interval(0L, 10L), Long.MIN_VALUE
            )
        }
    }

    @Test fun outOfRangeCompareTimeIsNotSilentlyClamped() {
        val mapping = ComparisonTimeMapping.overlap(
            interval(0L, 10_000L), interval(0L, 20_000L), 0L
        )!!
        assertFailsWith<IllegalArgumentException> { mapping.teacherTimeUs(-1L) }
        assertFailsWith<IllegalArgumentException> { mapping.learnerTimeUs(10_001L) }
    }
}
