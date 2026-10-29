package art.gaoge.dance.engine.comparison

import kotlin.math.*

/** Source microseconds. Music alone defines correspondence; early/late moves remain. */
internal data class ComparisonMusicMapping(val offsetUs: Long, val timeScale: Double = 1.0) {
    init {
        require(offsetUs in -172_800_000_000L..172_800_000_000L)
        require(timeScale.isFinite() && timeScale in .5..2.0)
    }
    fun learnerTimeUs(teacherUs: Long) = (teacherUs*timeScale).roundToLong()+offsetUs
    fun teacherTimeUs(learnerUs: Long) = ((learnerUs-offsetUs)/timeScale).roundToLong()
    fun inverse() = ComparisonMusicMapping((-offsetUs/timeScale).roundToLong(),1/timeScale)
}

internal data class SourceInterval(val startUs: Long, val endUs: Long) {
    init { require(startUs >= 0L && endUs >= startUs) { "Invalid source interval" } }
}

/** Common positions are measured in reference-source time, with an exclusive end. */
internal data class ComparisonOverlap(val teacherStartUs: Long, val teacherEndUs: Long,
    val offsetUs: Long, val timeScale: Double = 1.0) {
    val durationUs: Long get() = teacherEndUs-teacherStartUs
    val mapping get() = ComparisonMusicMapping(offsetUs,timeScale)
    fun teacherTimeUs(compareUs: Long): Long {
        require(compareUs in 0..durationUs) { "Compare position outside overlap" }
        return teacherStartUs+compareUs
    }
    fun learnerTimeUs(compareUs: Long) = mapping.learnerTimeUs(teacherTimeUs(compareUs))
}

internal object ComparisonTimeMapping {
    fun overlap(teacher: SourceInterval,learner: SourceInterval,offsetUs: Long,
        timeScale: Double = 1.0): ComparisonOverlap? {
        require(teacher.endUs <= 86_400_000_000L && learner.endUs <= 86_400_000_000L)
        val mapping=ComparisonMusicMapping(offsetUs,timeScale)
        val start=max(teacher.startUs,ceil((learner.startUs-offsetUs)/timeScale).toLong())
        // Keep the mapped integer microsecond strictly inside the target end.
        val end=min(teacher.endUs,floor((learner.endUs-1-offsetUs)/timeScale).toLong()+1)
        if (end<=start) return null
        return ComparisonOverlap(start,end,mapping.offsetUs,mapping.timeScale)
    }
}
