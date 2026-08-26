package com.danceanon.native.pipeline

import android.graphics.Bitmap
import com.danceanon.native.tracking.TrackedPerson
import java.util.concurrent.ConcurrentHashMap

data class CachedPreviewAnalysis(
    val frameBitmap: Bitmap,
    val trackedPersons: List<TrackedPerson>,
    val timestampMs: Long
)

class PreviewAnalysisCache {

    private val cache = ConcurrentHashMap<String, CachedPreviewAnalysis>()

    fun get(cacheKey: String): CachedPreviewAnalysis? {
        return cache[cacheKey]
    }

    fun put(cacheKey: String, item: CachedPreviewAnalysis) {
        val old = cache.put(cacheKey, item)
        if (old != null && old.frameBitmap != item.frameBitmap) {
            try {
                old.frameBitmap.recycle()
            } catch (_: Throwable) {}
        }
    }

    fun clear() {
        for ((_, item) in cache) {
            try {
                item.frameBitmap.recycle()
            } catch (_: Throwable) {}
        }
        cache.clear()
    }
}
