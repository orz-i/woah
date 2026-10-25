package art.gaoge.dance.engine.comparison

import kotlin.math.abs
import kotlin.math.ceil

/** GL submission metadata only. Captured/physical pixels need independent evidence. */
internal class ComparisonGlDiagnostics {
    data class Sample(val teacherUs: Long, val learnerUs: Long, val targetUs: Long,
                      val offsetUs: Long, val submitNs: Long, val clockAgeUs: Long,
                      val coverageValid: Boolean,val workUs: Long=0,val swapUs: Long=0,
                      val prepareUs: Long=0,val latchUs: Long=0) {
        val errorUs: Long get() = learnerUs - teacherUs - offsetUs
    }
    data class Window(val startUs: Long, val samples: Int, val p95Us: Long,
                      val maxUs: Long, val meanSignedUs: Double)
    data class Summary(val samples: Int, val p95Us: Long?, val maxUs: Long?,
                       val meanSignedUs: Double?, val p95ClockAgeUs: Long?,
                       val maxClockAgeUs: Long?, val coverageMisses: Int,
                       val durationUs: Long, val saturated: Boolean,
                       val windows: List<Window>, val examples: List<Sample>,
                       val p95WorkUs: Long?=null,val p95SwapUs: Long?=null,
                       val p95PrepareUs: Long?=null,val p95LatchUs: Long?=null)
    private var epoch = 0L
    private val samples = ArrayList<Sample>()
    private var saturated = false
    @Synchronized fun reset(): Long {
        samples.clear()
        saturated = false
        return ++epoch
    }
    @Synchronized fun generation(): Long = epoch
    @Synchronized fun add(generation: Long, sample: Sample) {
        if (generation != epoch) return
        if (samples.size < 50_000) samples.add(sample) else saturated = true
    }
    @Synchronized fun snapshot(): Summary {
        if (samples.isEmpty()) return Summary(0,null,null,null,null,null,0,0,
            saturated,emptyList(),emptyList())
        val ready = samples.first().submitNs + 1_000_000_000L
        val stable = samples.filter { it.submitNs >= ready }
        if (stable.isEmpty()) return Summary(0,null,null,null,null,null,0,0,
            saturated,emptyList(),emptyList())
        val errors = stable.map { abs(it.errorUs) }.sorted()
        val ages = stable.map { it.clockAgeUs }.sorted()
        return Summary(stable.size,p95(errors),errors.last(),
            stable.map { it.errorUs.toDouble() }.average(),p95(ages),ages.last(),
            stable.count { !it.coverageValid },
            (stable.last().submitNs-ready)/1000,saturated,
            stable.groupBy { (it.submitNs-ready)/5_000_000_000L }.entries.take(120).map {
                (window, rows) ->
                val values=rows.map { abs(it.errorUs) }.sorted()
                Window(window*5_000_000L,rows.size,p95(values),values.last(),
                    rows.map { it.errorUs.toDouble() }.average())
            },stable.takeLast(24),
            p95(stable.map { it.workUs }.sorted()),p95(stable.map { it.swapUs }.sorted()),
            p95(stable.map { it.prepareUs }.sorted()),p95(stable.map { it.latchUs }.sorted()))
    }
    private fun p95(values: List<Long>): Long =
        values[(ceil(values.size*.95).toInt()-1).coerceAtLeast(0)]
}
