package com.danceanon.native.storage

import android.content.Context
import android.graphics.Bitmap
import com.danceanon.native.inference.FloatRect
import java.io.File
import java.io.FileOutputStream

class CacheManager(private val context: Context) {

    fun getAnalysisDir(cacheId: String): File {
        val dir = File(context.cacheDir, "analysis/$cacheId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun savePersonThumbnail(
        cacheId: String,
        personId: Int,
        frameBitmap: Bitmap,
        bbox: FloatRect
    ): String {
        val dir = getAnalysisDir(cacheId)
        val file = File(dir, "person_$personId.webp")

        val w = frameBitmap.width
        val h = frameBitmap.height

        // Expand bbox by 10%
        val bw = bbox.width
        val bh = bbox.height
        val cropX1 = (bbox.left - bw * 0.1f).toInt().coerceIn(0, w - 1)
        val cropY1 = (bbox.top - bh * 0.1f).toInt().coerceIn(0, h - 1)
        val cropX2 = (bbox.right + bw * 0.1f).toInt().coerceIn(cropX1 + 1, w)
        val cropY2 = (bbox.bottom + bh * 0.1f).toInt().coerceIn(cropY1 + 1, h)

        val cropW = cropX2 - cropX1
        val cropH = cropY2 - cropY1

        val cropped = Bitmap.createBitmap(frameBitmap, cropX1, cropY1, cropW, cropH)
        val thumb = Bitmap.createScaledBitmap(cropped, 160, 240, true)
        if (cropped != frameBitmap && cropped != thumb) {
            cropped.recycle()
        }

        FileOutputStream(file).use { out ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                thumb.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
            } else {
                thumb.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        }
        thumb.recycle()

        return file.absolutePath
    }

    fun saveVideoUri(cacheId: String, videoUri: String) {
        val dir = getAnalysisDir(cacheId)
        val file = File(dir, "source_uri.txt")
        file.writeText(videoUri)
    }

    fun getVideoUri(cacheId: String): String? {
        val dir = getAnalysisDir(cacheId)
        val file = File(dir, "source_uri.txt")
        return if (file.exists()) file.readText().trim() else null
    }

    fun saveAnalysisMetadata(cacheId: String, metadata: AnalysisMetadata) {
        val dir = getAnalysisDir(cacheId)
        val file = File(dir, "analysis.json")
        val root = org.json.JSONObject().apply {
            put("schemaVersion", metadata.schemaVersion)
            put("sourceUri", metadata.sourceUri)
            val arr = org.json.JSONArray()
            for (p in metadata.persons) {
                val pObj = org.json.JSONObject().apply {
                    put("id", p.id)
                    put("confidence", p.confidence)
                    val bObj = org.json.JSONObject().apply {
                        put("left", p.bbox.left)
                        put("top", p.bbox.top)
                        put("right", p.bbox.right)
                        put("bottom", p.bbox.bottom)
                    }
                    put("bbox", bObj)
                }
                arr.put(pObj)
            }
            put("persons", arr)
        }
        file.writeText(root.toString(2))
    }

    fun getAnalysisMetadata(cacheId: String): AnalysisMetadata? {
        val dir = getAnalysisDir(cacheId)
        val file = File(dir, "analysis.json")
        if (!file.exists()) return null
        return try {
            val root = org.json.JSONObject(file.readText())
            val schemaVersion = root.optInt("schemaVersion", 1)
            val sourceUri = root.optString("sourceUri", "")
            val personsArray = root.optJSONArray("persons") ?: org.json.JSONArray()
            val persons = mutableListOf<CachedPerson>()
            for (i in 0 until personsArray.length()) {
                val pObj = personsArray.getJSONObject(i)
                val id = pObj.getInt("id")
                val conf = pObj.optDouble("confidence", 1.0)
                val bObj = pObj.getJSONObject("bbox")
                val bbox = CachedBBox(
                    left = bObj.getDouble("left"),
                    top = bObj.getDouble("top"),
                    right = bObj.getDouble("right"),
                    bottom = bObj.getDouble("bottom")
                )
                persons.add(CachedPerson(id, bbox, conf))
            }
            AnalysisMetadata(schemaVersion, sourceUri, persons)
        } catch (e: Exception) {
            android.util.Log.w("CacheManager", "Failed to parse analysis.json for cache $cacheId", e)
            null
        }
    }

    fun clearAnalysisCache(cacheId: String) {
        val dir = File(context.cacheDir, "analysis/$cacheId")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}

data class CachedBBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
)

data class CachedPerson(
    val id: Int,
    val bbox: CachedBBox,
    val confidence: Double
)

data class AnalysisMetadata(
    val schemaVersion: Int = 1,
    val sourceUri: String,
    val persons: List<CachedPerson>
)
