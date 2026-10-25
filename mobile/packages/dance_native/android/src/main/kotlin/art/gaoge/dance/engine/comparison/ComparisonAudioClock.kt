package art.gaoge.dance.engine.comparison

import java.util.ArrayDeque

/** Resample ONE audio renderer's published position; never align the two videos
 * independently. Media3's public position can arrive in ~10ms steps. Extrapolation
 * is bounded to 15ms and discarded on pause/seek/speed change, not a free clock.
 */
internal class ComparisonAudioClock {
    private data class Anchor(val mediaUs: Long,val ns: Long)
    private val anchors=ArrayDeque<Anchor>()
    private var speed=1f
    private var lastRaw: Long?=null
    var maxPublishedStepUs=0L
        private set
    var maxAheadUs=0L
        private set
    fun reset() { anchors.clear(); lastRaw=null }
    fun sample(rawUs: Long,nowNs: Long,playbackSpeed: Float): Long {
        if (speed!=playbackSpeed) { reset(); speed=playbackSpeed }
        lastRaw?.let { if (rawUs>=it) maxPublishedStepUs=maxOf(maxPublishedStepUs,rawUs-it) }
        lastRaw=rawUs
        anchors.addLast(Anchor(rawUs,nowNs))
        while (anchors.size>64 || nowNs-anchors.first().ns>250_000_000L) anchors.removeFirst()
        var estimate=rawUs
        anchors.forEach {
            estimate=maxOf(estimate,it.mediaUs+((nowNs-it.ns)/1000.0*speed).toLong())
        }
        estimate=estimate.coerceIn(rawUs,rawUs+15_000L)
        maxAheadUs=maxOf(maxAheadUs,estimate-rawUs)
        return estimate
    }
}
