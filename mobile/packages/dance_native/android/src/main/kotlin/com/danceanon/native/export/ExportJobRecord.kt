package com.danceanon.native.export

import com.danceanon.native.bridge.ExportRequestDto
import com.danceanon.native.bridge.JobStatusDto
import org.json.JSONArray
import org.json.JSONObject

data class ExportJobRecord(
    val jobId: String,
    var state: String, // queued, preparing, processing, muxing, completed, failed, cancelled, interrupted
    val sourceUri: String,
    val outputPath: String,
    val analysisCacheId: String,
    val targetWidth: Int,
    val targetHeight: Int,
    val targetFps: Double,
    val videoBitrate: Int,
    val selectedPersonIds: List<Int>,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var progress: Double = 0.0,
    var currentFrame: Long = 0L,
    var totalFrames: Long = 0L,
    var fps: Double = 0.0,
    var outputUri: String? = null,
    var errorCode: String? = null,
    var errorMessage: String? = null,
    val processingProfile: String = "balanced",
    val effectsJson: String = "{}",
    val followJson: String = "{}"
) {
    fun toJobStatusDto(): JobStatusDto {
        return JobStatusDto(
            jobId = jobId,
            state = state,
            currentFrame = currentFrame,
            totalFrames = totalFrames,
            fps = fps,
            progress = progress,
            outputUri = outputUri,
            errorCode = errorCode,
            errorMessage = errorMessage
        )
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("jobId", jobId)
        obj.put("state", state)
        obj.put("sourceUri", sourceUri)
        obj.put("outputPath", outputPath)
        obj.put("analysisCacheId", analysisCacheId)
        obj.put("targetWidth", targetWidth)
        obj.put("targetHeight", targetHeight)
        obj.put("targetFps", targetFps)
        obj.put("videoBitrate", videoBitrate)
        val pIds = JSONArray()
        selectedPersonIds.forEach { pIds.put(it) }
        obj.put("selectedPersonIds", pIds)
        obj.put("createdAt", createdAt)
        obj.put("updatedAt", updatedAt)
        obj.put("progress", progress)
        obj.put("currentFrame", currentFrame)
        obj.put("totalFrames", totalFrames)
        obj.put("fps", fps)
        obj.put("outputUri", outputUri ?: JSONObject.NULL)
        obj.put("errorCode", errorCode ?: JSONObject.NULL)
        obj.put("errorMessage", errorMessage ?: JSONObject.NULL)
        obj.put("processingProfile", processingProfile)
        obj.put("effectsJson", effectsJson)
        obj.put("followJson", followJson)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): ExportJobRecord {
            val pIdsJson = obj.optJSONArray("selectedPersonIds") ?: JSONArray()
            val pIds = mutableListOf<Int>()
            for (i in 0 until pIdsJson.length()) {
                pIds.add(pIdsJson.getInt(i))
            }
            return ExportJobRecord(
                jobId = obj.getString("jobId"),
                state = obj.optString("state", "queued"),
                sourceUri = obj.optString("sourceUri", ""),
                outputPath = obj.optString("outputPath", ""),
                analysisCacheId = obj.optString("analysisCacheId", ""),
                targetWidth = obj.optInt("targetWidth", 0),
                targetHeight = obj.optInt("targetHeight", 0),
                targetFps = obj.optDouble("targetFps", 30.0),
                videoBitrate = obj.optInt("videoBitrate", 4_000_000),
                selectedPersonIds = pIds,
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                progress = obj.optDouble("progress", 0.0),
                currentFrame = obj.optLong("currentFrame", 0L),
                totalFrames = obj.optLong("totalFrames", 0L),
                fps = obj.optDouble("fps", 0.0),
                outputUri = if (obj.isNull("outputUri")) null else obj.optString("outputUri"),
                errorCode = if (obj.isNull("errorCode")) null else obj.optString("errorCode"),
                errorMessage = if (obj.isNull("errorMessage")) null else obj.optString("errorMessage"),
                processingProfile = obj.optString("processingProfile", "balanced"),
                effectsJson = obj.optString("effectsJson", "{}"),
                followJson = obj.optString("followJson", "{}")
            )
        }

        fun fromRequest(
            jobId: String,
            request: ExportRequestDto,
            initialState: String = "preparing"
        ): ExportJobRecord {
            val effectsObj = JSONObject().apply {
                put("fillMode", request.effects.fillMode)
                put("fillColorArgb", request.effects.fillColorArgb)
                put("borderColorArgb", request.effects.borderColorArgb)
                put("opacity", request.effects.opacity)
                put("borderWidth", request.effects.borderWidth)
                put("blurStrength", request.effects.blurStrength)
                put("skinWhiten", request.effects.skinWhiten)
                put("legStretchEnabled", request.effects.legStretchEnabled)
                put("legStretch", request.effects.legStretch)
                put("legZoneTop", request.effects.legZoneTop)
                put("legZoneBottom", request.effects.legZoneBottom)
            }
            val followObj = JSONObject().apply {
                put("enabled", request.follow.enabled)
                put("targetPersonId", request.follow.targetPersonId ?: JSONObject.NULL)
                put("zoom", request.follow.zoom)
                put("smoothFactor", request.follow.smoothFactor)
            }
            return ExportJobRecord(
                jobId = jobId,
                state = initialState,
                sourceUri = request.sourceUri,
                outputPath = request.outputFilePath,
                analysisCacheId = request.analysisCacheId,
                targetWidth = request.targetWidth.toInt(),
                targetHeight = request.targetHeight.toInt(),
                targetFps = request.targetFps,
                videoBitrate = request.videoBitrate.toInt(),
                selectedPersonIds = request.selectedPersonIds.map { it.toInt() },
                processingProfile = request.processingProfile,
                effectsJson = effectsObj.toString(),
                followJson = followObj.toString()
            )
        }
    }
}
