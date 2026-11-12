package art.gaoge.dance.engine.comparison

/** Shared timeline interval [A, B). Audio and both decoders restart together. */
internal data class ComparisonLoop(val startUs: Long, val endUs: Long) {
    init { require(startUs >= 0 && endUs > startUs) }
    fun contains(positionUs: Long) = positionUs in startUs until endUs
    fun reached(positionUs: Long) = positionUs >= endUs
}

/** Navigate decoded presentation intervals, including VFR and a clipped first frame. */
internal object ComparisonFrameNavigation {
    fun target(frame: ComparisonFrameInterval, forward: Boolean,
        overlap: ComparisonOverlap, previous: (Long) -> ComparisonFrameInterval): Long {
        val plan=art.gaoge.dance.engine.core.FrameKernel.frameStepNative(frame.startUs,frame.endUs,
            overlap.teacherStartUs,overlap.teacherEndUs,forward,-1,-1)
        if(plan[1]==0L) return plan[0]
        val before=previous(plan[0])
        return art.gaoge.dance.engine.core.FrameKernel.frameStepNative(frame.startUs,frame.endUs,
            overlap.teacherStartUs,overlap.teacherEndUs,forward,before.startUs,before.endUs)[0]
    }
}
