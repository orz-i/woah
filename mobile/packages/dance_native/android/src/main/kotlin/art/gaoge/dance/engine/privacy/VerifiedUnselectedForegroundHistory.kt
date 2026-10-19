package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.tracking.TrackManager
import art.gaoge.dance.engine.tracking.TrackedPerson
import java.nio.ByteBuffer

/**
 * Export-scoped, bounded observations of a genuinely observed UNSELECTED root.
 *
 * Supports only a short temporal foreground-interior proof during a current
 * overlapping SELECTED/UNSELECTED detection. It is never used as a synthetic
 * fresh detection, nor for identity selection or a missing-person privacy
 * fallback. The two previous original masks must belong to the SAME observed
 * root, and be geometrically/temporally continuous with its current detection.
 */
class VerifiedUnselectedForegroundHistory {
    data class Evidence(val trackId: Int, val previousMasks: List<NativeMask>)

    private data class Sample(
        val trackId: Int,
        val bbox: FloatRect,
        val ptsUs: Long,
        val mask: NativeMask
    )

    private val recent = ArrayDeque<Sample>(2)

    fun clear() = recent.clear()

    fun priorEvidenceAndRecord(
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        ptsUs: Long,
        preferredUnselectedId: Int? = null
    ): Evidence? {
        val verified = persons.asSequence()
            .filter {
                val mask = it.mask
                mask != null && mask.width > 0 && mask.height > 0 &&
                    mask.width.toLong() * mask.height <= mask.buffer.capacity().toLong() &&
                    (preferredUnselectedId == null || it.id == preferredUnselectedId) &&
                    !selectedPersonIds.contains(it.id) &&
                    it.observedThisFrame &&
                    it.age >= MIN_PROTAGONIST_IDENTITY_AGE_FRAMES &&
                    it.confidence >= MIN_CURRENT_PROTAGONIST_CONFIDENCE &&
                    it.mask != null
            }
            .sortedWith(compareByDescending<TrackedPerson> {
                if (preferredUnselectedId != null && it.id == preferredUnselectedId) 1 else 0
            }.thenByDescending { it.age })
            .firstOrNull()
        if (verified == null) {
            recent.clear()
            return null
        }
        val mask = requireNotNull(verified.mask)
        val previous = recent.toList()
        val accepted = previous.size == 2 &&
            previous.all { prior ->
                prior.trackId == verified.id &&
                    ptsUs > prior.ptsUs &&
                    ptsUs - prior.ptsUs <= MAX_HISTORY_AGE_US &&
                    TrackManager.computeBBoxIoU(verified.bbox, prior.bbox) >=
                        MIN_CURRENT_BBOX_IOU &&
                    sameCoordinateContract(mask, prior.mask)
            }
        if (recent.size == 2) recent.removeFirst()
        recent.addLast(Sample(verified.id, verified.bbox, ptsUs, copyMask(mask)))
        return if (accepted) Evidence(verified.id, previous.map { it.mask }) else null
    }

    private fun sameCoordinateContract(a: NativeMask, b: NativeMask): Boolean =
        a.width == b.width && a.height == b.height &&
            a.originalWidth == b.originalWidth &&
            a.originalHeight == b.originalHeight &&
            a.mapper == b.mapper &&
            a.samplingRect == b.samplingRect

    private fun copyMask(mask: NativeMask): NativeMask {
        val total = mask.width * mask.height
        val buffer = ByteBuffer.allocateDirect(total)
        for (index in 0 until total) buffer.put(mask.buffer.get(index))
        buffer.rewind()
        return mask.copy(buffer = buffer)
    }

    companion object {
        private const val MIN_PROTAGONIST_IDENTITY_AGE_FRAMES = 15
        private const val MIN_CURRENT_PROTAGONIST_CONFIDENCE = 0.60f
        private const val MIN_CURRENT_BBOX_IOU = 0.60f
        private const val MAX_HISTORY_AGE_US = 100_000L
    }
}
