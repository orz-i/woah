package art.gaoge.dance.engine.pipeline

import android.graphics.Bitmap
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class PreviewAnalysisCacheTest {

    private fun createDummyEntry(ts: Long): CachedPreviewAnalysis {
        val bmp = Mockito.mock(Bitmap::class.java)
        val copy = Mockito.mock(Bitmap::class.java)
        Mockito.`when`(bmp.copy(Bitmap.Config.ARGB_8888, true)).thenReturn(copy)
        return CachedPreviewAnalysis(
            frameBitmap = bmp,
            trackedPersons = emptyList(),
            timestampMs = ts
        )
    }

    @Test
    fun copyRemainsOwnedByCallerAfterEviction() {
        val cache = PreviewAnalysisCache(maxEntries = 1)
        val original = createDummyEntry(0)
        cache.put("a", original)
        val copy = assertNotNull(cache.copyForUse("a"))
        kotlin.test.assertNotSame(original.frameBitmap, copy.frameBitmap)
        cache.put("b", createDummyEntry(1))
        Mockito.verify(original.frameBitmap).recycle()
        Mockito.verify(copy.frameBitmap, Mockito.never()).recycle()
        cache.clear()
        Mockito.verify(copy.frameBitmap, Mockito.never()).recycle()
    }

    @Test
    fun clearCannotRecycleWhileCopyIsReadingBitmap() {
        val cache = PreviewAnalysisCache()
        val original = createDummyEntry(0)
        val copied = Mockito.mock(Bitmap::class.java)
        val copying = java.util.concurrent.CountDownLatch(1)
        val allowCopy = java.util.concurrent.CountDownLatch(1)
        val clearing = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        Mockito.`when`(original.frameBitmap.copy(Bitmap.Config.ARGB_8888, true)).thenAnswer {
            copying.countDown()
            check(allowCopy.await(2, java.util.concurrent.TimeUnit.SECONDS))
            Mockito.verify(original.frameBitmap, Mockito.never()).recycle()
            copied
        }
        try {
            cache.put("a", original)
            val reader = pool.submit<CachedPreviewAnalysis?> { cache.copyForUse("a") }
            kotlin.test.assertTrue(copying.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val clear = pool.submit { clearing.countDown(); cache.clear() }
            kotlin.test.assertTrue(clearing.await(2, java.util.concurrent.TimeUnit.SECONDS))
            Mockito.verify(original.frameBitmap, Mockito.never()).recycle()
            allowCopy.countDown()
            kotlin.test.assertSame(copied, reader.get(2, java.util.concurrent.TimeUnit.SECONDS)?.frameBitmap)
            clear.get(2, java.util.concurrent.TimeUnit.SECONDS)
            Mockito.verify(original.frameBitmap).recycle()
            Mockito.verify(copied, Mockito.never()).recycle()
        } finally {
            allowCopy.countDown()
            pool.shutdownNow()
        }
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
        assertNull(cache.copyForUse("key1"))
        assertNotNull(cache.copyForUse("key2"))
        assertNotNull(cache.copyForUse("key3"))
        assertNotNull(cache.copyForUse("key4"))

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
        assertNull(cache.copyForUse("projA_0"))
        assertNull(cache.copyForUse("projA_500"))
        assertNotNull(cache.copyForUse("projB_0"))

        Mockito.verify(entryA0.frameBitmap).recycle()
        Mockito.verify(entryA500.frameBitmap).recycle()

        cache.clear()
        assertEquals(0, cache.size())
        Mockito.verify(entryB0.frameBitmap).recycle()
    }
}
