package art.gaoge.dance.engine.privacy

import android.content.Context
import android.util.Base64
import art.gaoge.dance.engine.BuildConfig
import art.gaoge.dance.engine.face.FaceHeadRoiPlan
import art.gaoge.dance.engine.face.FaceObservation
import art.gaoge.dance.engine.face.coreRecord
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.tracking.TrackedPerson
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Explicit, isolated QA capture. It never supplies observations to the policy. */
internal class AndroidFaceObservationCapture private constructor(
    private val directory: File, private val jobId: String, private val targets: Set<Long>
) {
    fun begin(ptsUs: Long, mapper: ModelCoordinateMapper, persons: List<TrackedPerson>): Session? {
        if (ptsUs !in targets) return null
        return runCatching { Session(directory, jobId, ptsUs, mapper, persons) }.getOrNull()
    }

    class Session(directory: File, jobId: String, private val ptsUs: Long,
                  mapper: ModelCoordinateMapper, persons: List<TrackedPerson>) {
        private val directory = directory
        private val calls = JSONArray()
        private val root = JSONObject().put("schema", "frame-kernel-face-roi-observation-capture-v1")
            .put("job_id", jobId).put("timestamp_us", ptsUs)
            .put("source_width", mapper.srcWidth).put("source_height", mapper.srcHeight)
            .put("input_size", mapper.modelInputSize).put("proto_size", mapper.protoSize)
            .put("scale", mapper.scale).put("pad_left", mapper.padLeft).put("pad_top", mapper.padTop)
            .put("normal_detector_calls", calls)
        private fun rect(r: FloatRect) = JSONArray(listOf(r.left, r.top, r.right, r.bottom))
        init {
            val rows = JSONArray()
            for (person in persons) {
                val row = JSONObject().put("id", person.id).put("bbox", rect(person.bbox))
                    .put("observed_this_frame", person.observedThisFrame)
                val mask = person.mask
                if (mask != null && mask.width > 0 && mask.height > 0) {
                    val count = Math.multiplyExact(mask.width, mask.height)
                    if (count <= 1024 * 1024 && mask.buffer.limit() >= count) {
                        val bytes = ByteArray(count)
                        mask.buffer.duplicate().apply { position(0); limit(count); get(bytes) }
                        row.put("mask_width", mask.width).put("mask_height", mask.height)
                            .put("mask_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        mask.mapper?.let { m -> row.put("mask_transform", JSONArray(listOf(
                            m.scale, m.padLeft, m.padTop, m.srcWidth, m.srcHeight, m.modelInputSize, m.protoSize))) }
                    }
                }
                rows.put(row)
            }
            root.put("persons", rows)
        }
        fun detector(trackId: Int, plan: FaceHeadRoiPlan, faces: List<FaceObservation>, selected: FaceObservation?) {
            runCatching {
                calls.put(JSONObject().put("track_id", trackId).put("source_rect", rect(plan.sourceRect))
                    .put("output_size", plan.outputSize).put("anchor", JSONArray(listOf(plan.anchorX, plan.anchorY)))
                    .put("rows18", JSONArray(faces.map { JSONArray(it.coreRecord().toList()) }))
                    .put("selected_row18", selected?.let { JSONArray(it.coreRecord().toList()) } ?: JSONObject.NULL))
            }
        }
        fun finish(regions: Map<Int, FacePrivacyEllipse>) {
            runCatching {
                root.put("rendered_regions", JSONArray(regions.map { (id, r) ->
                    JSONObject().put("id", id).put("region", JSONArray(listOf(r.centerX, r.centerY, r.radiusX, r.radiusY)))
                        .put("source", r.source.name)
                }))
                val target = File(directory, "qa_face_roi_observation_${ptsUs}_${UUID.randomUUID()}.json")
                val temporary = File(directory, target.name + ".tmp")
                temporary.writeText(root.toString())
                check(temporary.renameTo(target))
            }
        }
    }
    companion object {
        fun create(context: Context, jobId: String?): AndroidFaceObservationCapture? = runCatching {
            if (!BuildConfig.DEBUG || !context.packageName.endsWith(".privacyqa") || jobId == null) return null
            val directory = context.getExternalFilesDir(null) ?: return null
            val control = File(directory, "qa_capture_face_pts_us.txt")
            if (!control.isFile || control.length() > 1024) return null
            val tokens = control.readText().trim().split(Regex("[\\s,]+"))
            val targets = tokens.mapNotNull { it.toLongOrNull()?.takeIf { n -> n >= 0 } }
            if (targets.isEmpty() || targets.size != tokens.size || targets.size > 16) return null
            AndroidFaceObservationCapture(directory, jobId, targets.toSet())
        }.getOrNull()
    }
}
