package com.danceanon.native.pipeline

import android.graphics.Bitmap
import com.danceanon.native.tracking.TrackedPerson

data class CachedPreviewAnalysis(
    val frameBitmap: Bitmap,
    val trackedPersons: List<TrackedPerson>,
    val timestampMs: Long
)

/**
 * Thread-safe LRU Cache for preview analysis frames and track states.
 * Automatically recycles evicted Bitmaps to prevent native memory leaks.
 */
class PreviewAnalysisCache(val maxEntries: Int = 4) {

    private val lock = Any()
    private val lruMap = object : LinkedHashMap<String, CachedPreviewAnalysis>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPreviewAnalysis>?): Boolean {
            if (size > maxEntries && eldest != null) {
                recycleItem(eldest.value)
                return true
            }
            return false
        }
    }

    private fun recycleItem(item: CachedPreviewAnalysis) {
        try {
            if (!item.frameBitmap.isRecycled) {
                item.frameBitmap.recycle()
            }
        } catch (_: Throwable) {}
    }

    fun get(cacheKey: String): CachedPreviewAnalysis? {
        synchronized(lock) {
            return lruMap[cacheKey]
        }
    }

    fun put(cacheKey: String, item: CachedPreviewAnalysis) {
        synchronized(lock) {
            val old = lruMap.put(cacheKey, item)
            if (old != null && old.frameBitmap != item.frameBitmap) {
                recycleItem(old)
            }
        }
    }

    fun clearForAnalysis(cacheId: String) {
        synchronized(lock) {
            val iterator = lruMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith("${cacheId}_") || entry.key == cacheId) {
                    recycleItem(entry.value)
                    iterator.remove()
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            for (item in lruMap.values) {
                recycleItem(item)
            }
            lruMap.clear()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return lruMap.size
        }
    }
}
