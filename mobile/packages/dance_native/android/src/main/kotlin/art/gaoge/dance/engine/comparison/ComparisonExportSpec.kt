package art.gaoge.dance.engine.comparison

import org.json.JSONObject

/** Frozen export settings. Viewing speed, loop repetition and motion are not export inputs. */
internal data class ComparisonExportSpec(
    val projectId: String,
    val teacherUri: String,
    val learnerUri: String,
    val offsetUs: Long,
    val timeScale: Double,
    val startUs: Long,
    val endUs: Long,
    val width: Int,
    val height: Int,
    val gap: Int,
    val sideBySide: Boolean,
    val mirrorTeacher: Boolean,
    val mirrorLearner: Boolean,
    val teacherPicture: ComparisonPicture,
    val learnerPicture: ComparisonPicture,
) {
    init {
        require(projectId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(teacherUri.isNotBlank() && learnerUri.isNotBlank())
        ComparisonMusicMapping(offsetUs,timeScale)
        require(startUs>=0 && endUs>startUs && endUs<=86_400_000_000L)
        require(width in 16..1920 && height in 16..1920 && width%2==0 && height%2==0)
        require(gap in 1..64 && gap<minOf(width,height))
    }
    fun sourceRange(overlap: ComparisonOverlap): SourceInterval {
        require(endUs<=overlap.durationUs) { "选段已超出当前视频的共同区间" }
        return SourceInterval(overlap.teacherTimeUs(startUs),overlap.teacherTimeUs(endUs))
    }
    fun toJson() = JSONObject().apply {
        put("projectId",projectId);put("teacherUri",teacherUri);put("learnerUri",learnerUri)
        put("offsetUs",offsetUs);put("timeScale",timeScale);put("startUs",startUs);put("endUs",endUs)
        put("width",width);put("height",height);put("gap",gap);put("sideBySide",sideBySide)
        put("mirrorTeacher",mirrorTeacher);put("mirrorLearner",mirrorLearner)
        fun picture(value: ComparisonPicture) = JSONObject().apply {
            put("zoom",value.zoom);put("panX",value.panX);put("panY",value.panY)
        }
        put("teacherPicture",picture(teacherPicture));put("learnerPicture",picture(learnerPicture))
    }
    companion object {
        private fun picture(value: JSONObject?) = value?.let {
            ComparisonPicture(it.getDouble("zoom"),it.getDouble("panX"),it.getDouble("panY"))
        } ?: ComparisonPicture()
        fun fromProject(json: String,start: Long,end: Long,width: Int,height: Int,gap: Int,sideBySide: Boolean): ComparisonExportSpec {
            val p=JSONObject(json)
            require(p.getInt("schemaVersion")==1)
            return ComparisonExportSpec(p.getString("id"),p.getJSONObject("teacher").getString("uri"),
                p.getJSONObject("learner").getString("uri"),p.getLong("offsetUs"),p.optDouble("timeScale",1.0),
                start,end,width,height,gap,sideBySide,p.optBoolean("mirrorTeacher"),p.optBoolean("mirrorLearner"),
                picture(p.optJSONObject("teacherPicture")),picture(p.optJSONObject("learnerPicture")))
        }
        fun fromJson(p: JSONObject) = ComparisonExportSpec(p.getString("projectId"),p.getString("teacherUri"),
            p.getString("learnerUri"),p.getLong("offsetUs"),p.getDouble("timeScale"),p.getLong("startUs"),p.getLong("endUs"),
            p.getInt("width"),p.getInt("height"),p.getInt("gap"),p.getBoolean("sideBySide"),
            p.getBoolean("mirrorTeacher"),p.getBoolean("mirrorLearner"),
            picture(p.getJSONObject("teacherPicture")),picture(p.getJSONObject("learnerPicture")))
    }
}

internal class ComparisonExportCancelled : RuntimeException()
