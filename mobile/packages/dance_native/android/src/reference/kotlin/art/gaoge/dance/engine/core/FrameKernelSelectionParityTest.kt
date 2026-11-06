package art.gaoge.dance.engine.core

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.HighResolutionPrivacyCoverage
import art.gaoge.dance.engine.inference.PersonDetection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameKernelSelectionParityTest {
    @Test
    fun uniformScopesAndFallbackPrecedenceMatchTheCommonCorpus() {
        val values = listOf(-3, 2, 9, 44)
        fun ids(mask: Int) = values.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
        val roots = values.take(3).toSet()
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: Int) { digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        fun putId(value: Int) { digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong()).array()) }
        for (body in 0..15) for (face in 0..15) for (hero in listOf(null) + values) {
            val bodyIds = ids(body); val faceIds = ids(face)
            val actual = FrameKernel.sceneScope(roots, bodyIds, faceIds, hero)
            val expected = if (hero != null) {
                if (hero in roots && bodyIds.isEmpty() && faceIds == roots - hero) FrameKernel.SCENE_OTHER_FACES else 0
            } else if (faceIds.isEmpty() && bodyIds.containsAll(roots)) FrameKernel.SCENE_BODY
            else if (bodyIds.isEmpty() && faceIds.containsAll(roots)) FrameKernel.SCENE_FACE else 0
            assertEquals(expected, actual)
            put(actual)
        }
        for (selected in 0..15) for (covered in 0..15) for (explicit in 0..15) {
            val actual = FrameKernel.compositorTargets(ids(selected), ids(covered), ids(explicit)).sorted()
            assertEquals((ids(selected) - (ids(covered) - ids(explicit))).sorted(), actual)
            put(actual.size); actual.forEach(::putId)
        }
        assertEquals("1b5ca65615939625878106d6f85088fab8cdef5b5ed45dbf4f10a58df33f2117",
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        assertEquals(0, FrameKernel.sceneScope(emptySet(), roots, emptySet(), null))
    }

    @Test
    fun onlyOneCurrentObservedHeroIsExemptAndOtherPeopleReceiveFreshLeases() {
        val a = PersonDetection(FloatRect(10f, 20f, 30f, 100f), 0.9f)
        val hero = PersonDetection(FloatRect(200f, 20f, 300f, 100f), 0.9f)
        val coverage = HighResolutionPrivacyCoverage.FaceObservations()
        val first = coverage.current(listOf(a, hero), 6, hero.bbox)
        val next = coverage.current(listOf(a, hero), 6, hero.bbox)
        assertEquals(6, first[1].id); assertEquals(6, next[1].id)
        assertTrue(first[0].id < 0 && first[0].id != next[0].id)
        assertTrue(coverage.current(listOf(a, hero, hero), 6, hero.bbox).none { it.id == 6 })
        assertTrue(coverage.current(listOf(a, hero), 6, null).none { it.id == 6 })
        assertEquals(-1, FrameKernel.currentHeroIndex(listOf(a.bbox), hero.bbox))
    }
}
