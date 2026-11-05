package art.gaoge.dance.engine.inference

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HighResolutionPrivacyCoverageTest {
    @Test fun `current face evidence cannot borrow another observation or dormant identity cache`() {
        val stale = art.gaoge.dance.engine.tracking.TrackedPerson(
            1, FloatRect(671f, 882f, 875f, 1551f), null, .8f,
            state = art.gaoge.dance.engine.tracking.TrackState.OCCLUDED, observedThisFrame = false
        )
        val current = PersonDetection(FloatRect(812f, 1219f, 1003f, 1738f), .64f)
        val coverage = HighResolutionPrivacyCoverage.FaceObservations()
        val first = coverage.current(listOf(current))
        val next = coverage.current(listOf(current))
        assertEquals(art.gaoge.dance.engine.tracking.TrackState.OCCLUDED, stale.state)
        assertTrue(first.last().id < 0)
        assertTrue(first.last().id != next.last().id)
        assertEquals(art.gaoge.dance.engine.tracking.TrackState.ACTIVE, first.last().state)
        coverage.current(emptyList())
        val afterGap = coverage.current(listOf(current))
        assertTrue(afterGap.last().id != first.last().id)
        assertTrue(first.last().observedThisFrame)
        assertEquals(current.bbox, first.last().bbox)
    }

    @Test fun `fully protected scene retains every current face observation`() {
        val front = PersonDetection(FloatRect(1095f, 676f, 1579f, 1932f), .89f)
        val mirror = PersonDetection(FloatRect(812f, 1219f, 1003f, 1738f), .64f)
        val observed = HighResolutionPrivacyCoverage.FaceObservations().current(listOf(front, mirror))
        assertEquals(listOf(front.bbox, mirror.bbox), observed.map { it.bbox })
        assertTrue(observed.all { it.observedThisFrame })
    }

    @Test fun `lower-body fragment cannot become an anonymous face while distinct mirror foreground survives`() {
        val mapper = ModelCoordinateMapper(1000, 1000)
        fun mask(left: Int, top: Int, right: Int, bottom: Int): NativeMask {
            val buffer = ByteBuffer.allocateDirect(160 * 160)
            for (y in top until bottom) for (x in left until right) buffer.put(y * 160 + x, 255.toByte())
            return NativeMask(160, 160, buffer, 1000, 1000, mapper)
        }
        val front = PersonDetection(FloatRect(100f, 0f, 900f, 1000f), .9f, mask(16, 0, 80, 160))
        val fragment = PersonDetection(FloatRect(200f, 600f, 400f, 1000f), .8f, mask(32, 96, 64, 160))
        val mirror = PersonDetection(FloatRect(600f, 600f, 800f, 1000f), .6f, mask(96, 96, 128, 160))
        val current = HighResolutionPrivacyCoverage.FaceObservations().current(listOf(front, fragment, mirror))
        assertEquals(listOf(front.bbox, fragment.bbox, mirror.bbox), current.map { it.bbox })
        assertEquals(setOf(current[1].id), HighResolutionPrivacyCoverage.coveredHeadFallbackIds(current))
        // Box containment alone must never discard a distant, separately visible person.
        assertTrue(front.bbox.left < mirror.bbox.left && front.bbox.right > mirror.bbox.right)
    }

    @Test fun `visible legs cannot invent a face on an occluding dancer`() {
        val mapper = ModelCoordinateMapper(1000, 1000)
        fun mask(left: Int, top: Int, right: Int, bottom: Int): NativeMask {
            val bytes = ByteBuffer.allocateDirect(160 * 160)
            for (y in top until bottom) for (x in left until right) bytes.put(y * 160 + x, 255.toByte())
            return NativeMask(160, 160, bytes, 1000, 1000, mapper)
        }
        val front = PersonDetection(FloatRect(100f, 0f, 900f, 1000f), .9f, mask(16, 0, 80, 115))
        val hiddenHead = PersonDetection(FloatRect(200f, 600f, 400f, 1000f), .7f, mask(32, 130, 64, 160))
        val visibleHead = hiddenHead.copy(mask = mask(32, 96, 64, 160))
        // No whole-body foreground overlap: only the expected head is hidden.
        val hidden = HighResolutionPrivacyCoverage.FaceObservations().current(listOf(front, hiddenHead))
        assertEquals(2, hidden.size)
        assertEquals(setOf(hidden[1].id), HighResolutionPrivacyCoverage.coveredHeadFallbackIds(hidden))
        // Keep all observations available to the real face locator, even in an overlap.
        assertEquals(2, HighResolutionPrivacyCoverage.FaceObservations().current(listOf(front, visibleHead)).size)
        val distant = hiddenHead.copy(bbox = FloatRect(600f, 600f, 800f, 1000f), mask = mask(96, 96, 128, 160))
        val seen = HighResolutionPrivacyCoverage.FaceObservations().current(listOf(front, distant))
        assertEquals(2, seen.size)
        assertTrue(HighResolutionPrivacyCoverage.coveredHeadFallbackIds(seen).isEmpty())
    }

    @Test fun `detailed all-person coverage never adopts a selective or follow exemption`() {
        val ids = setOf(1, 2, 3)
        assertTrue(HighResolutionPrivacyCoverage.applies(3840, 2160, ids, ids, emptySet(), false))
        assertTrue(HighResolutionPrivacyCoverage.applies(1920, 1080, ids, emptySet(), ids, false))
        assertFalse(HighResolutionPrivacyCoverage.applies(3840, 2160, ids, setOf(1, 2), emptySet(), false))
        assertFalse(HighResolutionPrivacyCoverage.applies(3840, 2160, ids, ids, emptySet(), true))
        assertFalse(HighResolutionPrivacyCoverage.applies(3840, 2160, ids, setOf(1), setOf(2, 3), false))
        assertFalse(HighResolutionPrivacyCoverage.applies(3840, 2160, emptySet(), ids, emptySet(), false))
        assertFalse(HighResolutionPrivacyCoverage.applies(2160, 4096, setOf(1), setOf(1), emptySet(), false))
        assertFalse(HighResolutionPrivacyCoverage.applies(1280, 720, ids, ids, emptySet(), false))
    }

    @Test fun `tile clipping cannot create an anonymous face on a foreground torso`() {
        val view = HighResolutionPrivacyCoverage.views(3840, 2160)[3]
        val clippedHead = PersonDetection(FloatRect(100f, 0f, 500f, 1000f), .9f)
        val completePerson = PersonDetection(FloatRect(100f, 100f, 500f, 1000f), .7f)
        assertFalse(HighResolutionPrivacyCoverage.isComplete(clippedHead, view, 3840, 2160))
        assertTrue(HighResolutionPrivacyCoverage.isComplete(completePerson, view, 3840, 2160))
    }

    @Test fun `bottom tile body mask is projected to source coordinates without letterbox leakage`() {
        val mapper = ModelCoordinateMapper(3840, 2160)
        val view = HighResolutionPrivacyCoverage.views(3840, 2160)[2]
        val localMapper = ModelCoordinateMapper(view.width.toInt(), view.height.toInt())
        val buffer = ByteBuffer.allocateDirect(160 * 160)
        for (i in 0 until buffer.capacity()) buffer.put(i, 255.toByte())
        val mask = NativeMask(160, 160, buffer, localMapper.srcWidth, localMapper.srcHeight, localMapper)
        val local = PersonDetection(FloatRect(812f, 355f, 1003f, 874f), .64f, mask)
        val scene = HighResolutionPrivacyCoverage.toScene(local, view, mapper)
        assertEquals(FloatRect(812f, 1219f, 1003f, 1738f), scene.bbox)
        assertEquals(1738f, scene.footY)
        val projected = requireNotNull(scene.mask)
        fun value(x: Float, y: Float): Int {
            val px = floor(mapper.sourceToProtoX(x)).toInt()
            val py = floor(mapper.sourceToProtoY(y)).toInt()
            return projected.buffer.get(py * 160 + px).toInt() and 255
        }
        assertEquals(255, value(900f, 1400f))
        assertEquals(0, value(3000f, 1400f))
        assertEquals(0, value(900f, 300f))
        assertEquals(0, projected.buffer.get(0).toInt())
        assertEquals(3840, projected.originalWidth)
        assertEquals(2160, projected.originalHeight)
        assertEquals(mapper, projected.mapper)
    }

    @Test fun `primary measurements stay unchanged while overlapping tile duplicates collapse`() {
        val primary = PersonDetection(FloatRect(100f, 100f, 500f, 1000f), .8f)
        val same = PersonDetection(FloatRect(102f, 99f, 498f, 1001f), .95f)
        val distant = PersonDetection(FloatRect(812f, 1219f, 1003f, 1738f), .64f)
        val duplicate = distant.copy(confidence = .6f)
        val merged = HighResolutionPrivacyCoverage.merge(listOf(primary), listOf(same, duplicate, distant))
        assertEquals(2, merged.size)
        assertSame(primary, merged[0])
        assertSame(distant, merged[1])
    }

    @Test fun `distinct partially occluded neighboring people are retained`() {
        val front = PersonDetection(FloatRect(520f, 1058f, 780f, 1784f), .72f)
        val behind = PersonDetection(FloatRect(473f, 1261f, 612f, 1786f), .41f)
        assertEquals(2, HighResolutionPrivacyCoverage.merge(listOf(front), listOf(behind)).size)
    }
}
