package art.gaoge.dance.engine.comparison

import kotlin.math.*

/** Source microseconds. Music alone defines correspondence; early/late moves remain. */
internal data class ComparisonMusicMapping(val offsetUs: Long, val timeScale: Double = 1.0) {
    init {
        require(offsetUs in -172_800_000_000L..172_800_000_000L)
        require(timeScale.isFinite() && timeScale in .5..2.0)
    }
    fun learnerTimeUs(teacherUs: Long) = ComparisonTimeCore.map(teacherUs,timeScale,offsetUs)
    fun teacherTimeUs(learnerUs: Long) = ComparisonTimeCore.map(learnerUs,timeScale,offsetUs,true)
    fun inverse() = ComparisonMusicMapping(art.gaoge.dance.engine.core.FrameKernel.timeInverseNative(timeScale,offsetUs),1/timeScale)
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
        val range=ComparisonTimeCore.overlap(teacher.startUs,teacher.endUs,learner.startUs,learner.endUs,timeScale,offsetUs) ?: return null
        return ComparisonOverlap(range[0],range[1],mapping.offsetUs,mapping.timeScale)
    }
}
