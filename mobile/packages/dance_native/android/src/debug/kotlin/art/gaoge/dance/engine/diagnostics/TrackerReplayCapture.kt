package art.gaoge.dance.engine.diagnostics

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.PersonDetection
import art.gaoge.dance.engine.tracking.TrackedPerson
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** Opt-in QA capture of post-inference inputs; contains no source image pixels. */
internal object TrackerReplayCapture {
    const val PROPERTY = "framekernel.tracker.replay"
    val enabled: Boolean get() = !System.getProperty(PROPERTY).isNullOrBlank()

    fun record(ptsUs: Long, detections: List<PersonDetection>, assignedIds: List<Int>?,
        protectedIds: Set<Int>, bodyIds: Set<Int>, faceIds: Set<Int>, actual: List<TrackedPerson>) {
        val path = System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() } ?: return
        val root = File(path).apply { check(isDirectory || mkdirs()) }
        val target = File(root, "%016d.bin.gz".format(ptsUs))
        DataOutputStream(GZIPOutputStream(target.outputStream().buffered())).use { out ->
            out.writeInt(0x464B5452); out.writeInt(1); out.writeLong(ptsUs)
            fun ids(values: Collection<Int>) { out.writeInt(values.size); values.forEach(out::writeInt) }
            ids(protectedIds.sorted()); ids(bodyIds.sorted()); ids(faceIds.sorted())
            if (assignedIds == null) out.writeInt(-1) else ids(assignedIds)
            fun rect(value: FloatRect) {
                out.writeFloat(value.left); out.writeFloat(value.top); out.writeFloat(value.right); out.writeFloat(value.bottom)
            }
            fun optionalRect(value: FloatRect?) { out.writeBoolean(value != null); if (value != null) rect(value) }
            out.writeInt(detections.size)
            for (d in detections) {
                rect(d.bbox); out.writeFloat(d.confidence); out.writeFloat(d.footY)
                val mask = d.mask; out.writeBoolean(mask != null)
                if (mask != null) {
                    out.writeInt(mask.width); out.writeInt(mask.height)
                    out.writeInt(mask.originalWidth); out.writeInt(mask.originalHeight)
                    val mapper = mask.mapper; out.writeBoolean(mapper != null)
                    if (mapper != null) { out.writeInt(mapper.modelInputSize); out.writeInt(mapper.protoSize) }
                    optionalRect(mask.roiInProto); optionalRect(mask.samplingRect)
                    val count = Math.multiplyExact(mask.width, mask.height)
                    require(count in 1..1_000_000 && mask.buffer.capacity() >= count)
                    val bytes = ByteArray(count); mask.buffer.duplicate().apply { position(0) }.get(bytes)
                    out.writeInt(count); out.write(bytes)
                }
            }
            out.writeInt(actual.size)
            for (track in actual) {
                out.writeInt(track.id); rect(track.bbox); out.writeUTF(track.state.name)
                out.writeBoolean(track.observedThisFrame); out.writeInt(track.framesSinceLastObservation)
            }
        }
    }
}
