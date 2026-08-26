package com.danceanon.native.pipeline

import android.graphics.Bitmap
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class PreviewAnalysisCacheTest {

    private fun createDummyEntry(ts: Long): CachedPreviewAnalysis {
        val bmp = Mockito.mock(Bitmap::class.java)
        return CachedPreviewAnalysis(
            frameBitmap = bmp,
            trackedPersons = emptyList(),
            timestampMs = ts
        )
    }

    @Test
    fun testLruEvictionRecyclesOldest() {
        val cache = PreviewAnalysisCache(maxEntries = 3)

        val entry1 = createDummyEntry(100)
        val entry2 = createDummyEntry(200)
        val entry3 = createDummyEntry(300)
        val entry4 = createDummyEntry(400)

        cache.put("key1", entry1)
        cache.put("key2", entry2)
        cache.put("key3", entry3)
        assertEquals(3, cache.size())

        // Inserting 4th entry should evict key1
        cache.put("key4", entry4)
        assertEquals(3, cache.size())
        assertNull(cache.get("key1"))
        assertNotNull(cache.get("key2"))
        assertNotNull(cache.get("key3"))
        assertNotNull(cache.get("key4"))

        Mockito.verify(entry1.frameBitmap).recycle()
    }

    @Test
    fun testClearForAnalysisSelectivelyRemovesTargetCache() {
        val cache = PreviewAnalysisCache(maxEntries = 5)

        val entryA0 = createDummyEntry(0)
        val entryA500 = createDummyEntry(500)
        val entryB0 = createDummyEntry(0)

        cache.put("projA_0", entryA0)
        cache.put("projA_500", entryA500)
        cache.put("projB_0", entryB0)

        assertEquals(3, cache.size())

        cache.clearForAnalysis("projA")
        assertEquals(1, cache.size())
        assertNull(cache.get("projA_0"))
        assertNull(cache.get("projA_500"))
        assertNotNull(cache.get("projB_0"))

        Mockito.verify(entryA0.frameBitmap).recycle()
        Mockito.verify(entryA500.frameBitmap).recycle()

        cache.clear()
        assertEquals(0, cache.size())
        Mockito.verify(entryB0.frameBitmap).recycle()
    }
}
