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
        require(frame.endUs > overlap.teacherStartUs && frame.startUs < overlap.teacherEndUs)
        if (forward) return if (frame.endUs < overlap.teacherEndUs) frame.endUs
            else maxOf(frame.startUs, overlap.teacherStartUs)
        if (frame.startUs <= overlap.teacherStartUs) return overlap.teacherStartUs
        val probe = frame.startUs - 1
        val before = previous(probe)
        check(before.contains(probe) && before.startUs < frame.startUs)
        return maxOf(overlap.teacherStartUs, before.startUs)
    }
}
