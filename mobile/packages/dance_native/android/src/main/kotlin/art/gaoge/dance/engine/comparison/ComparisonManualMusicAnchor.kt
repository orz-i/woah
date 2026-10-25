package art.gaoge.dance.engine.comparison

/** Human-entered times for the same MUSIC event. Never inferred from motion. */
internal data class ComparisonManualMusicAnchor(val teacherUs: Long, val learnerUs: Long) {
    companion object {
        const val MAX_SOURCE_US = 86_400_000_000L
        const val MAX_OFFSET_US = 300_000_000L

        /** Exact microseconds; accept seconds or minutes:seconds, never round input. */
        fun parseTime(value: String): Long {
            val match = Regex("(?:(\\d+):)?(\\d+)(?:\\.(\\d{1,6}))?")
                .matchEntire(value.trim()) ?: throw IllegalArgumentException("Use seconds or minutes:seconds")
            val minutes = match.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull()
            require(match.groupValues[1].isEmpty() || minutes != null) { "Time overflow" }
            val seconds = match.groupValues[2].toLongOrNull() ?: throw IllegalArgumentException("Time overflow")
            require(seconds <= 86_400L && (minutes == null || seconds < 60L)) { "Invalid seconds" }
            require(minutes == null || minutes <= 1_440L) { "Invalid minutes" }
            val fraction = match.groupValues[3].padEnd(6, '0').toLong()
            val result = ((minutes ?: 0L) * 60L + seconds) * 1_000_000L + fraction
            require(result <= MAX_SOURCE_US) { "Time exceeds M0 source limit" }
            return result
        }

        fun formatTime(valueUs: Long): String {
            require(valueUs in 0..MAX_SOURCE_US)
            return "${valueUs / 1_000_000L}.${(valueUs % 1_000_000L).toString().padStart(6, '0')}"
        }
    }

    val offsetUs: Long get() = learnerUs - teacherUs

    init {
        require(teacherUs in 0..MAX_SOURCE_US && learnerUs in 0..MAX_SOURCE_US)
        require(offsetUs in -MAX_OFFSET_US..MAX_OFFSET_US) { "Offset exceeds M0 limit" }
    }

    fun overlap(teacherDurationUs: Long, learnerDurationUs: Long): ComparisonOverlap {
        require(teacherUs < teacherDurationUs && learnerUs < learnerDurationUs) {
            "Music anchor must lie inside both source videos"
        }
        return requireNotNull(ComparisonTimeMapping.overlap(SourceInterval(0, teacherDurationUs),
            SourceInterval(0, learnerDurationUs), offsetUs)) { "No common interval" }
    }
}
