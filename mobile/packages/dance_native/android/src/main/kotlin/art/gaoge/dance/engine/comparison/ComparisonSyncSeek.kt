package art.gaoge.dance.engine.comparison

/** A vendor extractor may land on a B-frame sync sample whose PTS is after the target. */
internal object ComparisonSyncSeek {
    data class Result(val syncPtsUs: Long, val retries: Int)

    fun atOrBefore(targetUs: Long, seek: (Long) -> Long): Result {
        require(targetUs >= 0)
        var requestUs=targetUs
        var retries=0
        while (true) {
            val actualUs=seek(requestUs)
            check(actualUs >= 0) { "No random access sample for target=$targetUs" }
            if (actualUs <= targetUs) return Result(actualUs,retries)
            check(requestUs > 0 && retries < 4) {
                "No preceding sync sample: target=$targetUs actual=$actualUs request=$requestUs"
            }
            // Retry only a future landing. Keep both players paused, never show
            // that future frame or loosen the requested source-interval check.
            requestUs=(requestUs-maxOf(1_000_000L,actualUs-targetUs+1L)).coerceAtLeast(0L)
            retries++
        }
    }
}
