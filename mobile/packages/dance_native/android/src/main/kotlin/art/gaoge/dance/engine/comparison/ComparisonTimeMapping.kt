package art.gaoge.dance.engine.comparison

import kotlin.math.max
import kotlin.math.min

/**
 * Media-source timestamps use signed microseconds, and never share a clock with
 * device monotonic time. Positive offset: the same music event occurs later in
 * the learner's source. This mapping deliberately preserves late/early moves.
 */
internal data class SourceInterval(val startUs: Long, val endUs: Long) {
    init {
        require(startUs >= 0L && endUs >= startUs) { "Invalid source interval" }
    }
}

/** compareUs = 0 at the first available common *music* time. */
internal data class ComparisonOverlap(
    val teacherStartUs: Long,
    val teacherEndUs: Long,
    val offsetUs: Long
) {
    val durationUs: Long get() = teacherEndUs - teacherStartUs

    fun teacherTimeUs(compareUs: Long): Long {
        require(compareUs in 0..durationUs) { "Compare position outside overlap" }
        return teacherStartUs + compareUs
    }

    fun learnerTimeUs(compareUs: Long): Long = teacherTimeUs(compareUs) + offsetUs
}

internal object ComparisonTimeMapping {
    // M0 only handles common-speed, constant offsets for videos <= 24 hours.
    // Explicit range limits also prevent long overflow in source subtraction.
    private const val MAX_MEDIA_US = 86_400_000_000L

    fun overlap(
        teacher: SourceInterval,
        learner: SourceInterval,
        offsetUs: Long
    ): ComparisonOverlap? {
        require(teacher.endUs <= MAX_MEDIA_US && learner.endUs <= MAX_MEDIA_US)
        require(offsetUs in -MAX_MEDIA_US..MAX_MEDIA_US)

        val startUs = max(teacher.startUs, learner.startUs - offsetUs)
        val endUs = min(teacher.endUs, learner.endUs - offsetUs)
        if (endUs <= startUs) return null
        return ComparisonOverlap(startUs, endUs, offsetUs)
    }
}
