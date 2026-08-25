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

    fun clearAnalysisCache(cacheId: String) {
        val dir = File(context.cacheDir, "analysis/$cacheId")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
