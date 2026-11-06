package art.gaoge.dance.engine.diagnostics

import android.content.Context
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.privacy.FacePrivacyEllipse
import art.gaoge.dance.engine.tracking.TrackedPerson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Opt-in isolated QA-package metadata; no image or tensor dumps. */
internal class PrivacyRegressionCapture private constructor(
    target: File, sourceName: String, outputName: String, width: Int, height: Int,
    private val scaleX: Float, private val scaleY: Float
) : AutoCloseable {
    private val writer = target.bufferedWriter()
    private var index = 0
    init {
        append(JSONObject().put("kind", "header").put("schema", "woah-pixel-crop-v1")
            .put("source_basename", sourceName).put("output_basename", outputName).put("output_width", width).put("output_height", height))
    }
    private fun append(row: JSONObject) { writer.write(row.toString()); writer.newLine(); writer.flush() }
    fun record(pts: Long, crop: FloatRect, regions: Map<Int, FacePrivacyEllipse>, persons: List<TrackedPerson>, hero: Int?) {
        val row = JSONObject().put("kind", "crop").put("frame_index", index++).put("t_us", pts)
            .put("source_crop_normalized", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom)))
            .put("face_regions", JSONArray(regions.toSortedMap().map { (id, face) ->
                JSONObject().put("id", id).put("center", JSONArray(listOf(face.centerX * scaleX, face.centerY * scaleY)))
                    .put("radii", JSONArray(listOf(face.radiusX * scaleX, face.radiusY * scaleY))).put("source", face.source.name)
            }))
            .put("observations", JSONArray(persons.map { person ->
                val r = person.bbox
                JSONObject().put("id", person.id).put("bbox", JSONArray(listOf(r.left * scaleX, r.top * scaleY, r.right * scaleX, r.bottom * scaleY)))
                    .put("predicted", !person.observedThisFrame)
            }))
        if (hero != null) row.put("unmasked_person_id", hero)
        append(row)
    }
    override fun close() { writer.close() }
    companion object {
        fun open(context: Context, sourceName: String, outputName: String, width: Int, height: Int,
            sourceWidth: Int, sourceHeight: Int, trackingWidth: Int, trackingHeight: Int): PrivacyRegressionCapture? {
            if (!context.packageName.endsWith(".privacyqa")) return null
            val directory = requireNotNull(context.getExternalFilesDir(null))
            return PrivacyRegressionCapture(File(directory, "qa_pixel_crops_${UUID.randomUUID()}.jsonl"), sourceName, outputName,
                width, height, sourceWidth.toFloat() / trackingWidth, sourceHeight.toFloat() / trackingHeight)
        }
    }
}
